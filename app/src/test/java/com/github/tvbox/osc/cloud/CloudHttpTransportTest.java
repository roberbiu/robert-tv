package com.github.tvbox.osc.cloud;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import static org.junit.Assert.*;

/** 应用拦截器直接提供可控响应体，没有真实DNS、账号或网络连接。 */
public class CloudHttpTransportTest {
    @Test public void slowDripCannotOutliveRemainingControlBudget() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient fake = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet(); byte[] json = "{\"code\":0,\"data\":{\"value\":\"synthetic slow response\"}}".getBytes("UTF-8");
            ResponseBody body = new ResponseBody() {
                final BufferedSource source = Okio.buffer(new Source() {
                    int index;
                    @Override public long read(Buffer sink, long byteCount) throws IOException {
                        try { Thread.sleep(10); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); throw new IOException("fixture interrupted"); }
                        if (chain.call().isCanceled()) throw new IOException("fixture cancelled");
                        if (index == json.length) return -1; sink.writeByte(json[index++]); return 1;
                    }
                    @Override public Timeout timeout() { return Timeout.NONE; }
                    @Override public void close() { }
                });
                @Override public MediaType contentType() { return MediaType.parse("application/json"); }
                @Override public long contentLength() { return -1; }
                @Override public BufferedSource source() { return source; }
            };
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build();
        }).build();
        long start = System.nanoTime();
        try { new CloudHttpTransport(fake).request(CloudProvider.QUARK, HttpUrl.parse(CloudProvider.QUARK.apiBase + "fixture"), null, "__pus=fake", new Cancellation(), 80); fail(); }
        catch (CloudException expected) { assertEquals(CloudException.Code.NETWORK, expected.code); }
        assertEquals(1, requests.get()); assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500);
    }
    @Test public void officialCredentialRequestNeverFollowsCrossHostRedirect() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient fake = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet(); assertEquals("drive-pc.quark.cn", chain.request().url().host()); assertEquals("__pus=fake", chain.request().header("Cookie"));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("fixture redirect")
                    .header("Location", "https://evil.example/receive").body(ResponseBody.create(MediaType.parse("application/json"), "{}")).build();
        }).build();
        CloudHttpTransport.Result response = new CloudHttpTransport(fake).request(CloudProvider.QUARK, HttpUrl.parse(CloudProvider.QUARK.apiBase + "fixture"), null, "__pus=fake", new Cancellation());
        assertEquals(302, response.status); assertEquals(1, requests.get());
        Cancellation cancelled = new Cancellation(); cancelled.cancel();
        try { new CloudHttpTransport(fake).request(CloudProvider.QUARK, HttpUrl.parse(CloudProvider.QUARK.apiBase + "fixture"), null, "__pus=fake", cancelled); fail(); }
        catch (CloudException expected) { assertEquals(CloudException.Code.CANCELLED, expected.code); }
        assertEquals(1, requests.get());
    }
}
