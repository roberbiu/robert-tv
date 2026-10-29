package com.github.tvbox.osc.browse;

import com.github.tvbox.osc.util.ContentPolicy;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 只把已识别类别映射到产品分类，不依赖跨站不同的数字ID。 */
public final class BrowseCategoryPolicy {
    private static final Set<String> CMS_PARENTS = new HashSet<>(Arrays.asList(
            "电影", "电视剧", "连续剧", "综艺", "动漫", "动画"));
    private static final Set<String> FILMS = new HashSet<>(Arrays.asList(
            "动作片", "喜剧片", "爱情片", "科幻片", "恐怖片", "剧情片", "战争片", "惊悚片",
            "冒险片", "犯罪片", "悬疑片", "家庭片", "奇幻片", "历史片", "灾难片", "武侠片", "音乐片"));

    private BrowseCategoryPolicy() {
    }

    public static boolean isCmsLeaf(String id, String name) {
        return id != null && id.matches("[0-9]+") && name != null && !CMS_PARENTS.contains(name.trim())
                && kindOf(name) != ContentKind.UNKNOWN;
    }

    public static ContentKind kindOf(String name) {
        if (name == null || !ContentPolicy.isAllowedCategory(name)) return ContentKind.UNKNOWN;
        String type = name.trim();
        if (type.contains("解说") || type.contains("预告")) return ContentKind.UNKNOWN;
        if (type.contains("短剧") || type.contains("短漫剧")) return ContentKind.SHORT_DRAMA;
        if (type.contains("纪录") || type.contains("记录")) return ContentKind.DOCUMENTARY;
        if (type.contains("动漫") || type.contains("动画") || type.equals("国漫") || type.equals("日漫")) return ContentKind.ANIMATION;
        if (type.contains("综艺") || type.equals("真人秀") || type.equals("脱口秀")) return ContentKind.VARIETY;
        if (type.equals("电影") || FILMS.contains(type)) return ContentKind.MOVIE;
        if (type.contains("剧") || type.equals("连续剧")) return ContentKind.TV;
        return ContentKind.UNKNOWN;
    }

    public static boolean accepts(ContentKind requested, String actualType, String actualCountry, String country) {
        return requested == kindOf(actualType) && CountryMatcher.matches(actualCountry, country);
    }
}
