package com.github.tvbox.osc.trending;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 仅解析公开JSON与HTML数据属性，不执行站点脚本。 */
public final class TrendingParsers {
    private static final int MAX_ATTRIBUTE_CHARS = 1024 * 1024;
    private static final Pattern BAIDU_DATA = Pattern.compile("<!--s-data:(.*?)-->", Pattern.DOTALL);
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})月(\\d{1,2})日已更新");

    private TrendingParsers() {
    }

    public static String guduoDate(String json) {
        JsonObject root = object(json);
        if (integer(root, "code", -1) != 0) throw invalid();
        String date = text(root.getAsJsonObject("data").getAsJsonObject("DAILY"), "endDay", 10);
        if (TrendingFreshness.dataTime(date) == 0) throw invalid();
        return date;
    }

    public static TrendingSnapshot guduo(TrendingBoard board, String date, String json, long now, TrendingSnapshot previous) {
        if (board.guduoCategory == null || TrendingFreshness.dataTime(date) == 0) throw invalid();
        JsonObject root = object(json);
        if (integer(root, "code", -1) != 0 || !root.has("data") || !root.get("data").isJsonArray()) throw invalid();
        List<TrendingEntry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        JsonArray data = root.getAsJsonArray("data");
        if (data.size() > 100) throw invalid();
        for (JsonElement element : data) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!board.guduoCategory.equals(text(item, "category", 40))) continue;
            String id = text(item, "showId", 80);
            String title = text(item, "name", 140);
            int rank = integer(item, "rank", -1);
            String score = text(item, "gdi", 30);
            if (id.isEmpty() || title.isEmpty() || rank < 1 || rank > 500 || !score.matches("\\d+(?:\\.\\d+)?") || !ids.add(id)) continue;
            entries.add(new TrendingEntry(id, title, rank, score, "", "https://d2.guduomedia.com/"));
        }
        return snapshot(board, date, "实际更新时间未提供", "日榜 · 数据日期 " + date, entries, now, previous);
    }

    public static TrendingSnapshot hongguo(String html, long now, TrendingSnapshot previous) {
        Document document = Jsoup.parse(html, "https://hongguoduanju.com/");
        List<TrendingEntry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Element script : document.select("script[data-script-src=modern-run-router-data-fn][data-fn-name=r]")) {
            String attribute = script.attr("data-fn-args");
            if (attribute.length() > MAX_ATTRIBUTE_CHARS) continue;
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(attribute);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (!parsed.isJsonArray()) continue;
            JsonArray arguments = parsed.getAsJsonArray();
            if (arguments.size() != 3 || !arguments.get(0).isJsonPrimitive() || !arguments.get(1).isJsonPrimitive()
                    || !"rank_hot-drama/page".equals(arguments.get(0).getAsString())
                    || !"content".equals(arguments.get(1).getAsString()) || !arguments.get(2).isJsonObject()) continue;
            JsonObject content = arguments.get(2).getAsJsonObject();
            if (!content.has("isSuccess") || !content.get("isSuccess").isJsonPrimitive()
                    || !content.getAsJsonPrimitive("isSuccess").isBoolean()
                    || !content.get("isSuccess").getAsBoolean() || !content.has("rankList") || !content.get("rankList").isJsonArray()) continue;
            JsonArray list = content.getAsJsonArray("rankList");
            if (list.size() > 100) throw invalid();
            for (JsonElement element : list) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String id = text(item, "seriesId", 80);
                if (id.isEmpty()) id = text(item, "id", 80);
                String title = text(item, "title", 140);
                String heat = text(item, "heatText", 60);
                int rank = integer(item, "rank", -1);
                if (id.isEmpty() || title.isEmpty() || heat.isEmpty() || rank < 1 || rank > 500 || !ids.add(id)) continue;
                entries.add(new TrendingEntry(id, title, rank, heat,
                        imageUrl(text(item, "cover", 2048), "hongguo"),
                        safeLink(text(item, "href", 2048), "hongguoduanju.com", "https://hongguoduanju.com/")));
            }
            if (!entries.isEmpty()) break;
        }
        String label = "源更新时间未提供";
        String date = "";
        for (Element paragraph : document.select("p")) {
            String candidate = paragraph.text();
            Matcher match = MONTH_DAY.matcher(candidate);
            if (match.find() && candidate.contains("综合热度")) {
                int month = Integer.parseInt(match.group(1));
                int day = Integer.parseInt(match.group(2));
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    date = String.format(Locale.ROOT, "--%02d-%02d", month, day);
                    label = match.group() + "（来源只提供月日）";
                    break;
                }
            }
        }
        if (date.isEmpty() || TrendingFreshness.dataTime(date, now) == 0) throw invalid();
        return snapshot(TrendingBoard.HONGGUO_SHORT, date, label, "站内综合热度 · 统计窗口未披露", entries, now, previous);
    }

    public static TrendingSnapshot baidu(String html, long now, TrendingSnapshot previous) {
        Matcher match = BAIDU_DATA.matcher(html);
        if (!match.find()) throw invalid();
        JsonObject root = object(match.group(1));
        JsonObject data = root.getAsJsonObject("data");
        if (data == null || !"电影榜".equals(text(data, "curBoardName", 30))) throw invalid();
        JsonArray cards = data.getAsJsonArray("cards");
        if (cards == null || cards.size() == 0 || !cards.get(0).isJsonObject()) throw invalid();
        JsonArray list = cards.get(0).getAsJsonObject().getAsJsonArray("content");
        if (list == null || list.size() > 100) throw invalid();
        List<TrendingEntry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String title = text(item, "word", 140);
            String score = text(item, "hotScore", 30);
            int index = integer(item, "index", -1);
            if (title.isEmpty() || !score.matches("\\d+") || index < 0 || index >= 500 || !ids.add(title)) continue;
            entries.add(new TrendingEntry(title, title, index + 1, score,
                    imageUrl(text(item, "img", 2048), "baidu"),
                    safeLink(text(item, "url", 2048), "baike.baidu.com", "https://top.baidu.com/board?tab=movie")));
        }
        return snapshot(TrendingBoard.BAIDU_MOVIE, "", "源更新时间未提供", "电影搜索热度参考 · 统计窗口未披露", entries, now, previous);
    }

    private static TrendingSnapshot snapshot(TrendingBoard board, String date, String update, String period,
                                             List<TrendingEntry> entries, long now, TrendingSnapshot previous) {
        if (entries.isEmpty()) throw invalid();
        StringBuilder stable = new StringBuilder(board.name()).append('|').append(date);
        for (TrendingEntry entry : entries) stable.append('|').append(entry.id).append('|').append(entry.rank)
                .append('|').append(entry.title).append('|').append(entry.score);
        String fingerprint = fingerprint(stable.toString());
        long firstSeen = TrendingFreshness.firstSeen(previous, fingerprint, now);
        long lastChanged = previous != null && fingerprint.equals(previous.fingerprint) ? previous.lastChangedAt : now;
        return new TrendingSnapshot(board, date, update, period, now, firstSeen, lastChanged, fingerprint, entries);
    }

    private static JsonObject object(String json) {
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonObject()) throw invalid();
        return element.getAsJsonObject();
    }

    private static String text(JsonObject object, String key, int maxLength) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return "";
        String value = object.get(key).getAsString().trim();
        return value.length() <= maxLength && !value.matches("(?s).*\\p{Cntrl}.*") ? value : "";
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static String safeLink(String value, String host, String base) {
        try {
            URI uri = URI.create(base).resolve(value);
            if ("https".equalsIgnoreCase(uri.getScheme()) && host.equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)) return uri.toString();
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    static String imageUrl(String value, String provider) {
        try {
            URI uri = URI.create(value.startsWith("//") ? "https:" + value : value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean allowed = "hongguo".equals(provider)
                    ? host.endsWith(".byteimg.com") || host.endsWith(".fqnovelpic.com") || host.endsWith(".fqnovelstatic.com")
                    : host.endsWith(".bdstatic.com") || host.endsWith(".baidu.com");
            if (allowed && "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)) return uri.toString();
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("无法校验榜单内容");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("榜单格式或类别无效");
    }
}
