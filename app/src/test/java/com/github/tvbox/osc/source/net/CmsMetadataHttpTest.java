package com.github.tvbox.osc.source.net;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;
import okio.Timeout;
import static org.junit.Assert.*;

/** 通过合成Chain验证实际请求边界，不建立公网或本地套接字。 */
public class CmsMetadataHttpTest {
    @Test public void anonymousRequestCannotInheritGlobalAccountHeaders() throws Exception {
        Request request = new Request.Builder().url("https://metadata.example.org/api.php/provide/vod/")
                .header("Cookie", "private").header("Authorization", "Bearer private")
                .header("Proxy-Authorization", "private").header("X-Account-Secret", "private").build();
        FakeChain chain = new FakeChain(request, null, "{\"ok\":true}".getBytes("UTF-8"));
        try (Response response = CmsMetadataHttp.intercept(chain)) {
            assertEquals("{\"ok\":true}", response.body().string());
        }
        assertEquals(1, chain.sent.size());
        assertEquals(2, chain.sent.get(0).headers().size());
        assertNull(chain.sent.get(0).header("Cookie"));
        assertNull(chain.sent.get(0).header("Authorization"));
        assertNull(chain.sent.get(0).header("X-Account-Secret"));
    }

    @Test public void onlySameHostRedirectWithoutHttpsDowngradeIsFollowed() throws Exception {
        FakeChain chain = new FakeChain(request("https://metadata.example.org/start"), "/next", "done".getBytes("UTF-8"));
        try (Response response = CmsMetadataHttp.intercept(chain)) { assertEquals("done", response.body().string()); }
        assertEquals(2, chain.sent.size());
        assertEquals("/next", chain.sent.get(1).url().encodedPath());
        for (String location : new String[]{"https://127.0.0.1/private", "https://other.example.org/", "http://metadata.example.org/plain"}) {
            FakeChain denied = new FakeChain(request("https://metadata.example.org/start"), location, new byte[0]);
            try { CmsMetadataHttp.intercept(denied); fail(location); }
            catch (IOException expected) { assertEquals(1, denied.sent.size()); }
        }
    }

    @Test public void redirectLoopHasFixedBudget() throws Exception {
        FakeChain chain = new FakeChain(request("https://metadata.example.org/start"), "/loop", new byte[0]);
        chain.alwaysRedirect = true;
        try { CmsMetadataHttp.intercept(chain); fail("不能无限跳转"); }
        catch (IOException expected) { assertEquals(3, chain.sent.size()); }
    }

    @Test public void unknownLengthBodyCannotExceedMetadataLimit() throws Exception {
        byte[] oversized = new byte[CmsMetadataHttp.MAX_BYTES + 1];
        FakeChain chain = new FakeChain(request("https://metadata.example.org/list"), null, oversized);
        try { CmsMetadataHttp.intercept(chain); fail("分块响应也必须受大小边界限制"); }
        catch (IOException expected) { assertEquals("元数据响应过大", expected.getMessage()); }
    }

    @Test public void deadlineCancelsEvenCallNotDispatchedToNetwork() throws Exception {
        FakeCall waiting = new FakeCall();
        CmsMetadataHttp.CallDeadline deadline = new CmsMetadataHttp.CallDeadline(30);
        deadline.callStart(waiting);
        assertTrue(waiting.cancelled.await(2, TimeUnit.SECONDS));
        deadline.callFailed(waiting, new IOException("cancelled"));
        assertTrue(waiting.isCanceled());
    }

    private static Request request(String address) { return new Request.Builder().url(address).build(); }

    private static final class FakeChain implements Interceptor.Chain {
        final Request first;
        final String redirect;
        final byte[] content;
        final List<Request> sent = new ArrayList<>();
        boolean alwaysRedirect;
        FakeChain(Request first, String redirect, byte[] content) { this.first = first; this.redirect = redirect; this.content = content; }
        @Override public Request request() { return first; }
        @Override public Response proceed(Request request) {
            sent.add(request);
            Response.Builder response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("synthetic");
            if (redirect != null && (sent.size() == 1 || alwaysRedirect)) {
                return response.code(302).header("Location", redirect).body(ResponseBody.create(null, new byte[0])).build();
            }
            ResponseBody body = new ResponseBody() {
                final BufferedSource input = Okio.buffer(Okio.source(new ByteArrayInputStream(content)));
                @Override public MediaType contentType() { return MediaType.parse("application/json;charset=utf-8"); }
                @Override public long contentLength() { return -1; }
                @Override public BufferedSource source() { return input; }
            };
            return response.code(200).body(body).build();
        }
        @Override public Connection connection() { return null; }
        @Override public Call call() { return null; }
        @Override public int connectTimeoutMillis() { return 1000; }
        @Override public Interceptor.Chain withConnectTimeout(int value, TimeUnit unit) { return this; }
        @Override public int readTimeoutMillis() { return 1000; }
        @Override public Interceptor.Chain withReadTimeout(int value, TimeUnit unit) { return this; }
        @Override public int writeTimeoutMillis() { return 1000; }
        @Override public Interceptor.Chain withWriteTimeout(int value, TimeUnit unit) { return this; }
    }

    private static final class FakeCall implements Call {
        final CountDownLatch cancelled = new CountDownLatch(1);
        @Override public Request request() { return CmsMetadataHttpTest.request("https://metadata.example.org/"); }
        @Override public Response execute() { throw new AssertionError("不应请求网络"); }
        @Override public void enqueue(Callback callback) { throw new AssertionError("不应请求网络"); }
        @Override public void cancel() { cancelled.countDown(); }
        @Override public boolean isExecuted() { return false; }
        @Override public boolean isCanceled() { return cancelled.getCount() == 0; }
        @Override public Timeout timeout() { return Timeout.NONE; }
        @Override public Call clone() { return new FakeCall(); }
    }
}
