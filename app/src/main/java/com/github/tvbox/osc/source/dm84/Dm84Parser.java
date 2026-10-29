package com.github.tvbox.osc.source.dm84;

import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.source.wogg.NativeJson;
import com.github.tvbox.osc.util.ContentPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 仅解析实见DOM，不从中文片名或栏目猜测国家与字幕。协议参考FongMi的Dm84适配。 */
public final class Dm84Parser {
    private Dm84Parser() { }
    public static Map<String, String> categories(String html, String base) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Element link : Jsoup.parse(html, base).select("ul.nav_row>li>a[href]")) {
            Matcher id = Pattern.compile("/list-([1-4])\\.html").matcher(link.attr("href"));
            if (id.matches()) {
                String name = link.text().trim(), normalized = normalize(name);
                if (!normalized.isEmpty()) result.put(id.group(1), normalized);
            }
        }
        return result;
    }
    static String normalize(String name) {
        if ("国漫".equals(name)) return "国产动漫";
        if ("日漫".equals(name)) return "日韩动漫";
        if ("欧美动漫".equals(name)) return name;
        if ("电影".equals(name)) return "动画片";
        return name;
    }
    public static JsonArray videos(String html, String base, String knownType) {
        JsonArray result = new JsonArray(); Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Element item : Jsoup.parse(html, base).select("div.item")) {
            Element link = item.selectFirst("a.title[href]"); if (link == null) continue;
            String id = path(link.absUrl("href"), base, false), title = link.text().trim();
            if (id.isEmpty() || title.isEmpty() || title.length() > 256 || seen.put(id, true) != null) continue;
            Element cover = item.selectFirst("a.cover[data-bg]");
            String image = cover == null ? "" : cover.absUrl("data-bg");
            if (!image.startsWith("https://") && !image.startsWith("http://")) image = "";
            result.add(NativeJson.video(id, title, image, item.select("span.desc").text(), knownType));
            if (result.size() >= 100) break;
        }
        return result;
    }
    public static JsonObject detail(String html, String base, String id) throws CloudException {
        Document doc = Jsoup.parse(html, base); String name = doc.select("h1.v_title").text();
        if (name.isEmpty()) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        String genre = meta(doc, "og:video:class");
        if (!ContentPolicy.isAllowedCategory("动漫 " + genre)) throw new CloudException(CloudException.Code.UNSUPPORTED, null);
        // class元数据在实站代表“奇幻/玄幻”等题材；真正影视类型取当前导航的明确栏目。
        Element currentCategory = doc.selectFirst("ul.nav_row>li.current>a[href]");
        String type = currentCategory == null ? normalize(genre) : normalize(currentCategory.text());
        if (!ContentPolicy.isAllowedCategory(type)) type = "";
        JsonObject result = NativeJson.video(id, name, meta(doc, "og:image"), doc.select("p.v_desc span.desc").text(), type);
        result.addProperty("vod_area", meta(doc, "og:video:area")); result.addProperty("vod_year", meta(doc, "og:video:release_date"));
        result.addProperty("vod_actor", meta(doc, "og:video:actor")); result.addProperty("vod_director", meta(doc, "og:video:director"));
        result.addProperty("vod_content", meta(doc, "og:description"));
        result.addProperty("vod_tag", genre);
        Elements labels = doc.select("ul.tab_control>li"), lists = doc.select("ul.play_list");
        StringBuilder flags = new StringBuilder(), rows = new StringBuilder();
        for (int i = 0; i < lists.size() && i < 12; i++) {
            StringBuilder episodes = new StringBuilder();
            for (Element link : lists.get(i).select("a[href]")) {
                String episodePath = path(link.absUrl("href"), base, true), episodeName = link.text().trim();
                if (episodePath.isEmpty() || episodeName.isEmpty()) continue;
                if (episodes.length() > 0) episodes.append('#');
                episodes.append(NativeJson.label(episodeName)).append('$').append(episodePath);
                if (episodes.length() > 262144) break;
            }
            if (episodes.length() == 0) continue;
            if (flags.length() > 0) { flags.append("$$$"); rows.append("$$$"); }
            flags.append(NativeJson.label(i < labels.size() ? labels.get(i).text() : "线路" + (i + 1))); rows.append(episodes);
        }
        result.addProperty("vod_play_from", flags.toString()); result.addProperty("vod_play_url", rows.toString());
        return result;
    }
    public static boolean hasNext(String html, String base, int page) {
        for (Element link : Jsoup.parse(html, base).select("a[href]")) {
            String href = link.attr("href");
            if ((link.text().contains("下一页") || link.text().trim().equals(">")) && !href.isEmpty() && !href.equals("#")) return true;
            if (href.matches("/show-[1-4]--time----" + (page + 1) + "\\.html")) return true;
        }
        return false;
    }
    public static String path(String value, String base, boolean play) {
        try {
            URI uri = URI.create(value), origin = URI.create(base);
            String path = uri.getPath();
            return uri.getHost() != null && uri.getHost().equalsIgnoreCase(origin.getHost()) && uri.getRawQuery() == null
                    && uri.getUserInfo() == null && path != null && path.matches(play ? "/p/[0-9]{1,12}-[0-9]{1,4}-[0-9]{1,6}\\.html" : "/v/[0-9]{1,12}\\.html") ? path : "";
        } catch (RuntimeException ignored) { return ""; }
    }
    private static String meta(Document doc, String key) {
        Element field = doc.selectFirst("meta[property=\"" + key + "\"],meta[name=\"" + key + "\"]");
        return field == null ? "" : field.attr("content").trim();
    }
}
