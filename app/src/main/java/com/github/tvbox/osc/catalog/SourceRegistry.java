package com.github.tvbox.osc.catalog;

import android.content.Context;
import android.content.SharedPreferences;

import com.github.tvbox.osc.util.LOG;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** 当前快照只在安全加载时发布；后台更新仅写独立目录缓存。 */
public final class SourceRegistry {
    public static final String MANAGED_URL = "builtin://liuliufufu/catalog-v2";
    public static final String REMOTE_URL = "https://raw.githubusercontent.com/roberbiu/robert-tv/main/sources/v2/catalog.json";
    private static final String ASSET = "robert/v2/catalog.json";
    private static final long SUCCESS_INTERVAL = TimeUnit.HOURS.toMillis(6);
    private static final long RETRY_INTERVAL = TimeUnit.MINUTES.toMillis(15);
    private static volatile SourceRegistry instance;
    private final Context context;
    private final CatalogProfileStore store;
    private final SharedPreferences preferences;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final ExecutorService updater = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private volatile SourceSnapshot snapshot = SourceSnapshot.empty();
    private String loadedSignature = "";
    private long nextRevision;

    private SourceRegistry(Context context) {
        this.context = context.getApplicationContext();
        store = new CatalogProfileStore(new File(this.context.getFilesDir(), "source-profiles-v2"));
        preferences = this.context.getSharedPreferences("source_profiles_v2", Context.MODE_PRIVATE);
    }
    public static SourceRegistry get(Context context) {
        if (instance == null) {
            synchronized (SourceRegistry.class) {
                if (instance == null) instance = new SourceRegistry(context);
            }
        }
        return instance;
    }
    public SourceSnapshot snapshot() { return snapshot; }

    /** 启动或用户明确安全重载时由后台调用，不触发网络或账号操作。 */
    public synchronized SourceSnapshot loadLocal() throws IOException {
        Map<String, String> profiles = localProfiles();
        String cms = profiles.get(CatalogV2Policy.CMS);
        String selected = profiles.get(CatalogV2Policy.SELECTED);
        SourceSnapshot candidate;
        try {
            candidate = CatalogV2Policy.merge(nextRevision + 1, cms, selected);
        } catch (IllegalArgumentException invalidSelected) {
            // 精选数据冲突时只退回精选组，保留免认证CMS启动能力。
            selected = CatalogV2Policy.emptySelected();
            candidate = CatalogV2Policy.merge(nextRevision + 1, cms, selected);
        }
        String signature = cms + "\n" + selected;
        if (!signature.equals(loadedSignature)) {
            nextRevision++;
            snapshot = candidate;
            loadedSignature = signature;
        }
        return snapshot;
    }

    private Map<String, String> bundledProfiles() throws IOException {
        try (InputStream input = context.getAssets().open(ASSET)) {
            return CatalogV2Policy.splitBundle(CatalogProfileStore.readLimited(input));
        } catch (IOException | IllegalArgumentException invalidV2) {
            // v2资源不可读仍保留已校验v1的旧CMS，不把新模块变成旧源前置。
            String legacy = BuiltinCatalog.get(context).loadLocal();
            Map<String, String> fallback = new LinkedHashMap<>();
            fallback.put(CatalogV2Policy.CMS, legacyCmsProfile(legacy));
            fallback.put(CatalogV2Policy.SELECTED, CatalogV2Policy.emptySelected());
            return fallback;
        }
    }
    private Map<String, String> localProfiles() throws IOException {
        Map<String, String> bundled = bundledProfiles();
        Map<String, String> result = new LinkedHashMap<>();
        try {
            result.put(CatalogV2Policy.CMS, store.load(CatalogV2Policy.CMS, bundled.get(CatalogV2Policy.CMS)));
        } catch (IOException unavailableCms) {
            result.put(CatalogV2Policy.CMS, legacyCmsProfile(BuiltinCatalog.get(context).loadLocal()));
        }
        try {
            result.put(CatalogV2Policy.SELECTED, store.load(CatalogV2Policy.SELECTED,
                    bundled.containsKey(CatalogV2Policy.SELECTED) ? bundled.get(CatalogV2Policy.SELECTED)
                            : CatalogV2Policy.emptySelected()));
        } catch (IOException unavailableSelected) {
            result.put(CatalogV2Policy.SELECTED, CatalogV2Policy.emptySelected());
        }
        return result;
    }

