package com.github.tvbox.osc.source.dm84;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.catalog.SourceDescriptor;
import com.github.tvbox.osc.cloud.Cancellation;
import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.playback.MediaLease;
import com.github.tvbox.osc.playback.ProxyRouter;
import com.github.tvbox.osc.source.AdapterRuntime;
import com.github.tvbox.osc.source.RequestContext;
import com.github.tvbox.osc.source.wogg.NativeJson;
import com.github.tvbox.osc.source.wogg.SiteHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import okhttp3.HttpUrl;

/** 动漫巴士本地适配，站点只提供补充候选，质量仍需实际播放验证。 */
public final class Dm84Spider extends Spider {
    private final Context context;
    private final SourceDescriptor descriptor;
    private final String base;
    private final Set<String> hosts = new HashSet<>();
    private volatile Map<String, String> types = Collections.emptyMap();
    private final Map<String, Map<Integer, String>> searchPages = new LinkedHashMap<String, Map<Integer, String>>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Map<Integer, String>> entry) { return size() > 32; }
    };
    public Dm84Spider(Context context, SourceDescriptor descriptor) {
        this.context = context.getApplicationContext(); this.descriptor = descriptor; siteKey = descriptor.getId(); base = descriptor.getPrimaryEndpoint();
        for (String site : descriptor.getSites()) { HttpUrl url = HttpUrl.parse(site); if (url != null && "https".equals(url.scheme())) hosts.add(url.host()); }
    }
    private Cancellation cancellation() { Cancellation value = new Cancellation(); RequestContext request = AdapterRuntime.currentContext(); if (request != null) request.onCancel(value::cancel); return value; }
    private String get(String path, Cancellation cancellation) throws CloudException {
        HttpUrl origin = HttpUrl.parse(base), url = origin == null ? null : origin.resolve(path);
        if (url == null) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        return SiteHttp.get(url.toString(), hosts, cancellation);
    }
    @Override public String homeContent(boolean filter) {
        try {
            String html = get("/", cancellation()); types = Dm84Parser.categories(html, base);
            if (types.isEmpty()) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
            JsonObject output = new JsonObject(); JsonArray classes = new JsonArray();
            for (Map.Entry<String, String> item : types.entrySet()) { JsonObject type = new JsonObject(); type.addProperty("type_id", item.getKey()); type.addProperty("type_name", item.getValue()); classes.add(type); }
            output.add("class", classes); output.add("list", Dm84Parser.videos(html, base, "")); return output.toString();
        } catch (CloudException failure) { return NativeJson.failure(failure, null); }
    }
    @Override public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (!tid.matches("[1-4]") || !pg.matches("[0-9]{1,3}") || Integer.parseInt(pg) < 1 || Integer.parseInt(pg) > 200) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
            if (types.isEmpty()) homeContent(false);
            String html = get("/show-" + tid + "--time----" + pg + ".html", cancellation());
            return NativeJson.list(Dm84Parser.videos(html, base, types.containsKey(tid) ? types.get(tid) : ""), Integer.parseInt(pg), Dm84Parser.hasNext(html, base, Integer.parseInt(pg)));
        } catch (Exception failure) { return failed(failure); }
    }
    @Override public String searchContent(String key, boolean quick) { return searchContent(key, quick, "1"); }
    @Override public String searchContent(String key, boolean quick, String pg) {
        try {
            if (key == null || key.length() > 128 || !pg.matches("[0-9]{1,3}") || Integer.parseInt(pg) < 1 || Integer.parseInt(pg) > 200) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
            int page = Integer.parseInt(pg); String path;
            synchronized (searchPages) {
                if (page == 1) { Map<Integer, String> paths = new HashMap<>(); paths.put(1, "/s----------.html?wd=" + URLEncoder.encode(key, "UTF-8")); searchPages.put(key, paths); }
                Map<Integer, String> paths = searchPages.get(key); path = paths == null ? null : paths.get(page);
            }
            if (path == null) throw new CloudException(CloudException.Code.NO_MATCH, null);
            String html = get(path, cancellation()), nextPath = "";
            for (org.jsoup.nodes.Element link : org.jsoup.Jsoup.parse(html, base).select("a[href]")) {
                if (!link.text().contains("下一页")) continue;
                HttpUrl next = HttpUrl.parse(link.absUrl("href")), origin = HttpUrl.parse(base);
                if (next != null && origin != null && "https".equals(next.scheme()) && next.host().equals(origin.host())
                        && next.port() == origin.port() && next.username().isEmpty() && next.password().isEmpty()
                        && next.encodedPath().matches("/s[^/]{0,1024}\\.html") && (next.queryParameter("wd") == null || key.equals(next.queryParameter("wd")))) { nextPath = next.toString(); break; }
            }
            synchronized (searchPages) { Map<Integer, String> paths = searchPages.get(key); if (paths != null && !nextPath.isEmpty()) paths.put(page + 1, nextPath); }
            return NativeJson.list(Dm84Parser.videos(html, base, ""), page, !nextPath.isEmpty());
        } catch (Exception failure) { return failed(failure); }
    }
    @Override public String detailContent(List<String> ids) {
        try {
            HttpUrl origin = HttpUrl.parse(base), target = ids != null && ids.size() == 1 && origin != null ? origin.resolve(ids.get(0)) : null;
            String path = target == null ? "" : Dm84Parser.path(target.toString(), base, false);
            if (path.isEmpty()) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
            JsonArray list = new JsonArray(); list.add(Dm84Parser.detail(get(path, cancellation()), base, path)); return NativeJson.list(list, 1, false);
        } catch (Exception failure) { return failed(failure); }
    }
    @Override public String playerContent(String flag, String id, List<String> vipFlags) {
        MediaLease published = null;
        try {
            RequestContext request = AdapterRuntime.currentContext();
            if (request == null || !request.matches(descriptor) || request.getPlaybackGeneration() <= 0) throw new CloudException(CloudException.Code.CANCELLED, null);
            HttpUrl origin = HttpUrl.parse(base), target = origin == null ? null : origin.resolve(id);
            if (target == null || Dm84Parser.path(target.toString(), base, true).isEmpty()) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
            Cancellation cancel = cancellation(); Dm84Resolver.Media media = new Dm84Resolver().resolve(get(id, cancel), target.toString(), cancel); cancel.check();
            Map<String, String> headers = new HashMap<>(); headers.put("User-Agent", "Mozilla/5.0"); headers.put("Referer", "https://hhjx.hhplayer.com/");
            MediaLease lease = ProxyRouter.get(context).publishAnonymous(descriptor.getId(), descriptor.getAdapterId(), request.getPlaybackGeneration(), media.url, headers, media.hls);
            published = lease; ProxyRouter.get(context).bindPendingDelivery(lease.getLocalUrl(), cancel); cancel.check();
            JsonObject output = new JsonObject(); output.addProperty("parse", 0); output.addProperty("url", lease.getLocalUrl()); output.add("header", new JsonObject()); return output.toString();
        } catch (Exception failure) {
            if (published != null) try { ProxyRouter.get(context).release(published.getLocalUrl()); } catch (CloudException ignored) { }
            return failed(failure);
        }
    }
    private static String failed(Exception failure) { return NativeJson.failure(failure instanceof CloudException ? (CloudException) failure : new CloudException(CloudException.Code.INVALID_RESPONSE, null), null); }
}
