package com.github.tvbox.osc.browse;

public enum ContentKind {
    TV("电视剧"), MOVIE("电影"), VARIETY("综艺"), ANIMATION("动漫"),
    DOCUMENTARY("纪录片"), SHORT_DRAMA("短剧"), UNKNOWN("其他");

    public final String label;

    ContentKind(String label) {
        this.label = label;
    }
}
