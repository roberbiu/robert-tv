package com.github.tvbox.osc.browse;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.catalog.SourceDescriptor;
import com.github.tvbox.osc.catalog.SourceRegistry;
import com.github.tvbox.osc.catalog.SourceSnapshot;
import com.github.tvbox.osc.trending.PublicMetadataHttp;
import com.github.tvbox.osc.source.AdapterRegistry;
import com.github.tvbox.osc.source.AdapterRuntime;
import com.github.tvbox.osc.source.RequestContext;
import com.github.tvbox.osc.source.SourceResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.HttpUrl;

/** 来源×真实叶子×国家独立分页；一轮有界，空页不触发全库扫描。 */
public final class BrowseRepository {
    public static final int PAGE_TARGET = 20;
    public static final int MAX_REQUESTS_PER_PAGE = 6;
    public static final long PAGE_BUDGET_MS = 18000;
    private static final long REQUEST_BUDGET_MS = 6000;
    private static final int MAX_LEAF_PAGE = 200;
    private static final ExecutorService LOCAL_READS = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(6), new ThreadPoolExecutor.AbortPolicy());

    public interface Listener {
        void onPage(Page result);
    }

    public static final class Page {
        public final List<Movie.Video> videos;
        public final boolean hasMore;
        public final boolean partialFailure;
        public final long revision;
        public final String message;

        Page(List<Movie.Video> videos, boolean hasMore, boolean partialFailure, long revision, String message) {
            this.videos = Collections.unmodifiableList(new ArrayList<>(videos));
            this.hasMore = hasMore;
            this.partialFailure = partialFailure;
            this.revision = revision;
            this.message = message;
        }
    }

    private static final class Cursor {
        final SourceDescriptor source;
        final String id;
        final String name;
        int page = 1;
        boolean exhausted;
        long retryAfter;
        int invalidPages;
        final List<Movie.Video> pending = new ArrayList<>();

        Cursor(SourceDescriptor source, String id, String name) {
            this.source = source;
            this.id = id;
            this.name = name;
        }

        Cursor copy() {
            Cursor copy = new Cursor(source, id, name);
            copy.page = page;
            copy.exhausted = exhausted;
            copy.retryAfter = retryAfter;
            copy.invalidPages = invalidPages;
            copy.pending.addAll(pending);
            return copy;
        }
    }

    private static final class State {
        final ContentKind kind;
        final String country;
        final SourceSnapshot snapshot;
        final List<Cursor> cursors = new ArrayList<>();
        final Set<String> initialized = new HashSet<>();
        final Map<String, Long> retryAfter = new HashMap<>();
        final Set<String> seen = new HashSet<>();
        FairPageSchedule schedule = new FairPageSchedule();
        int sourceStart;
        String lastCursor = "";

        State(ContentKind kind, String country, SourceSnapshot snapshot) {
            this.kind = kind;
            this.country = country;
            this.snapshot = snapshot;
        }

        State copy() {
            State copy = new State(kind, country, snapshot);
            for (Cursor cursor : cursors) copy.cursors.add(cursor.copy());
            copy.initialized.addAll(initialized);
            copy.retryAfter.putAll(retryAfter);
            copy.seen.addAll(seen);
            copy.schedule = schedule.copy();
            copy.sourceStart = sourceStart;
            copy.lastCursor = lastCursor;
            return copy;
        }
    }

    private static final class ParsedPage {
        final List<Movie.Video> videos;
        final boolean hasMore;

        ParsedPage(List<Movie.Video> videos, boolean hasMore) {
            this.videos = videos;
            this.hasMore = hasMore;
        }
    }

    private final Context context;
    private final PublicMetadataHttp http = new PublicMetadataHttp();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final Set<RequestContext> localRequests = Collections.newSetFromMap(new ConcurrentHashMap<RequestContext, Boolean>());
    private volatile State state;
    private volatile boolean closed;

    public BrowseRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public void load(ContentKind kind, String country, boolean refresh, Listener listener) {
        if (closed) return;
        final String requestedCountry = country == null ? "" : country;
        final int ticket = generation.incrementAndGet();
        cancelNetwork();
        worker.execute(() -> {
            if (cancelled(ticket)) return;
            SourceSnapshot snapshot = SourceRegistry.get(context).snapshot();
            State committed = state;
            State working = committed == null || refresh || committed.kind != kind || !committed.country.equals(requestedCountry)
                    || committed.snapshot.getRevision() != snapshot.getRevision()
                    ? new State(kind, requestedCountry, snapshot) : committed.copy();
            long deadline = SystemClock.elapsedRealtime() + PAGE_BUDGET_MS;
            int requests = 0;
            boolean partial = false;
            List<SourceDescriptor> sources = new ArrayList<>(snapshot.getSources());
            Collections.sort(sources, (left, right) -> right.getPriority() - left.getPriority());
            int checked = 0;
            int initializedThisRound = 0;
            boolean leavesChanged = false;
            while (!sources.isEmpty() && checked < sources.size() && initializedThisRound < 3
                    && requests < MAX_REQUESTS_PER_PAGE && SystemClock.elapsedRealtime() < deadline && !cancelled(ticket)) {
                int sourceIndex = working.sourceStart % sources.size();
                working.sourceStart = (sourceIndex + 1) % sources.size();
                checked++;
                SourceDescriptor source = sources.get(sourceIndex);
                if (working.initialized.contains(source.getId())) continue;
                if (!requestedCountry.isEmpty() && !source.supportsServerCountryFilter()) {
                    working.initialized.add(source.getId());
                    continue;
                }
                Long retry = working.retryAfter.get(source.getId());
                if (retry != null && retry > System.currentTimeMillis()) continue;
                requests++;
                initializedThisRound++;
                try {
                    List<Cursor> leaves = leaves(source, readCategories(source, snapshot.getRevision(), ticket, deadline), kind);
                    working.cursors.addAll(leaves);
                    leavesChanged |= !leaves.isEmpty();
                    working.initialized.add(source.getId());
                    working.retryAfter.remove(source.getId());
                } catch (Exception ignored) {
                    partial = true;
                    working.retryAfter.put(source.getId(), System.currentTimeMillis() + 15000);
                }
            }
            if (leavesChanged) interleave(working);
            List<Movie.Video> result = new ArrayList<>();
            while (requests < MAX_REQUESTS_PER_PAGE && result.size() < PAGE_TARGET
                    && SystemClock.elapsedRealtime() < deadline && !cancelled(ticket)) {
                List<Boolean> unavailable = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (Cursor cursor : working.cursors) unavailable.add((cursor.exhausted && cursor.pending.isEmpty()) || cursor.retryAfter > now);
                List<Integer> next = working.schedule.take(unavailable, 1);
                if (next.isEmpty()) break;
                Cursor cursor = working.cursors.get(next.get(0));
                working.lastCursor = cursor.source.getId() + '\u0000' + cursor.id;
                if (!cursor.pending.isEmpty()) {
                    drain(cursor, working, result);
                    continue;
                }
                requests++;
                try {
                    ParsedPage page = readPage(cursor, requestedCountry, snapshot.getRevision(), ticket, deadline);
                    int accepted = 0;
                    for (Movie.Video video : page.videos) {
                        if (!BrowseCategoryPolicy.accepts(kind, video.type, video.area, requestedCountry)) continue;
                        accepted++;
                        cursor.pending.add(video);
                    }
                    drain(cursor, working, result);
                    cursor.invalidPages = !page.videos.isEmpty() && accepted == 0 ? cursor.invalidPages + 1 : 0;
                    if (cursor.invalidPages > 0) partial = true;
                    cursor.page++;
                    cursor.exhausted = !page.hasMore || cursor.page > MAX_LEAF_PAGE || cursor.invalidPages >= 3;
                    cursor.retryAfter = 0;
                } catch (Exception ignored) {
                    partial = true;
                    cursor.retryAfter = System.currentTimeMillis() + 15000;
                }
            }
            boolean more = working.initialized.size() < sources.size();
            for (Cursor cursor : working.cursors) if (!cursor.exhausted || !cursor.pending.isEmpty()) more = true;
            final boolean hasMore = more;
            final boolean partialFailure = partial || !working.retryAfter.isEmpty();
            final String message = result.isEmpty()
                    ? hasMore ? "当前未取得匹配内容，可继续查找或搜索片名" : "该来源组合暂未提供符合条件的内容，可搜索片名"
                    : partialFailure ? "部分来源暂不可用，已显示取得的匹配内容" : "";
            if (cancelled(ticket) || SourceRegistry.get(context).snapshot().getRevision() != snapshot.getRevision()) return;
            main.post(() -> {
                if (!cancelled(ticket) && SourceRegistry.get(context).snapshot().getRevision() == snapshot.getRevision()) {
                    // 游标和已见集合随被UI接收的结果一起提交；被取消的晚到页不跳过内容。
                    state = working;
                    listener.onPage(new Page(result, hasMore, partialFailure, snapshot.getRevision(), message));
                }
            });
        });
    }

    private void drain(Cursor cursor, State working, List<Movie.Video> output) {
        while (!cursor.pending.isEmpty() && output.size() < PAGE_TARGET) {
            Movie.Video video = cursor.pending.remove(0);
            String identity = BrowseIdentity.key(working.kind, cursor.source.getId(), video.id, video.name, video.year);
            if (working.seen.add(identity)) output.add(video);
        }
    }

    private void interleave(State working) {
        Map<String, List<Cursor>> grouped = new LinkedHashMap<>();
        int longest = 0;
        for (Cursor cursor : working.cursors) {
            List<Cursor> leaves = grouped.get(cursor.source.getId());
            if (leaves == null) {
                leaves = new ArrayList<>();
                grouped.put(cursor.source.getId(), leaves);
            }
            leaves.add(cursor);
            longest = Math.max(longest, leaves.size());
        }
        List<Cursor> ordered = new ArrayList<>();
        for (int index = 0; index < longest; index++) {
            for (List<Cursor> leaves : grouped.values()) if (index < leaves.size()) ordered.add(leaves.get(index));
        }
        working.cursors.clear();
        working.cursors.addAll(ordered);
        for (int index = 0; index < ordered.size(); index++) {
            Cursor cursor = ordered.get(index);
            if (working.lastCursor.equals(cursor.source.getId() + '\u0000' + cursor.id)) {
                working.schedule.startAt((index + 1) % ordered.size());
                return;
            }
        }
    }

    private JsonObject readCategories(SourceDescriptor source, long revision, int ticket, long deadline) throws Exception {
        SourceBean bean = source.getSourceBean();
        if (bean.getType() == 1) {
            HttpUrl parsed = HttpUrl.parse(bean.getApi());
            if (parsed == null) throw new IOException("分类地址无效");
            String json = http.get(parsed.newBuilder().setQueryParameter("ac", "list").build().toString(),
                    () -> cancelled(ticket), remaining(deadline));
            return JsonParser.parseString(json).getAsJsonObject();
        }
        if (bean.getType() == 3 && bean.getApi().startsWith("builtin:")) {
            String json = local(source, revision, () -> AdapterRegistry.get().getSpider(context, source).homeContent(true), ticket, deadline);
            return JsonParser.parseString(json).getAsJsonObject();
        }
        throw new IOException("此来源不支持分类浏览");
    }

    private List<Cursor> leaves(SourceDescriptor source, JsonObject response, ContentKind kind) {
        List<Cursor> leaves = new ArrayList<>();
        JsonArray classes = response.getAsJsonArray("class");
        if (classes == null || classes.size() > 128) throw new IllegalArgumentException("分类格式无效");
        Set<String> ids = new HashSet<>();
        boolean cms = "cms".equals(source.getAdapterId());
        for (JsonElement element : classes) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = value(item, "type_id");
            String name = value(item, "type_name");
            if (!id.matches("[a-zA-Z0-9_-]{1,40}") || !ids.add(id)) continue;
            if (cms && !BrowseCategoryPolicy.isCmsLeaf(id, name)) continue;
            if (BrowseCategoryPolicy.kindOf(name) != kind) continue;
            if (!source.getCategories().isEmpty() && !source.getCategories().contains(name)) {
                boolean allowedLocalType = false;
                if (!cms) for (String approved : source.getCategories()) {
                    if (BrowseCategoryPolicy.kindOf(approved) == kind) allowedLocalType = true;
                }
                if (!allowedLocalType) continue;
            }
            leaves.add(new Cursor(source, id, name));
        }
        return leaves;
    }

    private ParsedPage readPage(Cursor cursor, String country, long revision, int ticket, long deadline) throws Exception {
        SourceBean bean = cursor.source.getSourceBean();
        String json;
        if (bean.getType() == 1) {
            HttpUrl parsed = HttpUrl.parse(bean.getApi());
            if (parsed == null) throw new IOException("来源地址无效");
            HttpUrl.Builder request = parsed.newBuilder().setQueryParameter("ac", "detail")
                    .setQueryParameter("t", cursor.id).setQueryParameter("pg", String.valueOf(cursor.page));
            if (!country.isEmpty()) request.setQueryParameter("area", country);
            json = http.get(request.build().toString(), () -> cancelled(ticket), remaining(deadline));
        } else {
            json = local(cursor.source, revision, () -> {
                Spider spider = AdapterRegistry.get().getSpider(context, cursor.source);
                return spider.categoryContent(cursor.id, String.valueOf(cursor.page), true, new HashMap<>());
            }, ticket, deadline);
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("code")) {
            int code = number(root, "code", -1);
            if (code != 0 && code != 1) throw new IOException("来源列表暂不可用");
        }
        if (!root.has("list") || !root.get("list").isJsonArray()) throw new IOException("列表格式无效");
        int responsePage = number(root, "page", cursor.page);
        if (responsePage != cursor.page) throw new IOException("来源未返回请求的分页");
        JsonArray rows = root.getAsJsonArray("list");
        if (rows.size() > 200) throw new IOException("列表过大");
        List<Movie.Video> videos = new ArrayList<>();
        for (JsonElement row : rows) {
            try {
                if (!row.isJsonObject()) continue;
                JsonObject item = row.getAsJsonObject();
                Movie.Video video = new Movie.Video();
                video.id = value(item, "vod_id", 256);
                video.name = value(item, "vod_name", 160);
                if (video.id.isEmpty() || video.name.isEmpty()) continue;
                video.type = value(item, "type_name", 80);
                video.area = value(item, "vod_area", 160);
                video.pic = value(item, "vod_pic", 2048);
                video.note = value(item, "vod_remarks", 160);
                int year = number(item, "vod_year", 0);
                video.year = year >= 1880 && year <= 2100 ? year : 0;
                video.sourceKey = bean.getKey();
                // 浏览仅保留选片元数据；详情和播放字段交给用户选片后的独立流程。
                videos.add(video);
            } catch (RuntimeException ignored) {
                // 单条脏数据不使其他已取得的影片消失。
            }
        }
        int pageCount = number(root, "pagecount", -1);
        boolean hasMore = rows.size() > 0 && (pageCount < 0 || cursor.page < pageCount);
        return new ParsedPage(videos, hasMore);
    }

    private interface LocalRead {
        String run() throws Exception;
    }

    private String local(SourceDescriptor source, long revision, LocalRead read, int ticket, long deadline) throws Exception {
        RequestContext requestContext = new RequestContext(source, revision, ticket);
        localRequests.add(requestContext);
        Future<String> future;
        try {
            future = LOCAL_READS.submit(() -> {
                if (cancelled(ticket)) throw new IOException("请求已取消");
                SourceResult<String> result = AdapterRuntime.get().execute(source, requestContext, read::run);
                if (!result.isSuccess()) throw new IOException("该来源暂不可用");
                return result.getData();
            });
        } catch (RuntimeException error) {
            requestContext.cancel();
            localRequests.remove(requestContext);
            throw error;
        }
        try {
            String json = future.get(remaining(deadline), TimeUnit.MILLISECONDS);
            if (json == null || json.length() > PublicMetadataHttp.MAX_BYTES
                    || json.getBytes("UTF-8").length > PublicMetadataHttp.MAX_BYTES || cancelled(ticket)) throw new IOException("来源响应无效");
            return json;
        } finally {
            requestContext.cancel();
            localRequests.remove(requestContext);
            future.cancel(true);
        }
    }

    private long remaining(long deadline) throws IOException {
        long remaining = Math.min(REQUEST_BUDGET_MS, deadline - SystemClock.elapsedRealtime());
        if (remaining < 100) throw new IOException("本轮浏览预算已用完");
        return remaining;
    }

    private static String value(JsonObject object, String key) {
        return value(object, key, 80);
    }

    private static String value(JsonObject object, String key, int maxLength) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) return "";
        String value = object.get(key).getAsString().trim();
        return value.length() <= maxLength && !value.matches("(?s).*\\p{Cntrl}.*") ? value : "";
    }

    private static int number(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private boolean cancelled(int ticket) {
        return closed || ticket != generation.get() || Thread.currentThread().isInterrupted();
    }

    public void cancel() {
        generation.incrementAndGet();
        cancelNetwork();
    }

    private void cancelNetwork() {
        http.cancelAll();
        for (RequestContext request : localRequests) request.cancel();
    }

    public void close() {
        closed = true;
        cancel();
        worker.shutdownNow();
    }
}
