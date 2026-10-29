package com.github.tvbox.osc.source;

import com.github.tvbox.osc.catalog.SourceDescriptor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;

/** 不可变请求身份与共享取消信号；不包含凭据或媒体地址。 */
public final class RequestContext {
    private final String sourceId;
    private final String adapterId;
    private final int configRevision;
    private final long registryRevision;
    private final long requestGeneration;
    private final long playbackGeneration;
    private final boolean explicitUserAction;
    private final AtomicBoolean cancelled;
    private final CopyOnWriteArrayList<Runnable> cancellationListeners;

    public RequestContext(SourceDescriptor source, long registryRevision, long requestGeneration) {
        this.sourceId = source.getId();
        this.adapterId = source.getAdapterId();
        this.configRevision = source.getConfigRevision();
        this.registryRevision = registryRevision;
        this.requestGeneration = requestGeneration;
        playbackGeneration = 0;
        explicitUserAction = false;
        cancelled = new AtomicBoolean(false);
        cancellationListeners = new CopyOnWriteArrayList<>();
    }
    private RequestContext(RequestContext source, long playbackGeneration, boolean explicitUserAction) {
        sourceId = source.sourceId;
        adapterId = source.adapterId;
        configRevision = source.configRevision;
        registryRevision = source.registryRevision;
        requestGeneration = source.requestGeneration;
        this.playbackGeneration = playbackGeneration;
        this.explicitUserAction = explicitUserAction;
        cancelled = source.cancelled;
        cancellationListeners = source.cancellationListeners;
    }
    public RequestContext withPlayback(long generation, boolean explicitUserAction) {
        if (generation < 1) throw new IllegalArgumentException("播放代际无效");
        return new RequestContext(this, generation, explicitUserAction);
    }
    public String getSourceId() { return sourceId; }
    public String getAdapterId() { return adapterId; }
    public int getConfigRevision() { return configRevision; }
    public long getRegistryRevision() { return registryRevision; }
    public long getRequestGeneration() { return requestGeneration; }
    public long getPlaybackGeneration() { return playbackGeneration; }
    public boolean isExplicitUserAction() { return explicitUserAction; }
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        for (Runnable listener : cancellationListeners) {
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
        cancellationListeners.clear();
    }
    public void onCancel(Runnable listener) {
        if (cancelled.get()) { listener.run(); return; }
        cancellationListeners.add(listener);
        if (cancelled.get() && cancellationListeners.remove(listener)) listener.run();
    }
    public void removeCancellationListener(Runnable listener) { cancellationListeners.remove(listener); }
    public boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
    public boolean matches(SourceDescriptor source) {
        return sourceId.equals(source.getId()) && adapterId.equals(source.getAdapterId())
                && configRevision == source.getConfigRevision();
    }
}
