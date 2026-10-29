package com.github.tvbox.osc.trending;

public final class TrendingEntry {
    public final String id;
    public final String title;
    public final int rank;
    public final String score;
    public final String cover;
    public final String detailUrl;

    public TrendingEntry(String id, String title, int rank, String score, String cover, String detailUrl) {
        this.id = id;
        this.title = title;
        this.rank = rank;
        this.score = score;
        this.cover = cover;
        this.detailUrl = detailUrl;
    }
}
