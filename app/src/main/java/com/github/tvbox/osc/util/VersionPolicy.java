package com.github.tvbox.osc.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 不依赖播放器状态的版本信息与自动换源准入规则。 */
public final class VersionPolicy {
    public enum SubtitleChoice { MANUAL_EXTERNAL, INTERNAL_CHINESE, SOURCE_EXTERNAL, INTERNAL_OTHER, NONE }
    private static final Pattern CHINESE_SUBTITLE = Pattern.compile(
            "(?i)中文字幕|中英(?:双字|字幕|双语)|简(?:体字幕|中)|繁(?:体字幕|中)|中字|\\b(?:chs|cht)\\b|chinese[ -]?(?:sub|caption)");
    private static final Pattern NO_CHINESE_SUBTITLE = Pattern.compile(
            "(?i)无(?:中文字幕|中字)|不含中文字幕|无字幕|生肉|raw[ -]?version");
    private static final Pattern UNCERTAIN_SUBTITLE = Pattern.compile("中文字幕(?:未知|待确认|待补)|字幕待确认|字幕未知");
    private static final Pattern QUALITY_4K = Pattern.compile("(?i)2160p|(?<![a-z0-9])4k(?![a-z0-9])|3840[×x*]2160|uhd");
    private static final Pattern QUALITY_1080 = Pattern.compile("(?i)1080p|1920[×x*]1080|(?<![a-z0-9])fhd(?![a-z0-9])");
    private static final Pattern QUALITY_720 = Pattern.compile("(?i)720p|1280[×x*]720");
    private static final Pattern QUALITY_SUFFIX = Pattern.compile(
            "(?i)\\s*[\\[【(](?:4k|2160p|1080p|720p|480p|hd|fhd|uhd|中字|中文字幕|中英字幕|简中|繁中|双语|蓝光|高清|[ /+_-])+[\\]】)]\\s*$");
    private static final Pattern SEASON = Pattern.compile(
            "(?i)第\\s*([0-9一二三四五六七八九十两]+)\\s*季|\\bseason\\s*(\\d{1,2})\\b|\\bs(\\d{1,2})(?=\\b|e\\d)");
    private static final Pattern EPISODE = Pattern.compile(
            "(?i)s\\d{1,2}\\s*e(\\d{1,4})(?!\\d)|(?:episode|ep|e)\\s*(\\d{1,4})(?!\\d)|第\\s*([0-9一二三四五六七八九十两]+)\\s*[集话期]");

    private VersionPolicy() {
    }

    public static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static String displayTitle(String title, int year) {
        return safe(title) + (year > 0 ? "（" + year + "）" : "");
    }

    public static boolean claimsChineseSubtitle(String title, String note) {
        String text = safe(title) + " " + safe(note);
        return !NO_CHINESE_SUBTITLE.matcher(text).find() && !UNCERTAIN_SUBTITLE.matcher(text).find()
                && CHINESE_SUBTITLE.matcher(text).find();
    }

    public static String subtitleLabel(String title, String note) {
        String text = safe(title) + " " + safe(note);
        if (NO_CHINESE_SUBTITLE.matcher(text).find()) return "来源标注无中文";
        return claimsChineseSubtitle(title, note) ? "来源标注中文" : "字幕待确认";
    }

    public static String qualityLabel(String title, String note) {
        String text = (safe(title) + " " + safe(note)).toLowerCase(Locale.ROOT);
        if (QUALITY_4K.matcher(text).find()) return "来源标注4K";
        if (QUALITY_1080.matcher(text).find()) return "来源标注1080P";
        if (QUALITY_720.matcher(text).find()) return "来源标注720P";
        if (text.contains("480p")) return "来源标注480P";
        if (text.contains("高清") || text.matches(".*\\bhd\\b.*")) return "来源标注高清";
        return "清晰度待确认";
    }

    public static String authorizationLabel(String authorization) {
        if ("none".equals(authorization)) return "无需网盘授权";
        if ("required".equals(authorization)) return "需扫码授权";
        return "授权待确认";
    }

    /** 音轨语言只用于识别语言标签，不能据此推断影片存在中文字幕。 */
    public static boolean isChineseLanguage(String language) {
        String text = safe(language).toLowerCase(Locale.ROOT).replace('_', '-');
        return text.equals("zh") || text.startsWith("zh-") || text.equals("zho")
                || text.equals("chi") || text.equals("cmn") || text.equals("yue")
                || text.equals("chs") || text.equals("cht") || text.equals("chinese")
                || text.equals("中文") || text.equals("简体中文") || text.equals("繁体中文")
                || text.equals("国语") || text.equals("粤语") || text.equals("普通话");
    }

    public static int balancedScore(String title, String note, String authorization) {
        int score = claimsChineseSubtitle(title, note) ? 30 : 0;
        if ("none".equals(authorization)) score += 12;
        String quality = qualityLabel(title, note);
        if (quality.endsWith("1080P")) score += 10;
        else if (quality.endsWith("4K")) score += 8;
        else if (quality.endsWith("720P")) score += 6;
        else if (quality.endsWith("高清")) score += 4;
        return score;
    }

