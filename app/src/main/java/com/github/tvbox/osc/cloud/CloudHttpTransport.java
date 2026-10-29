package com.github.tvbox.osc.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 网盘专用客户端不共享网页/热榜Cookie，不自动跟随携带凭据的重定向。 */
final class CloudHttpTransport {
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/100.0.4896.160 Safari/537.36";
    private final OkHttpClient client;
    CloudHttpTransport() { this(new OkHttpClient.Builder().dns(NetworkPolicy.publicDns()).build()); }
    CloudHttpTransport(OkHttpClient client) {
        this.client = client.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    }

    static final class Result {
        final int status;
        final JsonObject json;
        final List<String> cookies;
        Result(int status, JsonObject json, List<String> cookies) { this.status = status; this.json = json; this.cookies = cookies; }
    }

    Result request(CloudProvider provider, HttpUrl target, JsonObject post, String cookie, Cancellation cancellation) throws CloudException {
        return request(provider, target, post, cookie, cancellation, 20000);
    }
    Result request(CloudProvider provider, HttpUrl target, JsonObject post, String cookie, Cancellation cancellation, long remainingMillis) throws CloudException {
        return execute(provider, target, post == null ? null : RequestBody.create(MediaType.parse("application/json; charset=utf-8"), post.toString()), cookie, cancellation, remainingMillis);
    }

    Result form(CloudProvider provider, HttpUrl target, java.util.Map<String, String> values, String cookie, Cancellation cancellation) throws CloudException {
        return form(provider, target, values, cookie, cancellation, 20000);
    }
    Result form(CloudProvider provider, HttpUrl target, java.util.Map<String, String> values, String cookie, Cancellation cancellation, long remainingMillis) throws CloudException {
        FormBody.Builder body = new FormBody.Builder();
        for (java.util.Map.Entry<String, String> value : values.entrySet()) body.add(value.getKey(), value.getValue());
        return execute(provider, target, body.build(), cookie, cancellation, remainingMillis);
    }

    private Result execute(CloudProvider provider, HttpUrl target, RequestBody body, String cookie, Cancellation cancellation, long remainingMillis) throws CloudException {
        cancellation.check();
        if (remainingMillis <= 0) throw new CloudException(CloudException.Code.NETWORK, provider);
        Set<String> allowed = new HashSet<>(Arrays.asList(HttpUrl.parse(provider.apiBase).host(),
                HttpUrl.parse(provider.loginBase).host(), HttpUrl.parse(provider.webBase).host()));
        NetworkPolicy.requireUrl(target.toString(), allowed, true);
        Request.Builder request = new Request.Builder().url(target).header("User-Agent", USER_AGENT)
                .header("Referer", provider.webBase).header("Accept", "application/json");
        if (cookie != null && !cookie.isEmpty()) request.header("Cookie", cookie);
        if (body != null) request.post(body);
        Call call = client.newCall(request.build());
        call.timeout().timeout(Math.min(20000L, remainingMillis), TimeUnit.MILLISECONDS);
        try (AutoCloseable ignored = cancellation.attach(call::cancel); Response response = call.execute()) {
            cancellation.check();
            if (response.body() == null) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = response.body().byteStream()) {
                byte[] bytes = new byte[4096];
                int size;
                while ((size = input.read(bytes)) != -1) {
                    cancellation.check();
                    if (buffer.size() + size > 1048576) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
                    buffer.write(bytes, 0, size);
                }
            }
            JsonObject data;
            try { data = JsonParser.parseString(new String(buffer.toByteArray(), Charset.forName("UTF-8"))).getAsJsonObject(); }
            catch (RuntimeException malformed) {
                if (response.isSuccessful()) throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
                data = new JsonObject();
            }
            return new Result(response.code(), data, response.headers("Set-Cookie"));
        } catch (CloudException known) { throw known; }
        catch (Exception ignored) { cancellation.check(); throw new CloudException(CloudException.Code.NETWORK, provider); }
    }
}
