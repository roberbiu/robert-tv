package com.github.tvbox.osc.source.wogg;

import com.github.tvbox.osc.cloud.Cancellation;
import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.NetworkPolicy;
import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.source.AdapterRuntime;
import com.github.tvbox.osc.source.RequestContext;
import com.github.tvbox.osc.util.LOG;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 站点匿名客户端，不共享网盘会话、Cookie或网页脚本执行能力。 */
public final class SiteHttp {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().dns(NetworkPolicy.publicDns()).proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(18, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build();
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    private SiteHttp() { }

    public static String get(String value, Set<String> allowedHosts, Cancellation cancellation) throws CloudException {
        HttpUrl target = NetworkPolicy.requireUrl(value, allowedHosts, true);
        for (int redirects = 0; redirects <= 3; redirects++) {
            cancellation.check();
            Call call = CLIENT.newCall(new Request.Builder().url(target).header("User-Agent", UA).header("Accept", "text/html,application/xhtml+xml").build());
            try (AutoCloseable ignored = cancellation.attach(call::cancel); Response response = call.execute()) {
                diagnostic("HTTP", response.code(), null);
                if (response.code() >= 300 && response.code() < 400 && response.header("Location") != null) {
                    HttpUrl next = target.resolve(response.header("Location"));
                    if (next == null) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
                    target = NetworkPolicy.requireUrl(next.toString(), allowedHosts, true);
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) throw new CloudException(CloudException.Code.NETWORK, null);
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                try (InputStream input = response.body().byteStream()) {
                    byte[] bytes = new byte[4096]; int n;
                    while ((n = input.read(bytes)) != -1) {
                        cancellation.check();
                        if (body.size() + n > 1048576) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
                        body.write(bytes, 0, n);
                    }
                }
                return new String(body.toByteArray(), Charset.forName("UTF-8"));
            } catch (CloudException known) { diagnostic(known.code.name(), 0, null); throw known; }
            catch (Exception ignored) { diagnostic("NETWORK", 0, ignored); cancellation.check(); throw new CloudException(CloudException.Code.NETWORK, null); }
        }
        throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
    }

    private static void diagnostic(String category, int status, Exception error) {
        if (!BuildConfig.DEBUG) return;
        RequestContext context = AdapterRuntime.currentContext();
        String source = context == null ? "native" : context.getSourceId();
        if (!("fty_wogg".equals(source) || "fty_dm84".equals(source))) source = "native";
        // 只保留固定来源ID、错误类别、异常类名和HTTP状态，禁止message/stack/URL/响应或凭据。
        LOG.i("native_http source=" + source + " category=" + category + " status=" + status
                + " exception=" + (error == null ? "none" : error.getClass().getSimpleName()));
    }
}
