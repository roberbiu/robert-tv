package com.github.tvbox.osc.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.CookieJar;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Timeout;
import static org.junit.Assert.*;

public class AliDnsHttpsResolverTest {
    private static final String NAME = "origin.example.org";
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(2); }

    @Test public void observedOfficialObjectQuestionAndSingleArrayBothValidateCnameChain() throws Exception {
        JsonObject root = response(NAME, 1, "8.8.8.8");
        JsonArray answers = new JsonArray();
        answers.add(answer(NAME + ".", 5, "alias.example.org."));
        answers.add(answer("alias.example.org.", 5, "edge.example.org."));
        answers.add(answer("edge.example.org.", 1, "8.8.8.8"));
        root.add("Answer", answers);
        assertEquals("8.8.8.8", AliDnsHttpsResolver.parse(bytes(root), NAME, 1).get(0).getHostAddress());
        JsonArray questionArray = new JsonArray(); questionArray.add(root.get("Question")); root.add("Question", questionArray);
        assertEquals(1, AliDnsHttpsResolver.parse(bytes(root), NAME, 1).size());
    }

    @Test public void malformedQuestionStatusTypeAndUnrelatedAnswerFailClosed() {
        for (String mutation : new String[]{"question", "type", "status", "truncated", "host-as-ip", "unrelated", "cycle"}) {
            JsonObject root = response(NAME, 1, "8.8.8.8");
            if (mutation.equals("question")) root.getAsJsonObject("Question").addProperty("name", "other.example.org");
            if (mutation.equals("type")) root.getAsJsonObject("Question").addProperty("type", 28);
            if (mutation.equals("status")) root.addProperty("Status", 3);
            if (mutation.equals("truncated")) root.addProperty("TC", true);
            if (mutation.equals("host-as-ip")) root.getAsJsonArray("Answer").get(0).getAsJsonObject().addProperty("data", "never-query.example.org");
            if (mutation.equals("unrelated")) root.getAsJsonArray("Answer").get(0).getAsJsonObject().addProperty("name", "other.example.org");
            if (mutation.equals("cycle")) { JsonArray rows = new JsonArray(); rows.add(answer(NAME, 5, NAME)); root.add("Answer", rows); }
            try { AliDnsHttpsResolver.parse(bytes(root), NAME, 1); fail(mutation); } catch (UnknownHostException expected) { }
        }
    }

    @Test public void fallbackAnswersCannotContainPrivateFakeOrMixedAddressSet() {
        for (String value : new String[]{"198.18.0.1", "192.168.1.1", "203.0.113.1"}) {
            JsonObject root = response(NAME, 1, value);
            root.getAsJsonArray("Answer").add(answer(NAME, 1, "8.8.8.8"));
            try { AliDnsHttpsResolver.parse(bytes(root), NAME, 1); fail(value); } catch (UnknownHostException expected) { }
        }
    }

    @Test public void transportHasFixedEndpointAndOnlyHostnameTypeParametersWithoutCredentials() throws Exception {
        AtomicReference<Request> sent = new AtomicReference<>(); AtomicInteger count = new AtomicInteger();
        AliDnsHttpsResolver resolver = new AliDnsHttpsResolver((request, remaining) -> {
            sent.set(request); count.incrementAndGet();
            assertTrue(remaining > 0 && remaining <= 2000);
            return new FakeCall(request, response(NAME, 1, "8.8.8.8").toString(), 200);
        });
        assertEquals(1, resolver.lookup(NAME, deadline(), new RecoveringDns.Cancellation()).size());
        Request request = sent.get();
        assertEquals("https", request.url().scheme()); assertEquals("dns.alidns.com", request.url().host());
        assertEquals("/resolve", request.url().encodedPath());
        assertEquals(NAME, request.url().queryParameter("name")); assertEquals("A", request.url().queryParameter("type"));
        assertEquals(2, request.url().querySize()); assertNull(request.header("Cookie")); assertNull(request.header("Authorization"));
        assertEquals(1, count.get());
    }

    @Test public void noDataCanTryIpv6WithinSameAbsoluteDeadline() throws Exception {
        AtomicInteger count = new AtomicInteger();
        AliDnsHttpsResolver resolver = new AliDnsHttpsResolver((request, remaining) -> {
            count.incrementAndGet();
            boolean ipv6 = "AAAA".equals(request.url().queryParameter("type"));
            JsonObject root = response(NAME, ipv6 ? 28 : 1, ipv6 ? "2606:4700:4700::1111" : "8.8.8.8");
            if (!ipv6) root.remove("Answer");
            return new FakeCall(request, root.toString(), 200);
        });
        List<InetAddress> addresses = resolver.lookup(NAME, deadline(), new RecoveringDns.Cancellation());
        assertEquals(16, addresses.get(0).getAddress().length); assertEquals(2, count.get());
    }

    @Test public void bootstrapIsFixedAndClientDoesNotInheritProxyCookiesOrRedirects() throws Exception {
        OkHttpClient client = AliDnsHttpsResolver.client(1200);
        assertEquals(1200, client.callTimeoutMillis()); assertEquals(Proxy.NO_PROXY, client.proxy());
        assertSame(CookieJar.NO_COOKIES, client.cookieJar()); assertSame(Authenticator.NONE, client.authenticator());
        assertFalse(client.followRedirects()); assertFalse(client.followSslRedirects()); assertFalse(client.retryOnConnectionFailure());
        assertEquals("223.5.5.5", client.dns().lookup("dns.alidns.com").get(0).getHostAddress());
        assertEquals("223.6.6.6", client.dns().lookup("dns.alidns.com").get(1).getHostAddress());
        try { client.dns().lookup(NAME); fail(); } catch (UnknownHostException expected) { }
    }

    @Test public void redirectLargeBodyExpiredBudgetAndCancelledRequestNeverEscapeBoundary() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AliDnsHttpsResolver resolver = new AliDnsHttpsResolver((request, remaining) -> { calls.incrementAndGet(); return new FakeCall(request, "{}", 302); });
        try { resolver.lookup(NAME, deadline(), new RecoveringDns.Cancellation()); fail(); } catch (UnknownHostException expected) { assertEquals(1, calls.get()); }
        RecoveringDns.Cancellation cancelled = new RecoveringDns.Cancellation(); cancelled.cancel();
        try { resolver.lookup(NAME, deadline(), cancelled); fail(); } catch (UnknownHostException expected) { assertEquals(1, calls.get()); }
        try { resolver.lookup(NAME, System.nanoTime() - 1, new RecoveringDns.Cancellation()); fail(); } catch (UnknownHostException expected) { assertEquals(1, calls.get()); }
        try { AliDnsHttpsResolver.readBounded(new ByteArrayInputStream(new byte[AliDnsHttpsResolver.MAX_BYTES + 1]), deadline(), new RecoveringDns.Cancellation()); fail(); }
        catch (UnknownHostException expected) { }
    }

    @Test public void cancellationClosesAnAlreadyCreatedHttpsCall() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), closed = new CountDownLatch(1), finished = new CountDownLatch(1);
        AliDnsHttpsResolver resolver = new AliDnsHttpsResolver((request, remaining) -> new FakeCall(request, "{}", 200) {
            @Override public Response execute() throws IOException {
                entered.countDown();
                try { closed.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                throw new IOException("synthetic cancelled");
            }
            @Override public void cancel() { closed.countDown(); }
        });
        RecoveringDns.Cancellation cancellation = new RecoveringDns.Cancellation();
        Thread caller = new Thread(() -> { try { resolver.lookup(NAME, deadline(), cancellation); } catch (IOException expected) { } finally { finished.countDown(); } });
        caller.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); cancellation.cancel(); assertTrue(finished.await(2, TimeUnit.SECONDS));
    }

    private static JsonObject response(String host, int type, String address) {
        JsonObject root = new JsonObject(); root.addProperty("Status", 0); root.addProperty("TC", false);
        JsonObject question = new JsonObject(); question.addProperty("name", host + "."); question.addProperty("type", type); root.add("Question", question);
        JsonArray answers = new JsonArray(); answers.add(answer(host + ".", type, address)); root.add("Answer", answers); return root;
    }
    private static JsonObject answer(String name, int type, String data) {
        JsonObject item = new JsonObject(); item.addProperty("name", name); item.addProperty("type", type); item.addProperty("data", data); item.addProperty("TTL", 60); return item;
    }
    private static byte[] bytes(JsonObject json) { return json.toString().getBytes(StandardCharsets.UTF_8); }
    private static class FakeCall implements Call {
        final Request request; final String body; final int status; boolean cancelled;
        FakeCall(Request request, String body, int status) { this.request = request; this.body = body; this.status = status; }
        @Override public Request request() { return request; }
        @Override public Response execute() throws IOException { return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
                .body(ResponseBody.create(MediaType.parse("application/json"), body)).build(); }
        @Override public void enqueue(Callback callback) { throw new AssertionError("不建立网络连接"); }
        @Override public void cancel() { cancelled = true; }
        @Override public boolean isExecuted() { return false; }
        @Override public boolean isCanceled() { return cancelled; }
        @Override public Timeout timeout() { return Timeout.NONE; }
        @Override public Call clone() { return new FakeCall(request, body, status); }
    }
}