    public void refreshIfDue() {
        long now = System.currentTimeMillis();
        boolean cmsDue = due(CatalogV2Policy.CMS, now);
        boolean selectedDue = due(CatalogV2Policy.SELECTED, now);
        if ((!cmsDue && !selectedDue)
                || !refreshing.compareAndSet(false, true)) return;
        updater.execute(() -> {
            long attempt = System.currentTimeMillis();
            SharedPreferences.Editor changes = preferences.edit();
            if (cmsDue) changes.putLong("cms_attempt", attempt);
            if (selectedDue) changes.putLong("selected_attempt", attempt);
            changes.apply();
            try {
                Request request = new Request.Builder().url(REMOTE_URL).header("Accept", "application/json").build();
                Map<String, String> remote;
                try (Response response = client.newCall(request).execute()) {
                    ResponseBody body = response.body();
                    if (!response.isSuccessful() || body == null || body.contentLength() > CatalogV2Policy.MAX_BYTES) {
                        throw new IOException("目录更新响应无效");
                    }
                    try (InputStream input = body.byteStream()) {
                        remote = CatalogV2Policy.splitBundle(CatalogProfileStore.readLimited(input));
                    }
                }
                Map<String, String> current = localProfiles();
                for (String id : new String[]{CatalogV2Policy.CMS, CatalogV2Policy.SELECTED}) {
                    if (!remote.containsKey(id) || (CatalogV2Policy.CMS.equals(id) ? !cmsDue : !selectedDue)) continue;
                    try {
                        String candidate = CatalogV2Policy.validateProfile(remote.get(id), id);
                        String old = current.get(id);
                        if (CatalogV2Policy.version(candidate) > CatalogV2Policy.version(old)) {
                            if (!CatalogV2Policy.compatibleUpdate(old, candidate)) {
                                throw new IllegalArgumentException("来源身份或配置代际不可回退");
                            }
                            String cms = CatalogV2Policy.CMS.equals(id) ? candidate : current.get(CatalogV2Policy.CMS);
                            String selected = CatalogV2Policy.SELECTED.equals(id) ? candidate : current.get(CatalogV2Policy.SELECTED);
                            CatalogV2Policy.merge(0, cms, selected);
                            store.save(id, candidate);
                            current.put(id, candidate);
                        }
                        preferences.edit().putLong(id + "_success", System.currentTimeMillis()).apply();
                    } catch (IOException | IllegalArgumentException invalidProfile) {
                        LOG.i("部分来源目录暂未更新，继续使用最近有效版本");
                    }
                }
            } catch (IOException | IllegalArgumentException unavailable) {
                LOG.i("来源目录暂未更新，保留本机内容");
            } finally { refreshing.set(false); }
        });
    }
    private boolean due(String id, long now) {
        return !recent(now, preferences.getLong(id + "_success", 0), SUCCESS_INTERVAL)
                && !recent(now, preferences.getLong(id + "_attempt", 0), RETRY_INTERVAL);
    }
    private static boolean recent(long now, long then, long interval) {
        return then > 0 && now >= then && now - then < interval;
    }

    private static String legacyCmsProfile(String legacy) {
        JsonObject old = JsonParser.parseString(CatalogPolicy.validate(legacy)).getAsJsonObject();
        JsonObject profile = new JsonObject();
        profile.addProperty("id", CatalogV2Policy.CMS);
        profile.addProperty("version", old.get("version").getAsInt());
        JsonArray sources = new JsonArray();
        for (JsonElement value : old.getAsJsonArray("sites")) {
            JsonObject site = value.getAsJsonObject();
            JsonObject source = new JsonObject();
            source.addProperty("id", site.get("key").getAsString());
            source.addProperty("name", site.get("name").getAsString());
            source.addProperty("adapterId", "cms");
            source.addProperty("api", site.get("api").getAsString());
            source.addProperty("authorization", site.get("authorization").getAsString());
            source.add("categories", site.getAsJsonArray("categories"));
            sources.add(source);
        }
        profile.add("sources", sources);
        return CatalogV2Policy.validateProfile(profile.toString(), CatalogV2Policy.CMS);
    }
}
