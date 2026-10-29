package com.github.tvbox.osc.playback;

import android.content.Context;

import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.NetworkPolicy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Call;

/** 仅loopback监听；URL只含不可预测的租约和资源ID，不接受任意目标参数。 */
public final class ProxyRouter {
    private static volatile ProxyRouter instance;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");
    private final OkHttpClient client = new OkHttpClient.Builder().dns(NetworkPolicy.publicDns())
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build();
    private final Map<String, MediaLease> leases = new LinkedHashMap<>();
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
    private final LocalServer server;
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor();

    static final class Resource {
        final String id;
        final String parentId;
        volatile String url;
        Resource(String id, String parent, String url) { this.id = id; this.parentId = parent; this.url = url; }
    }

    private ProxyRouter() throws IOException {
        server = new LocalServer();
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        // 换号/退出后即使播放器暂未再次读数据，也及时停止旧身份的网络连接。
        expiry.scheduleWithFixedDelay(this::prune, 1, 1, TimeUnit.SECONDS);
    }

    public static ProxyRouter get(Context context) throws CloudException {
        ProxyRouter router = instance;
        if (router != null) return router;
        synchronized (ProxyRouter.class) {
            if (instance == null) try { instance = new ProxyRouter(); }
            catch (IOException ignored) { throw new CloudException(CloudException.Code.NETWORK, null); }
            return instance;
        }
    }

    public MediaLease publishAnonymous(String source, String adapter, long generation, String url, Map<String, String> headers) throws CloudException {
        return publishAnonymous(source, adapter, generation, url, headers, isHls(url));
    }

    public MediaLease publishAnonymous(String source, String adapter, long generation, String url, Map<String, String> headers, boolean hls) throws CloudException {
        Map<String, String> safe = anonymousHeaders(headers);
        return publish(source, adapter, generation, "", "", 0, url, hls, () -> true,
                target -> safe, null, null);
    }

    public MediaLease publishPlaylist(String source, String adapter, long generation, String baseUrl, String playlist, Map<String, String> headers) throws CloudException {
        if (playlist == null || !playlist.startsWith("#EXTM3U") || playlist.getBytes(UTF8).length > 1048576)
            throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        MediaLease lease = publishAnonymous(source, adapter, generation, baseUrl, headers, true);
        lease.memoryPlaylist = playlist;
        return lease;
    }

    public synchronized MediaLease publish(String source, String adapter, long generation, String provider, String account, long epoch,
                                          String url, boolean hls, MediaLease.Validator validator, MediaLease.Headers headers,
                                          MediaLease.Refresher refresher, Runnable onRelease) throws CloudException {
        NetworkPolicy.requireUrl(url, null, !provider.isEmpty());
        if (source == null || !source.matches("[A-Za-z0-9_-]{1,64}") || adapter == null || adapter.length() > 64 || generation < 0)
            throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        prune();
        if (leases.size() >= 32) throw new CloudException(CloudException.Code.NETWORK, null);
        String token = random(32);
        String local = "http://127.0.0.1:" + server.getListeningPort() + "/media/" + token + "/root" + (hls ? ".m3u8" : ".bin");
        MediaLease lease = new MediaLease(source, adapter, generation, provider, account, epoch, token, local, url, hls,
                validator, headers, refresher, onRelease);
        lease.resources.put("root", new Resource("root", "", url));
        lease.resourceUrlBytes = url.length();
        leases.put(token, lease);
        return lease;
    }

    public synchronized boolean validateOwnLeaseUrl(String url) { MediaLease lease = own(url); return lease != null && lease.isValid(); }
    /** 来源结果仍在交付途中；Runtime丢弃取消的success时也会回收其临时文件。 */
    public synchronized void bindPendingDelivery(String url, com.github.tvbox.osc.cloud.Cancellation request) throws CloudException {
        MediaLease lease = own(url);
        if (lease == null || !lease.isValid()) throw new CloudException(CloudException.Code.CANCELLED, null);
        lease.claimed = false;
        lease.pendingDeliveryLink = request.attach(() -> releaseUnclaimed(url));
    }
    public synchronized boolean claim(String url) {
        MediaLease lease = own(url); if (lease == null || !lease.isValid()) return false;
        lease.claimed = true; unlinkPending(lease); return true;
    }
    private synchronized void releaseUnclaimed(String url) { MediaLease lease = own(url); if (lease != null && !lease.claimed) close(lease); }
    private static void unlinkPending(MediaLease lease) { if (lease.pendingDeliveryLink != null) { try { lease.pendingDeliveryLink.close(); } catch (Exception ignored) { } lease.pendingDeliveryLink = null; } }
    public synchronized boolean retain(String url) { MediaLease lease = own(url); if (lease == null || !lease.isValid()) return false; lease.references++; return true; }
    public synchronized CloudException lastFailure(String url) { MediaLease lease = own(url); return lease == null ? null : lease.lastFailure; }

