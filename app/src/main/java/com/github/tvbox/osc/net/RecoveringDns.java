package com.github.tvbox.osc.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import okhttp3.Dns;

/** 只在系统DNS失败或返回保留地址时恢复；两阶段共用3秒预算，不修改设备DNS。 */
public final class RecoveringDns implements Dns {
    interface Fallback { List<InetAddress> lookup(String host, long deadlineNanos, Cancellation cancellation) throws IOException; }
    private static final ExecutorService SYSTEM_WORKERS = pool("public-dns-system");
    private static final ExecutorService HTTPS_WORKERS = pool("public-dns-https");
    private static final Dns INSTANCE = new RecoveringDns(Dns.SYSTEM, new AliDnsHttpsResolver(), SYSTEM_WORKERS, HTTPS_WORKERS, 3000, 750);
    private final Dns system;
    private final Fallback fallback;
    private final ExecutorService systemWorkers;
    private final ExecutorService httpsWorkers;
    private final long budgetMillis;
    private final long systemWaitMillis;

    RecoveringDns(Dns system, Fallback fallback, ExecutorService systemWorkers, ExecutorService httpsWorkers,
                  long budgetMillis, long systemWaitMillis) {
        this.system = system; this.fallback = fallback; this.systemWorkers = systemWorkers; this.httpsWorkers = httpsWorkers;
        this.budgetMillis = budgetMillis; this.systemWaitMillis = systemWaitMillis;
    }
    public static Dns system() { return INSTANCE; }

    @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        if (Thread.currentThread().isInterrupted()) throw PublicAddressPolicy.denied();
        if (PublicAddressPolicy.isLiteral(hostname)) {
            InetAddress address = PublicAddressPolicy.literal(hostname); PublicAddressPolicy.requirePublic(address);
            return Collections.singletonList(address);
        }
        String host = PublicAddressPolicy.publicHostname(hostname);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        Future<List<InetAddress>> first = null;
        try {
            first = systemWorkers.submit(() -> PublicAddressPolicy.requirePublicAnswers(system.lookup(host)));
            return first.get(Math.min(remaining(deadline), systemWaitMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); throw PublicAddressPolicy.denied();
        } catch (ExecutionException | TimeoutException | java.util.concurrent.RejectedExecutionException unavailable) {
            // 有限系统线程可能尚未退出；独立HTTPS池仍可使用，但不创建无界线程。
        } finally { if (first != null) first.cancel(true); }
        Cancellation cancellation = new Cancellation();
        Future<List<InetAddress>> recovery = null;
        try {
            long left = remaining(deadline);
            recovery = httpsWorkers.submit(() -> PublicAddressPolicy.requirePublicAnswers(fallback.lookup(host, deadline, cancellation)));
            return recovery.get(left, TimeUnit.MILLISECONDS);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); throw PublicAddressPolicy.denied();
        } catch (ExecutionException | TimeoutException | java.util.concurrent.RejectedExecutionException unavailable) {
            throw PublicAddressPolicy.denied();
        } finally {
            cancellation.cancel();
            if (recovery != null) recovery.cancel(true);
        }
    }

    static long remaining(long deadline) throws UnknownHostException {
        long value = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (value < 1) throw PublicAddressPolicy.denied();
        return value;
    }

    private static ExecutorService pool(String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
            Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    static final class Cancellation {
        private boolean cancelled;
        private Runnable active;
        synchronized void attach(Runnable action) { if (cancelled) action.run(); else active = action; }
        synchronized void detach(Runnable action) { if (active == action) active = null; }
        synchronized void cancel() { cancelled = true; if (active != null) active.run(); active = null; }
        synchronized void check() throws UnknownHostException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw PublicAddressPolicy.denied();
        }
    }
}
