package com.github.tvbox.osc.source;

import com.github.tvbox.osc.catalog.SourceDescriptor;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** 每站独立有界队列；慢站和取消不占住整个应用的来源加载。 */
public final class AdapterRuntime {
    public interface Operation<T> { T call() throws Exception; }
    private static final AdapterRuntime INSTANCE = new AdapterRuntime();
    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();
    private final ConcurrentHashMap<String, ThreadPoolExecutor> workers = new ConcurrentHashMap<>();
    private final AtomicInteger threadNumber = new AtomicInteger();

    private AdapterRuntime() { }
    public static AdapterRuntime get() { return INSTANCE; }
    public static RequestContext currentContext() { return CURRENT.get(); }

    private ThreadPoolExecutor worker(String sourceId) {
        ThreadPoolExecutor existing = workers.get(sourceId);
        if (existing != null) return existing;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "source-worker-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor created = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4), factory, new ThreadPoolExecutor.AbortPolicy());
        created.allowCoreThreadTimeOut(true);
        ThreadPoolExecutor raced = workers.putIfAbsent(sourceId, created);
        if (raced != null) {
            created.shutdownNow();
            return raced;
        }
        return created;
    }

    /** 仅从后台调用；返回后仍需由UI核对自己的当前generation/revision。 */
    public <T> SourceResult<T> execute(SourceDescriptor source, RequestContext context, Operation<T> operation) {
        if (source == null || context == null || operation == null || !context.matches(source)
                || !source.isEnabled()) {
            return SourceResult.failure(new DomainError(DomainError.Code.SOURCE_UNAVAILABLE), context);
        }
        if (context.isCancelled()) return cancelled(context);
        Future<T> future;
        ThreadPoolExecutor executor = worker(source.getId());
        try {
            future = executor.submit(() -> {
                if (context.isCancelled()) throw new InterruptedException();
                CURRENT.set(context);
                try { return operation.call(); }
                finally { CURRENT.remove(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            return SourceResult.failure(new DomainError(DomainError.Code.SOURCE_UNAVAILABLE), context);
        }
        Runnable cancelOperation = () -> { future.cancel(true); executor.purge(); };
        context.onCancel(cancelOperation);
        try {
            T value = future.get(source.getTimeoutSeconds(), TimeUnit.SECONDS);
            return context.isCancelled() ? cancelled(context) : SourceResult.success(value, context);
        } catch (TimeoutException error) {
            context.cancel();
            future.cancel(true);
            return SourceResult.failure(new DomainError(DomainError.Code.NETWORK), context);
        } catch (InterruptedException error) {
            context.cancel();
            future.cancel(true);
            Thread.currentThread().interrupt();
            return cancelled(context);
        } catch (ExecutionException error) {
            if (context.isCancelled() || error.getCause() instanceof InterruptedException) return cancelled(context);
            if (error.getCause() instanceof SourceException) {
                return SourceResult.failure(((SourceException) error.getCause()).getError(), context);
            }
            DomainError.Code code = error.getCause() instanceof IOException
                    ? DomainError.Code.NETWORK : DomainError.Code.INVALID_RESPONSE;
            return SourceResult.failure(new DomainError(code), context);
        } catch (CancellationException error) {
            return cancelled(context);
        } finally {
            context.removeCancellationListener(cancelOperation);
        }
    }

    private static <T> SourceResult<T> cancelled(RequestContext context) {
        return SourceResult.failure(new DomainError(DomainError.Code.CANCELLED), context);
    }
}