    public static String normalizeTitle(String title) {
        String text = Normalizer.normalize(safe(title), Normalizer.Form.NFKC);
        String previous;
        do {
            previous = text;
            text = QUALITY_SUFFIX.matcher(text).replaceFirst("").trim();
        } while (!text.equals(previous));
        Matcher matcher = SEASON.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            int number = number(firstNonEmpty(matcher.group(1), matcher.group(2), matcher.group(3)));
            matcher.appendReplacement(result, Matcher.quoteReplacement("第" + number + "季"));
        }
        matcher.appendTail(result);
        return result.toString().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 自动切换要求双方年份均已知；未知版本仍可由用户手动选择。 */
    public static boolean canAutoSwitch(String expectedTitle, int expectedYear, String candidateTitle, int candidateYear) {
        return expectedYear > 0 && candidateYear == expectedYear
                && !normalizeTitle(expectedTitle).isEmpty()
                && normalizeTitle(expectedTitle).equals(normalizeTitle(candidateTitle));
    }

    public static int episodeNumber(String label) {
        String text = Normalizer.normalize(safe(label), Normalizer.Form.NFKC);
        Matcher matcher = EPISODE.matcher(text);
        if (matcher.find()) return number(firstNonEmpty(matcher.group(1), matcher.group(2), matcher.group(3)));
        return text.matches("\\d{1,4}") ? number(text) : -1;
    }

    public static boolean sameEpisode(String left, String right) {
        String a = safe(left);
        String b = safe(right);
        if (a.isEmpty() || b.isEmpty()) return false;
        int leftSeason = seasonNumber(a);
        int rightSeason = seasonNumber(b);
        if (leftSeason > 0 && rightSeason > 0 && leftSeason != rightSeason) return false;
        if (normalizeTitle(a).equals(normalizeTitle(b))) return true;
        int leftEpisode = episodeNumber(a);
        int rightEpisode = episodeNumber(b);
        if (leftEpisode >= 0 || rightEpisode >= 0) {
            return leftEpisode >= 0 && leftEpisode == rightEpisode
                    && episodeVariant(a).equals(episodeVariant(b));
        }
        return isWholeFilmLabel(a) && isWholeFilmLabel(b);
    }

    /** 上下期、预告、纯享和加更等属于内容差异，不能仅按集号自动互换。 */
    private static String episodeVariant(String label) {
        String text = Normalizer.normalize(safe(label), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (text.matches("\\d{1,4}")) return "";
        text = SEASON.matcher(text).replaceAll("");
        text = EPISODE.matcher(text).replaceAll("");
        text = text.replaceAll("(?i)2160p|1080p|720p|480p|4k|fhd|uhd|hd|中文字幕|中英字幕|中字|国语版?|普通话|粤语版?", "");
        text = text.replaceAll("[\\s\\[\\]()【】_\\-·:：]+", "");
        text = text.replaceAll("([上中下])(?:半场|半部|半篇|半|篇|部|集|期)", "$1");
        return text.replace("预告片", "预告").replace("纯享版", "纯享").replace("加更版", "加更");
    }

    public static String episodeIdentity(String label) {
        int episode = episodeNumber(label);
        int season = seasonNumber(label);
        return episode >= 0 ? "s" + Math.max(season, 0) + "e" + episode + "|" + episodeVariant(label)
                : normalizeTitle(label);
    }

    public static String workEpisodeIdentity(String title, int year, String episode) {
        int number = episodeNumber(episode);
        int season = seasonNumber(episode);
        if (season < 0) season = seasonNumber(title);
        String identity = number >= 0 ? "s" + Math.max(season, 0) + "e" + number + "|" + episodeVariant(episode)
                : episodeIdentity(episode);
        return normalizeTitle(title) + "|" + year + "|" + identity;
    }

    public static String playbackIdentity(String source, String id, String line, String episode) {
        return safe(source) + "|" + safe(id) + "|" + safe(line) + "|" + episodeIdentity(episode);
    }

    public static SubtitleChoice chooseSubtitle(boolean manualExternal, boolean chineseInternal,
                                                boolean sourceExternal, boolean anyInternal) {
        if (manualExternal) return SubtitleChoice.MANUAL_EXTERNAL;
        if (chineseInternal) return SubtitleChoice.INTERNAL_CHINESE;
        if (sourceExternal) return SubtitleChoice.SOURCE_EXTERNAL;
        return anyInternal ? SubtitleChoice.INTERNAL_OTHER : SubtitleChoice.NONE;
    }

    private static boolean isWholeFilmLabel(String label) {
        return safe(label).toLowerCase(Locale.ROOT).matches(
                "(?:正片|完整版|全片|高清|蓝光|超清|hd|fhd|uhd|4k|2160p|1080p|720p)(?:中字|中文字幕|中英字幕)?");
    }

    private static int seasonNumber(String label) {
        Matcher matcher = SEASON.matcher(safe(label));
        return matcher.find() ? number(firstNonEmpty(matcher.group(1), matcher.group(2), matcher.group(3))) : -1;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.isEmpty()) return value;
        return "";
    }

    private static int number(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            String digits = "零一二三四五六七八九";
            String text = value.replace('两', '二');
            int ten = text.indexOf('十');
            if (ten >= 0) {
                int tens = ten == 0 ? 1 : digits.indexOf(text.charAt(0));
                int units = ten + 1 < text.length() ? digits.indexOf(text.charAt(ten + 1)) : 0;
                return tens > 0 && units >= 0 ? tens * 10 + units : -1;
            }
            return text.length() == 1 ? digits.indexOf(text.charAt(0)) : -1;
        }
    }
}
