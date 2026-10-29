package com.github.tvbox.osc.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import okhttp3.HttpUrl;

/** 仅负责官方协议，不弹UI、不主动登录、不遍历用户网盘。 */
public final class CloudProviderClient {
    public static final class LoginTicket {
        public final CloudProvider provider;
        public final long createdAt;
        private final transient String token;
        private final transient String cookie;
        private final long deadlineNanos;
        final long waitBudgetMillis;
        LoginTicket(CloudProvider provider, String token, String cookie) {
            this(provider, token, cookie, 180000);
        }
        LoginTicket(CloudProvider provider, String token, String cookie, long remainingMillis) {
            this.provider = provider; this.token = token; this.cookie = cookie;
            this.createdAt = System.currentTimeMillis();
            // 这是本次交互的等待预算，不是对服务端二维码有效期的承诺。
            waitBudgetMillis = Math.max(1, Math.min(180000, remainingMillis));
            this.deadlineNanos = System.nanoTime() + waitBudgetMillis * 1000000L;
        }
        public boolean isExpired() { return System.nanoTime() >= deadlineNanos; }
        long remainingMillis() { return Math.max(0, (deadlineNanos - System.nanoTime()) / 1000000L); }
        public String qrContent() {
            HttpUrl.Builder url = HttpUrl.parse(provider == CloudProvider.QUARK ? "https://su.quark.cn/4_eMHBJ" : "https://su.uc.cn/1_n0ZCv").newBuilder();
            return url.addQueryParameter("uc_param_str", "").addQueryParameter("token", token)
                    .addQueryParameter("client_id", provider.scanClientId)
                    .addQueryParameter("uc_biz_str", "S:custom|C:titlebar_fix").build().toString();
        }
        @Override public String toString() { return "LoginTicket(" + provider.id + ",redacted)"; }
    }

