package com.github.tvbox.osc.trending;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TrendingSnapshot {
    public final TrendingBoard board;
    public final String dataDate;
    public final String updatedLabel;
    public final String periodLabel;
    public final long fetchedAt;
    public final long firstSeenAt;
    public final long lastChangedAt;
    public final String fingerprint;
    public final List<TrendingEntry> entries;

    public TrendingSnapshot(TrendingBoard board, String dataDate, String updatedLabel, String periodLabel,
                            long fetchedAt, long firstSeenAt, long lastChangedAt, String fingerprint,
                            List<TrendingEntry> entries) {
        this.board = board;
        this.dataDate = dataDate;
        this.updatedLabel = updatedLabel;
        this.periodLabel = periodLabel;
        this.fetchedAt = fetchedAt;
        this.firstSeenAt = firstSeenAt;
        this.lastChangedAt = lastChangedAt;
        this.fingerprint = fingerprint;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }
}
