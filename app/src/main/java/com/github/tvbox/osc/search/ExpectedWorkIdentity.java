package com.github.tvbox.osc.search;

import com.github.tvbox.osc.browse.BrowseCategoryPolicy;
import com.github.tvbox.osc.browse.ContentKind;
import com.github.tvbox.osc.browse.CountryMatcher;
import com.github.tvbox.osc.util.VersionPolicy;

import java.net.URI;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 只比较来源真实字段；缺失信息不等于一致，也不按参考海报补全候选。 */
public final class ExpectedWorkIdentity {
    public enum FieldState { SAME, UNKNOWN, DIFFERENT }

    public static final class Assessment {
        public final FieldState title;
        public final FieldState year;
        public final FieldState type;
        public final FieldState area;

        Assessment(FieldState title, FieldState year, FieldState type, FieldState area) {
            this.title = title;
            this.year = year;
            this.type = type;
            this.area = area;
        }

        public boolean hasDifference() {
            return count(FieldState.DIFFERENT) > 0;
        }

        public boolean allKnownSame() {
            return count(FieldState.SAME) == 4;
        }

        public int priority() {
            return allKnownSame() ? 0 : hasDifference() ? 2 : 1;
        }

        public int sameFieldCount() {
            return count(FieldState.SAME);
        }

        private int count(FieldState expected) {
            int count = 0;
            for (FieldState value : new FieldState[]{title, year, type, area}) if (value == expected) count++;
            return count;
        }

        public String label() {
            if (allKnownSame()) return "来源字段一致 · 请核对版本";
            String differences = fields(FieldState.DIFFERENT);
            String unknown = fields(FieldState.UNKNOWN);
            return differences.isEmpty() ? "待核对：" + unknown
                    : "信息不同：" + differences + (unknown.isEmpty() ? "" : "；待核对：" + unknown);
        }

        private String fields(FieldState target) {
            StringBuilder fields = new StringBuilder();
            append(fields, title, target, "片名");
            append(fields, year, target, "年份");
            append(fields, type, target, "类型");
            append(fields, area, target, "地区");
            return fields.toString();
        }

        private static void append(StringBuilder fields, FieldState actual, FieldState target, String name) {
            if (actual != target) return;
            if (fields.length() > 0) fields.append('/');
            fields.append(name);
        }
    }

    private static final Set<String> UNKNOWN_AREAS = new HashSet<>(Arrays.asList(
            "未知", "不详", "不明", "未知地区", "地区未知", "国家未知", "待确认", "未提供", "暂无", "无", "其他", "其它", "其他地区", "其它地区",
            "欧美", "日韩", "港台", "海外", "亚洲", "不限", "全部", "unknown", "n/a", "null", "none", "nan", "？"));
    private final String title;
    private final int year;
    private final String type;
    private final String area;
    private final String picture;

    private ExpectedWorkIdentity(String title, int year, String type, String area, String picture) {
        this.title = bounded(title, 160);
        this.year = validYear(year) ? year : 0;
        this.type = bounded(type, 80);
        this.area = bounded(area, 160);
        this.picture = pictureUrl(bounded(picture, 2048));
    }

    public static ExpectedWorkIdentity none() {
        return new ExpectedWorkIdentity("", 0, "", "", "");
    }

    public static ExpectedWorkIdentity of(String title, int year, String type, String area, String picture) {
        return new ExpectedWorkIdentity(title, year, type, area, picture);
    }

    public boolean isActive() {
        return !title.isEmpty();
    }

    public boolean appliesToSearch(String query) {
        return isActive() && VersionPolicy.normalizeTitle(title).equals(VersionPolicy.normalizeTitle(query));
    }

    public String picture() {
        return picture;
    }

    public String expectationLabel() {
        return "正在查找：" + title + " · " + (year > 0 ? year : "年份未知")
                + " · " + (BrowseCategoryPolicy.kindOf(type) == ContentKind.UNKNOWN ? "类型未知" : type)
                + " · " + (areaTokens(area) == null ? "地区未知" : area);
    }

