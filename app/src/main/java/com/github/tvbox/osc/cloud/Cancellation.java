package com.github.tvbox.osc.cloud;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;

/** 每次界面操作独立持有，取消后不能被后续重试复活。 */
public final class Cancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> listeners = new ArrayList<>();
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        List<Runnable> actions;
        synchronized (listeners) { actions = new ArrayList<>(listeners); listeners.clear(); }
        for (Runnable action : actions) try { action.run(); } catch (RuntimeException ignored) { }
    }
    public AutoCloseable attach(Runnable action) {
        synchronized (listeners) {
            if (!cancelled.get()) {
                listeners.add(action);
                return () -> { synchronized (listeners) { listeners.remove(action); } };
            }
        }
        action.run();
        return () -> { };
    }
    public boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
    public void check() throws CloudException {
        if (isCancelled()) throw new CloudException(CloudException.Code.CANCELLED, null);
    }
}
