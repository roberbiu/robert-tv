package com.github.tvbox.osc.net;

import org.junit.After;
import org.junit.Test;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Dns;
import static org.junit.Assert.*;

public class RecoveringDnsTest {
    private final ExecutorService system = Executors.newFixedThreadPool(2);
    private final ExecutorService https = Executors.newFixedThreadPool(2);
    @After public void stop() { system.shutdownNow(); https.shutdownNow(); }
    private static List<InetAddress> addresses(String... values) throws UnknownHostException {
        java.util.ArrayList<InetAddress> result = new java.util.ArrayList<>();
        for (String value : values) result.add(PublicAddressPolicy.literal(value));
        return result;
    }
    private RecoveringDns dns(Dns initial, RecoveringDns.Fallback fallback) { return new RecoveringDns(initial, fallback, system, https, 1000, 50); }

    @Test public void systemFakeIpRecoversThroughFixedFallbackAndPublicSystemDoesNotSendQuery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RecoveringDns.Fallback fallback = (host, deadline, cancellation) -> { calls.incrementAndGet(); return addresses("8.8.8.8"); };
        assertEquals("8.8.8.8", dns(host -> addresses("198.18.1.1"), fallback).lookup("www.wogg.live").get(0).getHostAddress());
        assertEquals(1, calls.get());
        assertEquals("1.1.1.1", dns(host -> addresses("1.1.1.1"), fallback).lookup("www.wogg.live").get(0).getHostAddress());
        assertEquals(1, calls.get());
    }

    @Test public void failedOrMixedSystemAnswersCanRecoverButFallbackPrivateStillFailsClosed() throws Exception {
        assertEquals("8.8.8.8", dns(host -> { throw new UnknownHostException(); }, (h, d, c) -> addresses("8.8.8.8")).lookup("dm84.tv").get(0).getHostAddress());
        AtomicInteger fallbackCalls = new AtomicInteger();
        RecoveringDns checked = dns(host -> addresses("8.8.8.8", "198.18.1.1"), (h, d, c) -> {
            fallbackCalls.incrementAndGet(); return addresses("8.8.8.8", "10.0.0.1");
        });
        try { checked.lookup("dm84.tv"); fail(); } catch (UnknownHostException expected) { assertEquals(1, fallbackCalls.get()); }
    }

    @Test public void literalAndLocalNamesNeverReachSystemOrExternalFallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RecoveringDns checked = dns(host -> { calls.incrementAndGet(); return addresses("8.8.8.8"); }, (h, d, c) -> { calls.incrementAndGet(); return addresses("8.8.8.8"); });
        assertEquals("8.8.8.8", checked.lookup("8.8.8.8").get(0).getHostAddress());
        for (String name : Arrays.asList("198.18.1.1", "127.0.0.1", "::1", "::ffff:8.8.8.8", "nas", "a.local", "a.internal", "a.in-addr.arpa", "router.lan")) {
            try { checked.lookup(name); fail(name); } catch (UnknownHostException expected) { }
        }
        assertEquals(0, calls.get());
    }

    @Test public void overallDeadlineIncludesSystemAndFallbackWaitAndCancelsTransport() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1), never = new CountDownLatch(1);
        RecoveringDns checked = new RecoveringDns(host -> {
            try { never.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            throw new UnknownHostException();
        }, (host, deadline, cancellation) -> {
            cancellation.attach(cancelled::countDown);
            try { never.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            throw new UnknownHostException();
        }, system, https, 160, 70);
        long start = System.nanoTime();
        try { checked.lookup("dm84.tv"); fail(); } catch (UnknownHostException expected) { }
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
    }

    @Test public void callerInterruptionCancelsCreatedFallbackAndPreservesInterrupt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), cancelled = new CountDownLatch(1), completed = new CountDownLatch(1), never = new CountDownLatch(1);
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        RecoveringDns checked = dns(host -> addresses("198.18.1.1"), (h, d, c) -> {
            c.attach(cancelled::countDown); entered.countDown();
            try { never.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            throw new UnknownHostException();
        });
        Thread caller = new Thread(() -> {
            try { checked.lookup("dm84.tv"); } catch (UnknownHostException expected) { interrupted.set(Thread.currentThread().isInterrupted()); }
            finally { completed.countDown(); }
        });
        caller.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); caller.interrupt();
        assertTrue(completed.await(2, TimeUnit.SECONDS)); assertTrue(cancelled.await(2, TimeUnit.SECONDS)); assertEquals(Boolean.TRUE, interrupted.get());
    }

    @Test public void stuckSystemLookupCannotCreateUnboundedThreadsOrPreventFallback() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor stuck = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        try {
            RecoveringDns checked = new RecoveringDns(host -> {
                while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
                throw new UnknownHostException();
            }, (h, d, c) -> addresses("8.8.8.8"), stuck, https, 1000, 10);
            for (int i = 0; i < 8; i++) assertEquals("8.8.8.8", checked.lookup("dm84.tv").get(0).getHostAddress());
            assertEquals(1, stuck.getLargestPoolSize()); assertTrue(stuck.getQueue().size() <= 1);
        } finally { release.countDown(); stuck.shutdownNow(); }
    }

    @Test public void rejectedFallbackQueueDoesNotMakeUnexpectedNetworkRequest() throws Exception {
        https.shutdownNow(); AtomicInteger calls = new AtomicInteger();
        try { dns(host -> addresses("198.18.1.1"), (h, d, c) -> { calls.incrementAndGet(); return addresses("8.8.8.8"); }).lookup("dm84.tv"); fail(); }
        catch (UnknownHostException expected) { assertEquals(0, calls.get()); }
    }
}