    public Assessment assess(String candidateTitle, int candidateYear, String candidateType, String candidateArea) {
        String candidate = VersionPolicy.normalizeTitle(candidateTitle);
        FieldState titleState = candidate.isEmpty() ? FieldState.UNKNOWN
                : VersionPolicy.normalizeTitle(title).equals(candidate) ? FieldState.SAME : FieldState.DIFFERENT;
        FieldState yearState = year == 0 || !validYear(candidateYear) ? FieldState.UNKNOWN
                : year == candidateYear ? FieldState.SAME : FieldState.DIFFERENT;
        ContentKind expectedKind = BrowseCategoryPolicy.kindOf(type);
        ContentKind candidateKind = BrowseCategoryPolicy.kindOf(candidateType);
        FieldState typeState = expectedKind == ContentKind.UNKNOWN || candidateKind == ContentKind.UNKNOWN ? FieldState.UNKNOWN
                : expectedKind == candidateKind ? FieldState.SAME : FieldState.DIFFERENT;
        return new Assessment(titleState, yearState, typeState, compareAreas(area, candidateArea));
    }

    public boolean canOfferAutomaticFallback(String candidateTitle, int candidateYear, String candidateType, String candidateArea) {
        ContentKind kind = BrowseCategoryPolicy.kindOf(type);
        boolean seasonOrFilmKnown = kind == ContentKind.MOVIE || VersionPolicy.normalizeTitle(title).matches(".*第[0-9]+季.*");
        return seasonOrFilmKnown && assess(candidateTitle, candidateYear, candidateType, candidateArea).allKnownSame();
    }

    public static String actualLabel(int year, String type, String area) {
        return (validYear(year) ? String.valueOf(year) : "年份未知") + " · "
                + shortLabel(type, "类型未知") + " · " + shortLabel(area, "地区未知");
    }

    private static FieldState compareAreas(String expected, String candidate) {
        Set<String> left = areaTokens(expected);
        Set<String> right = areaTokens(candidate);
        if (left == null || right == null) return FieldState.UNKNOWN;
        if (left.equals(right)) return FieldState.SAME;
        for (String value : left) if (right.contains(value)) return FieldState.UNKNOWN;
        return FieldState.DIFFERENT;
    }

    private static Set<String> areaTokens(String value) {
        String normalized = Normalizer.normalize(bounded(value, 160), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+(?:and|&amp;)\\s+", "/");
        if (normalized.isEmpty()) return null;
        Set<String> result = new HashSet<>();
        for (String part : normalized.split("[,，、/|;；&]+")) {
            String token = part.trim();
            if (token.isEmpty() || UNKNOWN_AREAS.contains(token) || token.matches("[0-9?. _-]+")) return null;
            String country = canonicalCountry(token);
            result.add(country);
        }
        return result.isEmpty() ? null : result;
    }

    private static String canonicalCountry(String token) {
        for (String country : new String[]{"美国", "英国", "日本", "韩国"}) {
            if (CountryMatcher.matches(token, country)) return country;
        }
        if (Arrays.asList("中国大陆", "中國大陸", "大陆", "大陸", "内地", "中国", "中國", "china", "mainland china", "cn").contains(token)) return "中国大陆";
        if (Arrays.asList("香港", "中国香港", "中國香港", "hong kong", "hk").contains(token)) return "香港";
        if (Arrays.asList("台湾", "台灣", "中国台湾", "中國台灣", "taiwan", "tw").contains(token)) return "台湾";
        return token;
    }

    private static boolean validYear(int year) {
        return year >= 1880 && year <= 2100;
    }

    private static String shortLabel(String raw, String fallback) {
        String text = bounded(raw, 160);
        if (text.isEmpty()) return fallback;
        return text.length() > 16 ? text.substring(0, 16) + "…" : text;
    }

    private static String bounded(String raw, int maximum) {
        if (raw == null) return "";
        String text = raw.trim();
        return text.length() <= maximum && !text.matches("(?s).*\\p{Cntrl}.*") ? text : "";
    }

    private static String pictureUrl(String picture) {
        try {
            URI uri = URI.create(picture);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 80 || uri.getPort() == 443)) return picture;
        } catch (RuntimeException ignored) {
        }
        return "";
    }
}
