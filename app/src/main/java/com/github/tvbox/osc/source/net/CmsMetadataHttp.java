package com.github.tvbox.osc.source.net;

import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.source.PublicNetworkPolicy;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.cache.CacheMode;
import com.lzy.okgo.request.GetRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Headers;
import okhttp3.EventListener;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** 传统CMS的匿名元数据专用边界；不改变媒体、本机租约或手机输入客户端。 */
public final class CmsMetadataHttp {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static volatile OkHttpClient client;
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "cms-metadata-deadline");
        thread.setDaemon(true);
        return thread;
    });

    private CmsMetadataHttp() { }

    public static GetRequest<String> get(SourceBean source) {
        return get(source, source.getPlayTimeoutSeconds());
    }

    public static GetRequest<String> get(SourceBean source, int timeoutSeconds) {
        GetRequest<String> request = OkGo.<String>get(source.getApi());
        if (source.getType() != 0 && source.getType() != 1) return request;
        int seconds = Math.max(5, Math.min(timeoutSeconds, 30));
        return request.client(base().newBuilder().callTimeout(seconds, TimeUnit.SECONDS)
                .eventListenerFactory(call -> new CallDeadline(TimeUnit.SECONDS.toMillis(seconds))).build())
                .retryCount(0).cacheMode(CacheMode.NO_CACHE);
    }

    private static OkHttpClient base() {
        if (client == null) synchronized (CmsMetadataHttp.class) {
            if (client == null) {
                // 从全新Builder开始，避免继承旧全局Cookie、TLS、日志、hosts和代理规则。
                OkHttpClient.Builder builder = new OkHttpClient.Builder()
                        .dns(PublicNetworkPolicy.publicDns(Dns.SYSTEM)).proxy(Proxy.NO_PROXY)
                        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
                        .proxyAuthenticator(Authenticator.NONE).followRedirects(false).followSslRedirects(false)
                        .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(8, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS)
                        .addInterceptor(CmsMetadataHttp::intercept);
                // OkGo.cancelTag只遍历全局dispatcher；共享调度器不继承其网络和鉴权配置。
                OkHttpClient global = OkGo.getInstance().getOkHttpClient();
                if (global != null) builder.dispatcher(global.dispatcher());
                client = builder.build();
            }
        }
        return client;
    }

    static Response intercept(Interceptor.Chain chain) throws IOException {
        Request current = anonymous(chain.request());
        URI origin = validate(current.url().toString());
        for (int hop = 0; hop <= 2; hop++) {
            validate(current.url().toString());
            Response response = chain.proceed(current);
            if (response.code() >= 300 && response.code() < 400) {
                String location = response.header("Location");
                HttpUrl next = location == null ? null : current.url().resolve(location);
                response.close();
                if (hop == 2 || next == null) throw new IOException("元数据重定向无效");
                URI checked = validate(next.toString());
                if (!origin.getHost().equalsIgnoreCase(checked.getHost())
                        || ("https".equalsIgnoreCase(origin.getScheme()) && !"https".equalsIgnoreCase(checked.getScheme()))) {
                    throw new IOException("元数据重定向超出来源范围");
                }
                current = anonymous(current.newBuilder().url(next).build());
                continue;
            }
            // 在call总期限内完整读取有界响应，慢滴流不会刷新总预算。
            try {
                ResponseBody body = response.body();
                if (body == null || body.contentLength() > MAX_BYTES) throw new IOException("元数据响应无效");
                MediaType type = body.contentType();
                byte[] content = readBounded(body.byteStream());
                return response.newBuilder().body(ResponseBody.create(type, content)).build();
            } finally { response.close(); }
        }
        throw new IOException("元数据重定向次数过多");
    }

    /** callStart早于排队，覆盖队列等待；禁止OkGo重试重置这次请求的总预算。 */
    static final class CallDeadline extends EventListener {
        private final long timeoutMillis;
        private ScheduledFuture<?> timer;
        CallDeadline(long timeoutMillis) { this.timeoutMillis = timeoutMillis; }
        @Override public void callStart(Call call) { timer = DEADLINES.schedule(call::cancel, timeoutMillis, TimeUnit.MILLISECONDS); }
        @Override public void callEnd(Call call) { finish(); }
        @Override public void callFailed(Call call, IOException error) { finish(); }
        private void finish() { if (timer != null) timer.cancel(false); }
    }

    static URI validate(String address) throws IOException {
        try { return PublicNetworkPolicy.requirePublicUrl(address, true); }
        catch (IllegalArgumentException denied) { throw new IOException("元数据地址未准入"); }
    }

    private static Request anonymous(Request request) {
        Headers headers = new Headers.Builder()
                .add("Accept", "application/json,application/xml,text/xml;q=0.9")
                .add("User-Agent", "Mozilla/5.0 (Android TV; LiuLiuFuFuTV/1.0)").build();
        return request.newBuilder().headers(headers).build();
    }

    static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int read;
        while ((read = input.read(bytes)) != -1) {
            if (output.size() + read > MAX_BYTES) throw new IOException("元数据响应过大");
            output.write(bytes, 0, read);
        }
        return output.toByteArray();
    }
}
