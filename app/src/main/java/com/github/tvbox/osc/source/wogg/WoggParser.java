package com.github.tvbox.osc.source.wogg;

import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.CloudReference;
import com.github.tvbox.osc.util.ContentPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTML协议参考mymine/CatVodSpider Wogg（zhixc）；本项目独立解析与错误处理。 */
public final class WoggParser {
    private static final Pattern TYPE = Pattern.compile("(?:/index\\.php)?/vodtype/([0-9]{1,5})\\.html");
    private static final Pattern DETAIL = Pattern.compile("(?:/index\\.php)?/voddetail/[0-9]{1,12}\\.html");
    private WoggParser() { }

    public static Map<String, String> categories(String html, String base) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Element link : Jsoup.parse(html, base).select("a.nav-link[href]")) {
            Matcher match = TYPE.matcher(link.attr("href"));
            String type = normalizeType(link.text());
            if (match.find() && !type.isEmpty() && ContentPolicy.isAllowedCategory(type)) result.put(match.group(1), type);
        }
        return result;
    }

    public static JsonArray videos(String html, String base, boolean search, String knownType) {
        Document document = Jsoup.parse(html, base);
        JsonArray videos = new JsonArray(); Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Element item : document.select(search ? ".module-search-item" : ".module-item")) {
            Element anchor = item.selectFirst(search ? "a.video-serial[href]" : ".video-name a[href]");
            if (anchor == null) continue;
            String id = detailPath(anchor.absUrl("href"), base);
            String name = search ? anchor.attr("title").trim() : anchor.text().trim();
            if (name.isEmpty()) name = anchor.text().trim();
            if (id.isEmpty() || name.isEmpty() || name.length() > 256 || seen.put(id, true) != null) continue;
            Element image = item.selectFirst(".module-item-pic img");
            String picture = image == null ? "" : image.hasAttr("data-src") ? image.absUrl("data-src") : image.absUrl("src");
            if (!(picture.startsWith("https://") || picture.startsWith("http://"))) picture = "";
            videos.add(NativeJson.video(id, name, picture, item.select(search ? ".video-tag-icon" : ".module-item-text").text(), knownType));
            if (videos.size() >= 100) break;
        }
        return videos;
    }

    public static JsonObject detail(String html, String base, String id, String sourceId) throws CloudException {
        Document document = Jsoup.parse(html, base);
        String name = document.select(".video-info-header .page-title").text().trim();
        if (name.isEmpty()) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        Element picture = document.selectFirst(".module-item-pic img");
        JsonObject result = NativeJson.video(id, name, picture == null ? "" : picture.absUrl("data-src"), "", "");
        result.addProperty("vod_year", ""); result.addProperty("vod_area", "");
        for (Element item : document.select(".video-info-item")) {
            Element label = item.previousElementSibling(); if (label == null) continue;
            String title = label.text();
            if (title.contains("导演")) result.addProperty("vod_director", item.text());
            else if (title.contains("主演")) result.addProperty("vod_actor", item.text());
            else if (title.contains("年代") || title.contains("年份")) {
                Matcher year = Pattern.compile("(?:19|20)[0-9]{2}").matcher(item.text()); if (year.find()) result.addProperty("vod_year", year.group());
            } else if (title.contains("地区")) result.addProperty("vod_area", item.text());
            else if (title.contains("类型")) result.addProperty("type_name", normalizeType(item.text()));
            else if (title.contains("备注")) result.addProperty("vod_remarks", item.text());
            else if (title.contains("剧情")) result.addProperty("vod_content", item.text());
        }
        if (!ContentPolicy.isAllowedCategory(result.get("type_name").getAsString())) throw new CloudException(CloudException.Code.UNSUPPORTED, null);
        StringBuilder flags = new StringBuilder(), lines = new StringBuilder(); int index = 0;
        for (Element row : document.select("[data-clipboard-text]")) {
            CloudReference reference;
            try { reference = CloudReference.fromShareUrl(row.attr("data-clipboard-text"), sourceId, id); }
            catch (CloudException unsupported) { continue; }
            if (index++ > 0) { flags.append("$$$"); lines.append("$$$"); }
            flags.append(reference.cloudProvider().displayName).append("·线路").append(index);
            lines.append("选择文件与集数$").append(reference.encode());
        }
        if (index == 0) result.addProperty("vod_remarks", "当前分享类型暂未支持，可尝试其他来源");
        result.addProperty("vod_play_from", flags.toString()); result.addProperty("vod_play_url", lines.toString());
        return result;
    }

    public static String detailPath(String value, String base) {
        try {
            URI uri = URI.create(value), origin = URI.create(base);
            String path = uri.getPath();
            return uri.getHost() != null && uri.getHost().equalsIgnoreCase(origin.getHost()) && path != null && DETAIL.matcher(path).matches() ? path : "";
        } catch (RuntimeException ignored) { return ""; }
    }

    public static String normalizeType(String value) {
        if (value == null) return "";
        String text = value.trim();
        if ("剧集".equals(text)) return "电视剧";
        if ("短漫剧".equals(text)) return "短剧";
        if ("臻彩".equals(text) || text.contains("原盘") || text.equalsIgnoreCase("4K")) return "";
        return text;
    }
}
