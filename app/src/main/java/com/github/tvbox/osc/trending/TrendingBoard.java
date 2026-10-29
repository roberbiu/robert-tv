package com.github.tvbox.osc.trending;

import com.github.tvbox.osc.browse.ContentKind;

import java.util.ArrayList;
import java.util.List;

/** 各来源原始榜单独立展示，不把不同指标合成新的全国排名。 */
public enum TrendingBoard {
    NETWORK_DRAMA(ContentKind.TV, "骨朵网络剧日榜", "骨朵GDI热度", "NETWORK_DRAMA"),
    TV_DRAMA(ContentKind.TV, "骨朵电视剧日榜", "骨朵GDI热度", "TV_DRAMA"),
    NETWORK_MOVIE(ContentKind.MOVIE, "骨朵网络电影日榜", "骨朵GDI热度", "NETWORK_MOVIE"),
    BAIDU_MOVIE(ContentKind.MOVIE, "百度电影热搜参考", "百度搜索热度", null),
    NETWORK_VARIETY(ContentKind.VARIETY, "骨朵网络综艺日榜", "骨朵GDI热度", "NETWORK_VARIETY"),
    TV_VARIETY(ContentKind.VARIETY, "骨朵电视综艺日榜", "骨朵GDI热度", "TV_VARIETY"),
    ALL_ANIME(ContentKind.ANIMATION, "骨朵动漫日榜", "骨朵GDI热度", "ALL_ANIME"),
    HONGGUO_SHORT(ContentKind.SHORT_DRAMA, "红果短剧热播榜", "红果内观看/互动综合热度", null);

    public final ContentKind kind;
    public final String label;
    public final String metric;
    public final String guduoCategory;

    TrendingBoard(ContentKind kind, String label, String metric, String guduoCategory) {
        this.kind = kind;
        this.label = label;
        this.metric = metric;
        this.guduoCategory = guduoCategory;
    }

    public String officialUrl() {
        if (guduoCategory != null) return "https://d2.guduomedia.com/";
        return this == HONGGUO_SHORT ? "https://hongguoduanju.com/rank/hot-drama"
                : "https://top.baidu.com/board?tab=movie";
    }

    public static List<TrendingBoard> forKind(ContentKind kind) {
        List<TrendingBoard> result = new ArrayList<>();
        for (TrendingBoard board : values()) if (board.kind == kind) result.add(board);
        return result;
    }
}
