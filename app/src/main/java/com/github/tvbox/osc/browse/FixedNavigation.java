package com.github.tvbox.osc.browse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 产品导航由应用持有，来源分类变更不改动一级入口。 */
public final class FixedNavigation {
    public static final class Item {
        public final String id;
        public final String label;
        public final ContentKind kind;
        public final String country;

        private Item(String id, String label, ContentKind kind, String country) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.country = country;
        }
    }

    public static final List<Item> ITEMS = Collections.unmodifiableList(Arrays.asList(
            new Item("my0", "首页", ContentKind.UNKNOWN, ""),
            new Item("trending", "热播", ContentKind.UNKNOWN, ""),
            new Item("browse_tv", "电视剧", ContentKind.TV, ""),
            new Item("browse_movie", "电影", ContentKind.MOVIE, ""),
            new Item("browse_variety", "综艺", ContentKind.VARIETY, ""),
            new Item("browse_animation", "动漫", ContentKind.ANIMATION, ""),
            new Item("country_kr", "韩剧", ContentKind.TV, "韩国"),
            new Item("country_us", "美剧", ContentKind.TV, "美国"),
            new Item("country_uk", "英剧", ContentKind.TV, "英国"),
            new Item("country_jp", "日剧", ContentKind.TV, "日本")
    ));

    private FixedNavigation() {
    }
}
