package com.github.tvbox.osc.trending;

import com.github.tvbox.osc.source.PublicNetworkPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 匿名元数据边界；不继承账号Cookie、鉴权器或请求日志拦截器。 */
public final class PublicMetadataHttp {
    public interface Cancellation {
        boolean isCancelled();
    }

    public static final int MAX_BYTES = 1024 * 1024;
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor();
    private final Set<Call> active = Collections.newSetFromMap(new ConcurrentHashMap<Call, Boolean>());
    private final OkHttpClient client;

    public PublicMetadataHttp() {
        // 全新客户端使用系统TLS，不继承旧全局Client的自定义hosts、代理或TLS配置。
        client = new OkHttpClient.Builder().dns(PublicNetworkPolicy.publicDns()).proxy(Proxy.NO_PROXY)
                .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE).followRedirects(false).followSslRedirects(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    }

    public String get(String url, Cancellation cancellation, long timeoutMillis) throws IOException {
        URI origin;
        try {
            origin = URI.create(url);
        } catch (IllegalArgumentException error) {
            throw new IOException("元数据地址无效");
        }
        validate(origin);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(100, Math.min(timeoutMillis, 15000)));
        URI current = origin;
        for (int redirects = 0; redirects < 3; redirects++) {
            if (cancellation.isCancelled()) throw new IOException("请求已取消");
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) throw new IOException("元数据请求超时");
            Call call = MetadataCallDeadline.create(client, new Request.Builder().url(current.toString())
                    .header("User-Agent", "Mozilla/5.0 (Android TV; LiuLiuFuFuTV/1.0)")
                    .header("Accept", "application/json,text/html;q=0.9").build(), remaining);
            active.add(call);
            if (cancellation.isCancelled()) call.cancel();
            ScheduledFuture<?> timeout = TIMEOUTS.schedule(call::cancel, remaining, TimeUnit.MILLISECONDS);
            try (Response response = call.execute()) {
                if (response.code() >= 300 && response.code() < 400) {
                    String location = response.header("Location");
                    if (location == null) throw new IOException("元数据重定向无效");
                    URI next = current.resolve(location);
                    validate(next);
                    if (!origin.getHost().equalsIgnoreCase(next.getHost())
                            || ("https".equalsIgnoreCase(origin.getScheme()) && !"https".equalsIgnoreCase(next.getScheme()))) {
                        throw new IOException("元数据重定向超出来源范围");
                    }
                    current = next;
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) throw new IOException("元数据来源暂不可用");
                long length = response.body().contentLength();
                if (length > MAX_BYTES) throw new IOException("元数据响应过大");
                try (InputStream input = response.body().byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] bytes = new byte[8192];
                    int count;
                    while ((count = input.read(bytes)) != -1) {
                        if (cancellation.isCancelled()) throw new IOException("请求已取消");
                        if (output.size() + count > MAX_BYTES) throw new IOException("元数据响应过大");
                        output.write(bytes, 0, count);
                    }
                    return new String(output.toByteArray(), "UTF-8");
                }
            } finally {
                timeout.cancel(false);
                active.remove(call);
            }
        }
        throw new IOException("元数据重定向次数过多");
    }

    private static void validate(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if ((!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) {
            throw new IOException("元数据地址无效");
        }
        try {
            PublicNetworkPolicy.requirePublicUrl(uri.toString(), true);
        } catch (IllegalArgumentException rejected) {
            throw new IOException("元数据地址未准入");
        }
    }

    public void cancelAll() {
        for (Call call : active) call.cancel();
    }
}
