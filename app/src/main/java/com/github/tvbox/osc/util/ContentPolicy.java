package com.github.tvbox.osc.util;

import java.util.Locale;
import java.util.regex.Pattern;

/** 默认内容目录只展示常规影视类别，不把隐藏分类入口当作结果过滤。 */
public final class ContentPolicy {
    private static final Pattern EXCLUDED = Pattern.compile(
            "伦理|理论|情色|色情|三级|写真|福利|成人|18禁|无码|有码|\\bAV\\b|HENTAI|EROTIC|ADULT");
    private static final Pattern INCLUDED = Pattern.compile(
            "电影|剧|片|动漫|动画|综艺|纪录|记录|预告|音乐|演唱|晚会|访谈|真人秀|脱口秀|少儿|儿童"
                    + "|MOVIE|FILM|DRAMA|ANIME|ANIMATION|DOCUMENTARY|VARIETY|TV");

    private ContentPolicy() {
    }

    public static boolean isAllowedCategory(String category) {
        // 上游有些搜索列表不返回分类，不能据字段缺失误判影片内容。
        if (category == null || category.trim().isEmpty()) return true;
        String normalized = category.trim().toUpperCase(Locale.ROOT);
        return !EXCLUDED.matcher(normalized).find() && INCLUDED.matcher(normalized).find();
    }
}