    public synchronized void release(String url) {
        MediaLease lease = own(url);
        if (lease == null || --lease.references > 0) return;
        close(lease);
    }

    private MediaLease own(String value) {
        HttpUrl url = HttpUrl.parse(value == null ? "" : value);
        if (url == null || !"http".equals(url.scheme()) || !"127.0.0.1".equals(url.host())
                || url.port() != server.getListeningPort() || !url.username().isEmpty() || !url.password().isEmpty() || url.query() != null) return null;
        if (url.fragment() != null) return null;
        List<String> path = url.pathSegments();
        if (path.size() != 3 || !"media".equals(path.get(0)) || !path.get(2).matches("(?:root|[a-f0-9]{24})\\.(?:m3u8|bin)")) return null;
        MediaLease lease = leases.get(path.get(1));
        if (lease == null) return null;
        String resourceId = path.get(2).replaceFirst("\\.(?:m3u8|bin)$", "");
        synchronized (lease) { return lease.resources.containsKey(resourceId) ? lease : null; }
    }

    private void prune() {
        long now = System.nanoTime();
        List<MediaLease> snapshot; synchronized (this) { snapshot = new ArrayList<>(leases.values()); }
        for (MediaLease lease : snapshot) {
            if (!lease.isValid() || now - lease.lastAccess > 3600000000000L) synchronized (this) { close(lease); }
        }
    }

    private void close(MediaLease lease) {
        if (lease.closed) return;
        lease.closed = true; leases.remove(lease.token);
        unlinkPending(lease);
        lease.cancellation.cancel();
        synchronized (lease) {
            for (Call call : new ArrayList<>(lease.activeCalls)) call.cancel();
            lease.activeCalls.clear();
            List<Response> responses = new ArrayList<>(lease.activeResponses); lease.activeResponses.clear();
            cleanup.execute(() -> { for (Response response : responses) response.close(); if (lease.onRelease != null) lease.onRelease.run(); });
        }
    }