    public static final class SharePage {
        public final List<CloudFile> items;
        public final int page;
        public final boolean hasMore;
        SharePage(List<CloudFile> items, int page, boolean hasMore) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items)); this.page = page; this.hasMore = hasMore;
        }
    }

    public static final class Stream {
        final transient String url;
        public final String resolution;
        public final List<String> availableResolutions;
        Stream(String url, String resolution, List<String> available) { this.url = url; this.resolution = resolution; this.availableResolutions = Collections.unmodifiableList(new ArrayList<>(available)); }
        @Override public String toString() { return "CloudStream(redacted)"; }
    }

    private static final class ShareTicket {
        final String value;
        final long createdAt;
        ShareTicket(String value) { this.value = value; createdAt = System.currentTimeMillis(); }
    }

    private final CloudSessionManager sessions;
    private final CloudHttpTransport transport = new CloudHttpTransport();
    private final Map<CloudProvider, Object> locks = new EnumMap<>(CloudProvider.class);
    private final Map<String, ShareTicket> shareTickets = new LinkedHashMap<String, ShareTicket>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, ShareTicket> entry) { return size() > 64; }
    };

    public CloudProviderClient(CloudSessionManager sessions) {
        this.sessions = sessions;
        for (CloudProvider provider : CloudProvider.values()) locks.put(provider, new Object());
    }

    public LoginTicket createLoginTicket(CloudProvider provider, Cancellation cancellation) throws CloudException {
        return createLoginTicket(provider, cancellation, 180000);
    }
    LoginTicket createLoginTicket(CloudProvider provider, Cancellation cancellation, long remainingMillis) throws CloudException {
        long deadline = System.nanoTime() + Math.max(0, remainingMillis) * 1000000L;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", provider.tokenClientId); params.put("v", "1.2"); params.put("request_id", UUID.randomUUID().toString());
        HttpUrl.Builder url = HttpUrl.parse(provider.loginBase + "getTokenForQrcodeLogin").newBuilder();
        CloudHttpTransport.Result response;
        if (provider == CloudProvider.UC) {
            url.addQueryParameter("__dt", "1000").addQueryParameter("__t", String.valueOf(System.currentTimeMillis()));
            response = transport.form(provider, url.build(), params, "", cancellation, remainingMillis);
        } else {
            for (Map.Entry<String, String> param : params.entrySet()) url.addQueryParameter(param.getKey(), param.getValue());
            response = transport.request(provider, url.build(), null, "", cancellation, remainingMillis);
        }
        if (response.status != 200) throw new CloudException(CloudException.Code.NETWORK, provider);
        String token = text(object(object(response.json, "data"), "members"), "token");
        if (token.isEmpty() || token.length() > 4096) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
        long left = (deadline - System.nanoTime()) / 1000000L;
        if (left <= 0) throw new CloudException(CloudException.Code.QR_EXPIRED, provider);
        return new LoginTicket(provider, token, CookieValues.merge("", response.cookies), left);
    }

    /** 返回false表示尚待手机确认；成功提交也必须匹配当前授权事务。 */
    static final class LoginResult {
        final String account;
        final transient String cookie;
        LoginResult(String account, String cookie) { this.account = account; this.cookie = cookie; }
    }

    LoginResult pollLogin(LoginTicket ticket, Cancellation cancellation) throws CloudException {
        cancellation.check();
        CloudProvider provider = ticket.provider;
        if (ticket.isExpired()) throw new CloudException(CloudException.Code.QR_EXPIRED, provider);
        HttpUrl url = HttpUrl.parse(provider.loginBase + "getServiceTicketByQrcodeToken").newBuilder()
                .addQueryParameter("token", ticket.token).addQueryParameter("client_id", provider.scanClientId)
                .addQueryParameter("v", "1.2").addQueryParameter("request_id", UUID.randomUUID().toString()).build();
        CloudHttpTransport.Result response = transport.request(provider, url, null, ticket.cookie, cancellation, ticket.remainingMillis());
        if (response.status != 200) throw new CloudException(CloudException.Code.NETWORK, provider);
        String serviceTicket = text(object(object(response.json, "data"), "members"), "service_ticket");
        if (serviceTicket.isEmpty()) {
            String message = text(response.json, "message").toLowerCase(java.util.Locale.ROOT);
            if (message.contains("过期") || message.contains("expired")) throw new CloudException(CloudException.Code.QR_EXPIRED, provider);
            return null;
        }
        HttpUrl accountUrl = HttpUrl.parse(provider.webBase + "account/info").newBuilder().addQueryParameter("st", serviceTicket).addQueryParameter("lw", "scan").build();
        String temporaryCookie = CookieValues.merge(ticket.cookie, response.cookies);
        if (ticket.isExpired()) throw new CloudException(CloudException.Code.QR_EXPIRED, provider);
        CloudHttpTransport.Result account = transport.request(provider, accountUrl, null, temporaryCookie, cancellation, ticket.remainingMillis());
        if (account.status != 200 || !bool(account.json, "success")) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
        String cookie = CookieValues.merge(temporaryCookie, account.cookies);
        Map<String, String> cookieMap = CookieValues.parse(cookie);
        if (!cookieMap.containsKey("__pus") && !cookieMap.containsKey("__puus")) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
        String identity = identity(object(account.json, "data"));
        if (identity.isEmpty()) identity = cookieMap.get("__uid");
        if (identity == null || identity.isEmpty()) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
        cancellation.check();
        return new LoginResult(digest(provider.id + ":" + identity), cookie);
    }

    public SharePage listShare(CloudReference reference, int page, Cancellation cancellation) throws CloudException {
        return listShare(reference, page, sessions.require(reference.cloudProvider()), cancellation);
    }

    private SharePage listShare(CloudReference reference, int page, CloudSessionManager.Snapshot expected, Cancellation cancellation) throws CloudException {
        if (page < 1 || page > 20) throw new CloudException(CloudException.Code.INVALID_RESPONSE, reference.cloudProvider());
        String token = shareToken(reference, expected, cancellation);
        Map<String, String> query = params("pwd_id", reference.shareId, "stoken", token, "pdir_fid", reference.folderId,
                "force", "0", "_page", String.valueOf(page), "_size", "100", "_sort", "file_type:asc,file_name:asc");
        JsonObject result = api(reference.cloudProvider(), "share/sharepage/detail", query, null, expected, cancellation);
        JsonArray rows = array(object(result, "data"), "list");
        List<CloudFile> files = new ArrayList<>();
        for (JsonElement row : rows) {
            if (!row.isJsonObject()) continue;
            JsonObject item = row.getAsJsonObject();
            String fid = text(item, "fid"), name = text(item, "file_name");
            if (!validId(fid) || name.isEmpty() || name.length() > 512) continue;
            boolean directory = bool(item, "dir");
            boolean video = "video".equals(text(item, "obj_category")) || text(item, "mime_type").startsWith("video/");
            if (!directory && !video) continue;
            files.add(new CloudFile(fid, reference.folderId, name, directory, video, number(item, "size"), text(item, "share_fid_token"), page));
        }
        long total = number(object(result, "metadata"), "_total");
        return new SharePage(files, page, total > 0 ? page * 100L < total : rows.size() == 100);
    }

    CloudFile findFile(CloudReference reference, CloudSessionManager.Snapshot expected, Cancellation cancellation) throws CloudException {
        for (int page = reference.page; page <= Math.min(reference.page + 1, 20); page++) {
            SharePage result = listShare(reference, page, expected, cancellation);
            for (CloudFile item : result.items) if (reference.fileId.equals(item.id) && item.video) return item;
            if (!result.hasMore) break;
        }
        throw new CloudException(CloudException.Code.NO_MATCH, reference.cloudProvider());
    }

    String createDirectory(CloudProvider provider, String uniqueName, CloudSessionManager.Snapshot expected, Cancellation cancellation) throws CloudException {
        JsonObject data = json("pdir_fid", "0", "file_name", uniqueName, "dir_path", "");
        data.addProperty("dir_init_lock", false);
        String id = text(object(api(provider, "file", Collections.emptyMap(), data, expected, cancellation), "data"), "fid");
        if (!validId(id)) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
        return id;
    }

    String saveFile(CloudReference reference, CloudFile file, String directoryId, CloudSessionManager.Snapshot expected, Cancellation cancellation) throws CloudException {
        String token = shareToken(reference, expected, cancellation);
        JsonObject body = json("to_pdir_fid", directoryId, "pwd_id", reference.shareId, "stoken", token, "pdir_fid", reference.folderId, "scene", "link");
        JsonArray ids = new JsonArray(); ids.add(file.id); body.add("fid_list", ids);
        JsonArray tokens = new JsonArray(); tokens.add(file.fileToken); body.add("fid_token_list", tokens);
        return text(object(api(reference.cloudProvider(), "share/sharepage/save", Collections.emptyMap(), body, expected, cancellation), "data"), "task_id");
    }

    String savedFileId(CloudProvider provider, String taskId, CloudSessionManager.Snapshot expected, Cancellation cancellation) throws CloudException {
        if (!validId(taskId)) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
        for (int attempt = 0; attempt < 4; attempt++) {
            JsonObject task = object(api(provider, "task", params("task_id", taskId, "retry_index", String.valueOf(attempt)), null, expected, cancellation), "data");
            JsonArray ids = array(object(task, "save_as"), "save_as_top_fids");
            if (ids.size() == 1 && validId(ids.get(0).getAsString())) return ids.get(0).getAsString();
            pause(cancellation, 750);
        }
        return "";
    }

    public Stream playFile(CloudProvider provider, String fid, String quality, Cancellation cancellation) throws CloudException {
        return playFile(provider, fid, quality, sessions.require(provider), cancellation);
    }

    public Stream playFile(CloudProvider provider, String fid, String quality, CloudSessionManager.Snapshot expected, Cancellation cancellation) throws CloudException {
        if (!validId(fid)) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
        if ("original".equals(quality)) {
            JsonObject body = new JsonObject(); JsonArray fids = new JsonArray(); fids.add(fid); body.add("fids", fids);
            JsonArray data = api(provider, "file/download", Collections.emptyMap(), body, expected, cancellation).getAsJsonArray("data");
            String url = data != null && data.size() > 0 ? text(data.get(0).getAsJsonObject(), "download_url") : "";
            if (url.isEmpty()) throw new CloudException(CloudException.Code.ENTITLEMENT_REQUIRED, provider);
            NetworkPolicy.requireUrl(url, null, true);
            return new Stream(url, "original", Collections.singletonList("original"));
        }
        JsonObject body = json("fid", fid, "resolutions", "normal,low,high,super,2k,4k", "supports", "fmp4");
        JsonArray videos = array(object(api(provider, "file/v2/play", Collections.emptyMap(), body, expected, cancellation), "data"), "video_list");
        List<String> available = new ArrayList<>();
        for (JsonElement row : videos) if (row.isJsonObject()) {
            JsonObject item = row.getAsJsonObject(); String resolution = text(item, "resolution");
            if (resolution.matches("low|normal|high|super|2k|4k") && !text(object(item, "video_info"), "url").isEmpty() && !available.contains(resolution)) available.add(resolution);
        }
        String[] preference = "auto".equals(quality) ? new String[]{"super", "high", "normal", "low", "2k", "4k"} : new String[]{quality};
        for (String resolution : preference) for (JsonElement row : videos) {
            JsonObject item = row.getAsJsonObject();
            String url = text(object(item, "video_info"), "url");
            if (resolution.equals(text(item, "resolution")) && !url.isEmpty()) {
                NetworkPolicy.requireUrl(url, null, true);
                return new Stream(url, resolution, available);
            }
        }
        throw new CloudException(CloudException.Code.ENTITLEMENT_REQUIRED, provider);
    }

    void deleteOwnedFile(CloudProvider provider, String fid, CloudSessionManager.Snapshot expected, Cancellation cancellation) throws CloudException {
        if (!validId(fid)) throw new CloudException(CloudException.Code.OWNERSHIP_UNKNOWN, provider);
        JsonArray files = new JsonArray(); files.add(fid);
        JsonObject body = json("action_type", "2", "exclude_fids", ""); body.addProperty("filelist", files.toString());
        api(provider, "file/delete", Collections.emptyMap(), body, expected, cancellation);
    }

    private String shareToken(CloudReference reference, CloudSessionManager.Snapshot expected, Cancellation cancellation) throws CloudException {
        CloudSessionManager.Snapshot state = sessions.require(reference.cloudProvider());
        if (state.authEpoch != expected.authEpoch || !state.accountId.equals(expected.accountId)) throw new CloudException(CloudException.Code.CANCELLED, state.provider);
        String key = state.provider.id + ":" + state.accountId + ":" + state.authEpoch + ":" + reference.shareId;
        synchronized (shareTickets) {
            ShareTicket ticket = shareTickets.get(key);
            if (ticket != null && System.currentTimeMillis() - ticket.createdAt < 300000) return ticket.value;
        }
        JsonObject data = object(api(state.provider, "share/sharepage/token", Collections.emptyMap(), json("pwd_id", reference.shareId, "passcode", reference.passcode), expected, cancellation), "data");
        String token = text(data, "stoken");
        if (token.isEmpty() || token.length() > 16384) throw new CloudException(CloudException.Code.NO_MATCH, state.provider);
        synchronized (shareTickets) { shareTickets.put(key, new ShareTicket(token)); }
        return token;
    }

    private JsonObject api(CloudProvider provider, String path, Map<String, String> query, JsonObject post, Cancellation cancellation) throws CloudException {
        return api(provider, path, query, post, null, cancellation);
    }

    private JsonObject api(CloudProvider provider, String path, Map<String, String> query, JsonObject post, CloudSessionManager.Snapshot expected, Cancellation cancellation) throws CloudException {
        synchronized (locks.get(provider)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                CloudSessionManager.Snapshot state = sessions.require(provider);
                if (expected != null && (state.provider != expected.provider || state.authEpoch != expected.authEpoch || !state.accountId.equals(expected.accountId)))
                    throw new CloudException(CloudException.Code.CANCELLED, provider);
                HttpUrl.Builder url = HttpUrl.parse(provider.apiBase + path).newBuilder().addQueryParameter("pr", provider.product).addQueryParameter("fr", "pc");
                for (Map.Entry<String, String> item : query.entrySet()) url.addQueryParameter(item.getKey(), item.getValue());
                CloudHttpTransport.Result response = transport.request(provider, url.build(), post, state.credential(), cancellation);
                cancellation.check();
                CloudSessionManager.Snapshot current = sessions.snapshot(provider);
                if (current.authEpoch != state.authEpoch || !current.accountId.equals(state.accountId)) throw new CloudException(CloudException.Code.CANCELLED, provider);
                CloudException.Code error = CloudErrors.classify(response.status, response.json);
                if (error == CloudException.Code.SESSION_EXPIRED) {
                    if (sessions.expireIfCurrent(state)) throw new CloudException(error, provider);
                    if (attempt == 0) continue;
                }
                if (error != null) throw new CloudException(error, provider);
                sessions.rotate(state, CookieValues.merge(state.credential(), response.cookies));
                return response.json;
            }
        }
        throw new CloudException(CloudException.Code.SESSION_EXPIRED, provider);
    }

    static void pause(Cancellation cancellation, long millis) throws CloudException {
        long end = System.nanoTime() + millis * 1000000L;
        while (System.nanoTime() < end) {
            cancellation.check();
            try { Thread.sleep(Math.min(100, Math.max(1, (end - System.nanoTime()) / 1000000L))); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); cancellation.check(); }
        }
    }

    static String digest(String value) throws CloudException {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(Charset.forName("UTF-8")));
            StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (Exception ignored) { throw new CloudException(CloudException.Code.INVALID_RESPONSE, null); }
    }

    static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,128}"); }
    static JsonObject object(JsonObject parent, String key) { return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject(); }
    static JsonArray array(JsonObject parent, String key) { return parent != null && parent.has(key) && parent.get(key).isJsonArray() ? parent.getAsJsonArray(key) : new JsonArray(); }
    static String text(JsonObject parent, String key) { try { return parent != null && parent.has(key) && !parent.get(key).isJsonNull() && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsString() : ""; } catch (RuntimeException ignored) { return ""; } }
    static boolean bool(JsonObject parent, String key) { try { return parent.has(key) && parent.get(key).getAsBoolean(); } catch (RuntimeException ignored) { return false; } }
    static long number(JsonObject parent, String key) { try { return parent.has(key) ? parent.get(key).getAsLong() : 0; } catch (RuntimeException ignored) { return 0; } }
    private static JsonObject json(String... fields) { JsonObject object = new JsonObject(); for (int i = 0; i < fields.length; i += 2) object.addProperty(fields[i], fields[i + 1]); return object; }
    private static Map<String, String> params(String... fields) { Map<String, String> map = new LinkedHashMap<>(); for (int i = 0; i < fields.length; i += 2) map.put(fields[i], fields[i + 1]); return map; }
    private static String identity(JsonObject data) {
        for (String key : new String[]{"uid", "user_id", "userid", "member_id", "account_id"}) if (!text(data, key).isEmpty()) return text(data, key);
        for (String key : new String[]{"user", "user_info", "members"}) {
            JsonObject child = object(data, key);
            for (String name : new String[]{"uid", "user_id", "userid", "member_id", "account_id"}) if (!text(child, name).isEmpty()) return text(child, name);
        }
        return "";
    }
}
