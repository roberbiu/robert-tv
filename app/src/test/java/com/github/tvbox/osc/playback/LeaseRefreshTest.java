package com.github.tvbox.osc.playback;

import com.github.tvbox.osc.cloud.Cancellation;
import com.github.tvbox.osc.cloud.CloudException;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class LeaseRefreshTest {
    @Test public void lateFailureFromOldRevisionReusesCompletedRefreshInsideCooldown() throws Exception {
        LeaseRefresh refresh = new LeaseRefresh(); Cancellation lifecycle = new Cancellation();
        AtomicInteger revision = new AtomicInteger(), providerCalls = new AtomicInteger();
        // R1/R2均已用rev0发出；R1的401先到并完整刷新至rev1。
        long firstResponseRevision = revision.get(), delayedResponseRevision = revision.get();
        assertTrue(refresh.run(lifecycle, firstResponseRevision, revision::get, () -> { providerCalls.incrementAndGet(); revision.incrementAndGet(); }));
        // 无sleep，R2旧401在同一个5秒冷却窗到达；必须复用，而非503或再请求provider。
        assertFalse(refresh.run(lifecycle, delayedResponseRevision, revision::get, providerCalls::incrementAndGet));
        assertEquals(1, providerCalls.get()); assertEquals(1, revision.get());
        // 真正由rev1发出的新失败仍执行冷却，避免无限刷新。
        try { refresh.run(lifecycle, revision.get(), revision::get, providerCalls::incrementAndGet); fail(); }
        catch (CloudException expected) { assertEquals(CloudException.Code.NETWORK, expected.code); }
        assertEquals(1, providerCalls.get());
    }
    @Test public void concurrentFailuresShareOneRefreshWithoutHoldingStateLock() throws Exception {
        LeaseRefresh refresh = new LeaseRefresh(); Cancellation lifecycle = new Cancellation();
        ExecutorService workers = Executors.newFixedThreadPool(2); CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
        try {
            Future<Boolean> leader = workers.submit(() -> refresh.run(lifecycle, () -> { calls.incrementAndGet(); entered.countDown(); assertTrue(finish.await(3, TimeUnit.SECONDS)); }));
            assertTrue(entered.await(1, TimeUnit.SECONDS)); AtomicReference<Thread> joining = new AtomicReference<>();
            Future<Boolean> follower = workers.submit(() -> { joining.set(Thread.currentThread()); return refresh.run(lifecycle, calls::incrementAndGet); });
            long deadline = System.nanoTime() + 1000000000L;
            while ((joining.get() == null || joining.get().getState() != Thread.State.TIMED_WAITING) && System.nanoTime() < deadline) Thread.yield();
            assertFalse(follower.isDone()); finish.countDown();
            assertTrue(leader.get(1, TimeUnit.SECONDS)); assertFalse(follower.get(1, TimeUnit.SECONDS)); assertEquals(1, calls.get());
        } finally { finish.countDown(); workers.shutdownNow(); }
    }
    @Test public void releaseCancelsBlockedRefreshAndPreventsLatePublish() throws Exception {
        LeaseRefresh refresh = new LeaseRefresh(); Cancellation lifecycle = new Cancellation(); ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1); AtomicInteger published = new AtomicInteger();
        try {
            Future<?> result = worker.submit(() -> {
                try {
                    refresh.run(lifecycle, () -> { try (AutoCloseable link = lifecycle.attach(unblock::countDown)) { entered.countDown(); unblock.await(3, TimeUnit.SECONDS); lifecycle.check(); published.incrementAndGet(); } });
                    fail();
                } catch (CloudException expected) { assertEquals(CloudException.Code.CANCELLED, expected.code); }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS)); long start = System.nanoTime(); lifecycle.cancel();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 250); result.get(1, TimeUnit.SECONDS); assertEquals(0, published.get());
            assertTrue(new LeaseRefresh().run(new Cancellation(), () -> { }));
        } finally { unblock.countDown(); worker.shutdownNow(); }
    }
}