    private final class LocalServer extends NanoHTTPD {
        LocalServer() { super("127.0.0.1", 0); }
        @Override public Response serve(IHTTPSession session) {
            if (session.getMethod() != Method.GET && session.getMethod() != Method.HEAD) return denied();
            String host = session.getHeaders().get("host");
            if (!("127.0.0.1:" + getListeningPort()).equals(host) || !session.getParameters().isEmpty()) return denied();
            String[] parts = session.getUri().split("/");
            if (parts.length != 4 || !"media".equals(parts[1])) return denied();
            if (!parts[3].matches("(?:root|[a-f0-9]{24})\\.(?:m3u8|bin)")) return denied();
            MediaLease lease;
            synchronized (ProxyRouter.this) { lease = leases.get(parts[2]); }
            if (lease == null || !lease.isValid()) return denied();
            String resourceId = parts[3].replaceFirst("\\.(?:m3u8|bin)$", "");
            Resource resource;
            synchronized (lease) { resource = lease.resources.get(resourceId); lease.lastAccess = System.nanoTime(); }
            if (resource == null) return denied();
            String range = session.getHeaders().get("range");
            if (range != null && !range.matches("bytes=(?:[0-9]{1,18}-[0-9]{0,18}|-[0-9]{1,18})")) return denied();
            try { return serveResource(lease, resource, range, true); }
            catch (CloudException failure) { lease.lastFailure = failure; return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, failure.getMessage()); }
            catch (Exception ignored) { return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "媒体暂不可用"); }
        }
    }

    private NanoHTTPD.Response serveResource(MediaLease lease, Resource resource, String range, boolean canRefresh) throws Exception {
        if (!lease.isValid()) return denied();
        if ("root".equals(resource.id) && lease.memoryPlaylist != null) return playlistResponse(lease, resource, lease.memoryPlaylist, lease.mediaRevision);
        Upstream connection = fetch(lease, resource, range, lease.cancellation);
        Response upstream = connection.response;
        if ((upstream.code() == 401 || upstream.code() == 403 || upstream.code() == 410) && canRefresh && lease.refresher != null) {
            connection.close();
            renew(lease, resource, connection.revision);
            return serveResource(lease, resource, range, false);
        }
        if (!upstream.isSuccessful() || upstream.body() == null) {
            int status = upstream.code(); connection.close();
            return NanoHTTPD.newFixedLengthResponse(status == 416 ? NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE : NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                    NanoHTTPD.MIME_PLAINTEXT, "上游媒体暂不可用");
        }
        String mime = upstream.header("Content-Type", "application/octet-stream");
        if (isHls(resource.url) || mime.toLowerCase(Locale.ROOT).contains("mpegurl") || ("root".equals(resource.id) && lease.hls)) {
            String text;
            try { adoptEffectiveBase(lease, resource, connection); text = readPlaylist(connection); }
            finally { connection.close(); }
            return playlistResponse(lease, resource, text, connection.revision);
        }
        InputStream body = new FilterInputStream(upstream.body().byteStream()) {
            @Override public int read() throws IOException { if (!lease.isValid()) throw new IOException("媒体租约已关闭"); lease.lastAccess = System.nanoTime(); return super.read(); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException { if (!lease.isValid()) throw new IOException("媒体租约已关闭"); lease.lastAccess = System.nanoTime(); return super.read(bytes, offset, length); }
            @Override public void close() throws IOException { try { super.close(); } finally { connection.close(); } }
        };
        NanoHTTPD.Response response = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.lookup(upstream.code()), mime, body);
        for (String key : new String[]{"Content-Range", "Accept-Ranges", "ETag", "Last-Modified"}) if (upstream.header(key) != null) response.addHeader(key, upstream.header(key));
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static final class Upstream implements AutoCloseable {
        final MediaLease lease; final Call call; final Response response; final AutoCloseable cancellationLink; final long revision;
        Upstream(MediaLease lease, Call call, Response response, AutoCloseable cancellationLink, long revision) { this.lease = lease; this.call = call; this.response = response; this.cancellationLink = cancellationLink; this.revision = revision; }
        @Override public void close() { synchronized (lease) { lease.activeCalls.remove(call); lease.activeResponses.remove(response); } try { cancellationLink.close(); } catch (Exception ignored) { } response.close(); }
    }

    private Upstream fetch(MediaLease lease, Resource resource, String range, com.github.tvbox.osc.cloud.Cancellation cancellation) throws Exception {
        String value; long revision;
        synchronized (lease) { value = resource.url; revision = lease.mediaRevision; }
        HttpUrl target = NetworkPolicy.requireUrl(value, null, !lease.provider.isEmpty());
        for (int redirect = 0; redirect <= 4; redirect++) {
            cancellation.check();
            if (!lease.isValid()) throw new CloudException(CloudException.Code.CANCELLED, null);
            Request.Builder request = new Request.Builder().url(target).header("Accept-Encoding", "identity");
            Map<String, String> headers = lease.headers.forTarget(target.toString());
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey();
                if (name.equalsIgnoreCase("User-Agent") || name.equalsIgnoreCase("Referer") || name.equalsIgnoreCase("Origin")
                        || name.equalsIgnoreCase("Accept") || name.equalsIgnoreCase("Cookie") || name.equalsIgnoreCase("Authorization"))
                    request.header(name, header.getValue());
            }
            if (range != null) request.header("Range", range);
            Call call = client.newCall(request.build());
            synchronized (lease) { if (!lease.isValid()) throw new CloudException(CloudException.Code.CANCELLED, null); lease.activeCalls.add(call); }
            AutoCloseable cancellationLink = cancellation.attach(call::cancel);
            ScheduledFuture<?> headerDeadline = expiry.schedule(call::cancel, 20, TimeUnit.SECONDS);
            Response response;
            try { response = call.execute(); }
            catch (Exception failure) { synchronized (lease) { lease.activeCalls.remove(call); } cancellationLink.close(); throw failure; }
            finally { headerDeadline.cancel(false); }
            synchronized (lease) { lease.activeResponses.add(response); }
            Upstream connection = new Upstream(lease, call, response, cancellationLink, revision);
            if (!lease.isValid()) { connection.close(); throw new CloudException(CloudException.Code.CANCELLED, null); }
            if (response.code() < 300 || response.code() >= 400 || response.header("Location") == null) return connection;
            String location = response.header("Location"); connection.close();
            HttpUrl next = target.resolve(location);
            if (next == null) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
            target = NetworkPolicy.requireUrl(next.toString(), null, !lease.provider.isEmpty());
            // 每一跳重新向按目标主机判断的supplier取头；不沿用上一跳Cookie。
        }
        throw new CloudException(CloudException.Code.NETWORK, null);
    }

    private NanoHTTPD.Response playlistResponse(MediaLease lease, Resource parent, String text, long expectedRevision) throws CloudException {
        if (!text.startsWith("#EXTM3U")) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        synchronized (lease) { if (!lease.isValid() || lease.mediaRevision != expectedRevision) throw new CloudException(CloudException.Code.CANCELLED, null); }
        StringBuilder output = new StringBuilder();
        long sequence = 0;
        int index = 0;
        for (String line : text.split("\\r?\\n")) {
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) try { sequence = Long.parseLong(line.substring(22).trim()); } catch (NumberFormatException ignored) { }
            String changed = line;
            if (!line.isEmpty() && !line.startsWith("#")) changed = child(lease, parent, "line:" + sequence++, line, expectedRevision);
            else {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer buffer = new StringBuffer();
                int attribute = 0;
                while (matcher.find()) matcher.appendReplacement(buffer, Matcher.quoteReplacement("URI=\"" + child(lease, parent, "attr:" + index + ":" + attribute++, matcher.group(1), expectedRevision) + "\""));
                matcher.appendTail(buffer); changed = buffer.toString();
            }
            output.append(changed).append('\n'); index++;
        }
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/vnd.apple.mpegurl", output.toString());
        response.addHeader("Cache-Control", "no-store"); return response;
    }

    private String child(MediaLease lease, Resource parent, String key, String relative, long expectedRevision) throws CloudException {
        if (relative.length() > 8192) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        HttpUrl target = resolveChildTarget(parent, relative, !lease.provider.isEmpty());
        synchronized (lease) {
            if (!lease.isValid() || lease.mediaRevision != expectedRevision) throw new CloudException(CloudException.Code.CANCELLED, null);
            String semantic = parent.id + ":" + key;
            String id = lease.semanticIds.get(semantic);
            long newSize = lease.resourceUrlBytes + target.toString().length() - (id == null ? 0 : lease.resources.get(id).url.length());
            if (newSize > 2097152) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
            if (id == null) {
                if (lease.resources.size() >= 8192) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
                id = random(12); lease.semanticIds.put(semantic, id);
                lease.resources.put(id, new Resource(id, parent.id, target.toString()));
            } else lease.resources.get(id).url = target.toString();
            lease.resourceUrlBytes = newSize;
            return "http://127.0.0.1:" + server.getListeningPort() + "/media/" + lease.token + "/" + id + (isHls(target.toString()) ? ".m3u8" : ".bin");
        }
    }

    private void renew(MediaLease lease, Resource failed, long failedRevision) throws Exception {
        boolean leader = lease.refresh.run(lease.cancellation, failedRevision,
                () -> { synchronized (lease) { return lease.mediaRevision; } }, () -> withRefreshBudget(lease, operation -> {
            String next = lease.refresher.refresh(operation);
            NetworkPolicy.requireUrl(next, null, true); operation.check();
            synchronized (lease) {
                if (!lease.isValid()) throw new CloudException(CloudException.Code.CANCELLED, null);
                Resource root = lease.resources.get("root"); lease.resourceUrlBytes += next.length() - root.url.length();
                lease.upstream = next; root.url = next; lease.mediaRevision++;
            }
            if (!"root".equals(failed.id)) reloadParent(lease, failed.parentId, 0, operation);
        }));
        if (!leader && !"root".equals(failed.id)) withRefreshBudget(lease, operation -> reloadParent(lease, failed.parentId, 0, operation));
    }

    private interface RefreshWork { void run(com.github.tvbox.osc.cloud.Cancellation cancellation) throws Exception; }
    private void withRefreshBudget(MediaLease lease, RefreshWork work) throws Exception {
        com.github.tvbox.osc.cloud.Cancellation operation = new com.github.tvbox.osc.cloud.Cancellation();
        try (AutoCloseable link = lease.cancellation.attach(operation::cancel)) {
            ScheduledFuture<?> deadline = expiry.schedule(operation::cancel, 45, TimeUnit.SECONDS);
            try { work.run(operation); operation.check(); }
            catch (Exception error) {
                if (operation.isCancelled() && !lease.cancellation.isCancelled()) throw new CloudException(CloudException.Code.NETWORK, null);
                throw error;
            } finally { deadline.cancel(false); }
        }
    }

    private void reloadParent(MediaLease lease, String id, int depth, com.github.tvbox.osc.cloud.Cancellation cancellation) throws Exception {
        if (depth > 2) throw new CloudException(CloudException.Code.NETWORK, null);
        Resource parent; synchronized (lease) { parent = lease.resources.get(id); }
        if (parent == null) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        if (!"root".equals(id)) reloadParent(lease, parent.parentId, depth + 1, cancellation);
        try (Upstream connection = fetch(lease, parent, null, cancellation)) {
            Response response = connection.response;
            if (!response.isSuccessful() || response.body() == null) throw new CloudException(CloudException.Code.NETWORK, null);
            adoptEffectiveBase(lease, parent, connection);
            // 重新建立相同媒体序列对应的URL，不把失效片段盲拼到另一地址。
            playlistResponse(lease, parent, readPlaylist(connection), connection.revision);
        }
    }

    /** 重定向后的播放列表以最终响应URL为相对基址，刷新父链遵守同一合同。 */
    static void adoptEffectivePlaylistBase(Resource parent, Response response, boolean httpsOnly) throws CloudException {
        parent.url = NetworkPolicy.requireUrl(response.request().url().toString(), null, httpsOnly).toString();
    }

    private void adoptEffectiveBase(MediaLease lease, Resource parent, Upstream connection) throws CloudException {
        synchronized (lease) {
            if (!lease.isValid() || lease.mediaRevision != connection.revision) throw new CloudException(CloudException.Code.CANCELLED, null);
            String original = parent.url; adoptEffectivePlaylistBase(parent, connection.response, !lease.provider.isEmpty());
            long next = lease.resourceUrlBytes - original.length() + parent.url.length();
            if (next > 2097152) { parent.url = original; throw new CloudException(CloudException.Code.INVALID_RESPONSE, null); }
            lease.resourceUrlBytes = next;
        }
    }

    private String readPlaylist(Upstream connection) throws IOException {
        ScheduledFuture<?> deadline = expiry.schedule(connection.call::cancel, 20, TimeUnit.SECONDS);
        try { return readLimited(connection.response.body().byteStream()); }
        finally { deadline.cancel(false); }
    }

    static HttpUrl resolveChildTarget(Resource parent, String relative, boolean httpsOnly) throws CloudException {
        HttpUrl base = HttpUrl.parse(parent.url), target = base == null ? null : base.resolve(relative);
        if (target == null) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        return NetworkPolicy.requireUrl(target.toString(), null, httpsOnly);
    }

    private static String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
        while ((n = input.read(buffer)) != -1) { if (output.size() + n > 1048576) throw new IOException("播放列表过大"); output.write(buffer, 0, n); }
        String text = new String(output.toByteArray(), UTF8); return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static boolean isHls(String value) { HttpUrl url = HttpUrl.parse(value == null ? "" : value); return url != null && url.encodedPath().toLowerCase(Locale.ROOT).endsWith(".m3u8"); }
    private static Map<String, String> anonymousHeaders(Map<String, String> input) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (input != null) for (Map.Entry<String, String> header : input.entrySet()) {
            if (header.getValue() == null || header.getValue().indexOf('\r') >= 0 || header.getValue().indexOf('\n') >= 0) continue;
            if (header.getKey().equalsIgnoreCase("User-Agent") || header.getKey().equalsIgnoreCase("Referer") || header.getKey().equalsIgnoreCase("Origin") || header.getKey().equalsIgnoreCase("Accept")) safe.put(header.getKey(), header.getValue());
        }
        return Collections.unmodifiableMap(safe);
    }
    private static String random(int bytes) { byte[] value = new byte[bytes]; RANDOM.nextBytes(value); StringBuilder out = new StringBuilder(); for (byte b : value) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }
    private static NanoHTTPD.Response denied() { return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "请求未授权"); }
}
