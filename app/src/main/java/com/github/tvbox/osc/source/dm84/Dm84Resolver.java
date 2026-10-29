package com.github.tvbox.osc.source.dm84;

import com.github.tvbox.osc.cloud.Cancellation;
import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.NetworkPolicy;
import com.github.tvbox.osc.source.wogg.SiteHttp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 固定站点协议的有界解析；仅把JSON作为数据，不执行页面JS或任意解析服务。 */
public final class Dm84Resolver {
    static final String PLAYER_HOST = "hhjx.hhplayer.com";
    private static final Set<String> PLAYER_HOSTS = Collections.singleton(PLAYER_HOST);
    private static final Pattern BOOTSTRAP = Pattern.compile("window\\.__HHJX_BOOTSTRAP__\\s*=\\s*(\\{[^\\n]{1,16384}?\\});");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().dns(NetworkPolicy.publicDns()).proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build();
    public static final class Media {
        final transient String url; public final boolean hls;
        Media(String url, boolean hls) { this.url = url; this.hls = hls; }
        @Override public String toString() { return "Dm84Media(redacted)"; }
    }
    public Media resolve(String html, String pageUrl, Cancellation cancellation) throws CloudException {
        for (Element element : Jsoup.parse(html, pageUrl).select("video[src],video source[src]")) {
            Media direct = direct(element.absUrl("src"), ""); if (direct != null) return direct;
        }
        Element iframe = Jsoup.parse(html, pageUrl).selectFirst("iframe[src]");
        if (iframe == null) throw new CloudException(CloudException.Code.UNSUPPORTED, null);
        HttpUrl frame = NetworkPolicy.requireUrl(iframe.absUrl("src"), PLAYER_HOSTS, true);
        if (!"/".equals(frame.encodedPath())) throw new CloudException(CloudException.Code.UNSUPPORTED, null);
        String frameHtml = SiteHttp.get(frame.toString(), PLAYER_HOSTS, cancellation);
        JsonObject request = parseBootstrap(frameHtml);
        HttpUrl endpoint = frame.newBuilder().encodedPath("/api/parse").query(null).build();
        Call call = CLIENT.newCall(new Request.Builder().url(endpoint).header("User-Agent", "Mozilla/5.0")
                .header("Referer", frame.toString()).post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), request.toString())).build());
        try (AutoCloseable ignored = cancellation.attach(call::cancel); Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new CloudException(CloudException.Code.NETWORK, null);
            ByteArrayOutputStream body = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int count;
            try (InputStream input = response.body().byteStream()) {
                while ((count = input.read(bytes)) != -1) { cancellation.check(); if (body.size() + count > 65536) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null); body.write(bytes, 0, count); }
            }
            JsonObject parsed = JsonParser.parseString(new String(body.toByteArray(), Charset.forName("UTF-8"))).getAsJsonObject();
            if (!parsed.has("code") || parsed.get("code").getAsInt() != 200) throw new CloudException(CloudException.Code.UNSUPPORTED, null);
            Media media = direct(text(parsed, "url"), text(parsed, "ext"));
            if (media == null) throw new CloudException(CloudException.Code.UNSUPPORTED, null);
            return media;
        } catch (CloudException known) { throw known; }
        catch (Exception ignored) { cancellation.check(); throw new CloudException(CloudException.Code.NETWORK, null); }
    }
    static JsonObject parseBootstrap(String html) throws CloudException {
        try {
            Matcher match = BOOTSTRAP.matcher(html); if (!match.find()) throw new IllegalArgumentException();
            JsonObject value = JsonParser.parseString(match.group(1)).getAsJsonObject();
            String encrypted = text(value, "url"), key = text(value, "key");
            if (!encrypted.matches("[A-Fa-f0-9]{16,8192}") || !key.matches("[A-Fa-f0-9]{16,256}") || !value.has("t")) throw new IllegalArgumentException();
            long timestamp = value.get("t").getAsLong(); if (timestamp <= 0) throw new IllegalArgumentException();
            JsonObject request = new JsonObject(); request.addProperty("url", encrypted); request.addProperty("t", timestamp); request.addProperty("key", key); request.addProperty("client_fallback", false);
            if (value.has("act") && value.get("act").getAsInt() == 99) request.addProperty("act", 99);
            return request;
        } catch (Exception ignored) { throw new CloudException(CloudException.Code.UNSUPPORTED, null); }
    }
    static Media direct(String value, String ext) throws CloudException {
        if (value == null || value.length() > 8192) return null;
        HttpUrl url = NetworkPolicy.requireUrl(value, null, false);
        String path = url.encodedPath().toLowerCase(Locale.ROOT), type = ext.toLowerCase(Locale.ROOT);
        boolean hls = path.endsWith(".m3u8") || "m3u8".equals(type) || "hls".equals(type);
        boolean file = path.matches(".*\\.(?:mp4|mkv|webm|m4v)$") || type.matches("mp4|mkv|webm|m4v");
        return hls || file ? new Media(url.toString(), hls) : null;
    }
    private static String text(JsonObject object, String name) { return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : ""; }
}
