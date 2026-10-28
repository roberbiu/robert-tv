package com.github.tvbox.osc.util;

/** 中文字幕版本的试播事务：仅真实起播确认后释放原可播版本快照。 */
public final class SubtitleTrial<T> {
    private T original;
    private String target;
    private long generation;

    public boolean begin(T snapshot, String targetPlayback) {
        if (isPending() || snapshot == null || VersionPolicy.safe(targetPlayback).isEmpty()) return false;
        generation++;
        original = snapshot;
        target = targetPlayback;
        return true;
    }

    public boolean isPending() {
        return original != null;
    }

    public boolean confirmStarted(String playback) {
        return confirmStarted(playback, generation);
    }

    public boolean confirmStarted(String playback, long ticket) {
        if (!isCurrent(ticket, playback)) return false;
        clear();
        return true;
    }

    public T rollback(String playback) {
        return rollback(playback, generation);
    }

    public T rollback(String playback, long ticket) {
        if (!isCurrent(ticket, playback)) return null;
        T snapshot = original;
        clear();
        return snapshot;
    }

    public void clear() {
        generation++;
        original = null;
        target = null;
    }

    public long generation() {
        return generation;
    }

    public boolean isGenerationCurrent(long ticket) {
        return generation == ticket;
    }

    public boolean isCurrent(long ticket, String playback) {
        return isGenerationCurrent(ticket) && isTarget(playback);
    }

    public boolean invalidateForPlayback(String playback) {
        if (!isPending() || isTarget(playback)) return false;
        clear();
        return true;
    }

    public boolean isTarget(String playback) {
        return isPending() && target.equals(playback);
    }

    public static long snapshotPosition(long currentPosition, long savedPosition) {
        return currentPosition >= 0 ? currentPosition : Math.max(savedPosition, 0);
    }
}
