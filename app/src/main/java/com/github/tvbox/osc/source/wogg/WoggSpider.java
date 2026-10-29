package com.github.tvbox.osc.source.wogg;

import android.content.Context;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.catalog.SourceDescriptor;
import com.github.tvbox.osc.cloud.Cancellation;
import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.CloudReference;
import com.github.tvbox.osc.cloud.CloudRuntime;
import com.github.tvbox.osc.source.AdapterRuntime;
import com.github.tvbox.osc.source.RequestContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.HttpUrl;

/** 编入APK的玩偶适配。初始化不联网、不触发账号界面。 */
public final class WoggSpider extends Spider {
    private final SourceDescriptor descriptor;
    private final CloudRuntime cloud;
    private final Set<String> hosts = new HashSet<>();
    private volatile String base;
    private volatile Map<String, String> types = new LinkedHashMap<>();

    public WoggSpider(Context context, SourceDescriptor descriptor) {
        this.descriptor = descriptor; this.cloud = CloudRuntime.get(context); this.siteKey = descriptor.getId();
        base = descriptor.getPrimaryEndpoint();
        for (String value : descriptor.getSites()) { HttpUrl url = HttpUrl.parse(value); if (url != null && "https".equals(url.scheme())) hosts.add(url.host()); }
    }

    private Cancellation cancellation() {
        Cancellation cancellation = new Cancellation(); RequestContext context = AdapterRuntime.currentContext();
        if (context != null) context.onCancel(cancellation::cancel);
        return cancellation;
    }

    private String get(String path, Cancellation cancellation) throws CloudException {
        HttpUrl origin = HttpUrl.parse(base), target = origin == null ? null : origin.resolve(path);
        if (target == null) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        return SiteHttp.get(target.toString(), hosts, cancellation);
    }

    @Override public String homeContent(boolean filter) {
        try {
            Cancellation cancellation = cancellation(); String html = ""; CloudException last = null;
            for (String endpoint : descriptor.getSites()) {
                try {
                    String candidate = SiteHttp.get(endpoint, hosts, cancellation);
                    Map<String, String> found = WoggParser.categories(candidate, endpoint);
                    if (found.isEmpty()) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
                    base = endpoint; html = candidate; types = found; last = null; break;
                } catch (CloudException failure) { last = failure; if (cancellation.isCancelled()) throw failure; }
            }
            if (last != null) throw last;
            JsonObject output = new JsonObject(); JsonArray categories = new JsonArray();
            for (Map.Entry<String, String> type : types.entrySet()) { JsonObject row = new JsonObject(); row.addProperty("type_id", type.getKey()); row.addProperty("type_name", type.getValue()); categories.add(row); }
            output.add("class", categories); output.add("list", WoggParser.videos(html, base, false, ""));
            return output.toString();
        } catch (CloudException failure) { return NativeJson.failure(failure, null); }
    }

    @Override public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (!tid.matches("[0-9]{1,5}") || !pg.matches("[0-9]{1,4}")) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
            int page = Integer.parseInt(pg); if (page < 1 || page > 200) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
            Cancellation cancellation = cancellation();
            if (types.isEmpty()) homeContent(false);
            String html = get("/index.php/vodshow/" + tid + "--------" + page + "---.html", cancellation);
            JsonArray items = WoggParser.videos(html, base, false, types.containsKey(tid) ? types.get(tid) : "");
            boolean more = org.jsoup.Jsoup.parse(html).select("a.page-link:contains(下一页),a[href]:contains(下一页)").size() > 0;
            return NativeJson.list(items, page, more);
        } catch (Exception failure) { return NativeJson.failure(failure instanceof CloudException ? (CloudException) failure : new CloudException(CloudException.Code.INVALID_RESPONSE, null), null); }
    }

    @Override public String searchContent(String key, boolean quick) { return searchContent(key, quick, "1"); }
    @Override public String searchContent(String key, boolean quick, String pg) {
        try {
            if (key == null || key.length() > 128 || !pg.matches("[0-9]{1,3}")) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
            String html = get("/index.php/vodsearch/" + URLEncoder.encode(key, "UTF-8") + "----------" + pg + "---.html", cancellation());
            return NativeJson.list(WoggParser.videos(html, base, true, ""), Integer.parseInt(pg), org.jsoup.Jsoup.parse(html).select("a[href]:contains(下一页)").size() > 0);
        } catch (Exception failure) { return NativeJson.failure(failure instanceof CloudException ? (CloudException) failure : new CloudException(CloudException.Code.INVALID_RESPONSE, null), null); }
    }

    @Override public String detailContent(List<String> ids) {
        try {
            if (ids == null || ids.size() != 1 || WoggParser.detailPath(HttpUrl.parse(base).resolve(ids.get(0)).toString(), base).isEmpty()) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
            JsonArray rows = new JsonArray(); rows.add(WoggParser.detail(get(ids.get(0), cancellation()), base, ids.get(0), descriptor.getId()));
            return NativeJson.list(rows, 1, false);
        } catch (Exception failure) { return NativeJson.failure(failure instanceof CloudException ? (CloudException) failure : new CloudException(CloudException.Code.INVALID_RESPONSE, null), null); }
    }

    @Override public String playerContent(String flag, String id, List<String> vipFlags) {
        CloudReference reference = null;
        try {
            reference = CloudReference.decode(id);
            RequestContext context = AdapterRuntime.currentContext();
            if (context == null || !context.matches(descriptor) || context.getPlaybackGeneration() <= 0 || !descriptor.getId().equals(reference.sourceId))
                throw new CloudException(CloudException.Code.CANCELLED, reference.cloudProvider());
            // 自动详情预览不得转存文件或开启授权，正式选择播放/续播由界面传显式意图。
            if (reference.isFile() && !context.isExplicitUserAction()) {
                JsonObject output = new JsonObject(); output.addProperty("parse", 0); output.addProperty("url", "");
                output.addProperty("cloud_action", "user_play_required"); output.addProperty("provider", reference.provider);
                output.addProperty("cloud_ref", reference.encode()); output.addProperty("message", "请选择播放以继续"); return output.toString();
            }
            return cloud.playerResult(reference, descriptor.getAdapterId(), context.getPlaybackGeneration(), cancellation()).toString();
        } catch (CloudException failure) { return NativeJson.failure(failure, reference); }
    }
}
