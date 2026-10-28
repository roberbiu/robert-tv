package com.github.tvbox.osc.catalog;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** 内置与最近校验通过的目录优先，网络更新不占用首次启动的完成回调。 */
public final class BuiltinCatalog {
    public static final String MANAGED_URL = "builtin://robert/catalog";
    public static final String REMOTE_URL =
            "https://raw.githubusercontent.com/roberbiu/robert-tv/main/sources/catalog.json";
    private static final String ASSET_PATH = "robert/catalog.json";
    private static final long UPDATE_INTERVAL = TimeUnit.HOURS.toMillis(6);
    private static final long RETRY_INTERVAL = TimeUnit.MINUTES.toMillis(15);
    private static volatile BuiltinCatalog instance;

    private final Context context;
    private final AtomicFile cache;
    private final Object cacheLock = new Object();
    private final SharedPreferences preferences;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    // 目录更新独立使用系统 TLS 校验，不继承来源请求中的自定义证书和日志设置。
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();

    private BuiltinCatalog(Context context) {
        this.context = context.getApplicationContext();
        cache = new AtomicFile(new File(this.context.getFilesDir(), "robert-catalog.json"));
        preferences = this.context.getSharedPreferences("robert_catalog", Context.MODE_PRIVATE);
    }

    public static BuiltinCatalog get(Context context) {
        if (instance == null) {
            synchronized (BuiltinCatalog.class) {
                if (instance == null) instance = new BuiltinCatalog(context);
            }
        }
        return instance;
    }

    public static void initializeDefaults() {
        // 发布版本始终使用受控目录，设置页或局域网写入地址不会变成可执行订阅入口。
        Hawk.put(HawkConfig.API_URL, MANAGED_URL);
        if (!Hawk.contains(HawkConfig.DEFAULT_LOAD_LIVE)) {
            Hawk.put(HawkConfig.DEFAULT_LOAD_LIVE, false);
        }
    }

    public String loadLocal() throws IOException {
        String cached = null;
        synchronized (cacheLock) {
            try (InputStream input = cache.openRead()) {
                cached = readLimited(input);
            } catch (IOException ignored) {
                // 首次启动没有缓存，或者写入被中断时，由 AtomicFile/内置资源恢复。
            }
        }
        String bundled = null;
        try (InputStream input = context.getAssets().open(ASSET_PATH)) {
            bundled = readLimited(input);
        } catch (IOException ignored) {
            // 若内置资源不可读，仍允许使用已经通过策略校验的本地缓存。
        }
        return CatalogPolicy.chooseLocalCatalog(cached, bundled);
    }

    public void refreshIfDue() {
        long now = System.currentTimeMillis();
        if (isRecent(now, preferences.getLong("last_success", 0), UPDATE_INTERVAL)
                || isRecent(now, preferences.getLong("last_attempt", 0), RETRY_INTERVAL)
                || !refreshing.compareAndSet(false, true)) {
            return;
        }
        updateExecutor.execute(() -> {
            preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply();
            try {
                Request request = new Request.Builder().url(REMOTE_URL)
                        .header("Accept", "application/json").build();
                try (Response response = client.newCall(request).execute()) {
                    ResponseBody body = response.body();
                    if (!response.isSuccessful() || body == null
                            || body.contentLength() > CatalogPolicy.MAX_BYTES) {
                        throw new IOException("目录响应无效");
                    }
                    String validated;
                    try (InputStream input = body.byteStream()) {
                        validated = CatalogPolicy.validate(readLimited(input));
                    }
                    if (CatalogPolicy.isNewerVersion(validated, loadLocal())) {
                        saveValidated(validated);
                    }
                    preferences.edit().putLong("last_success", System.currentTimeMillis()).apply();
                }
            } catch (IOException | IllegalArgumentException error) {
                LOG.i("Robert影视目录暂未更新，继续使用本机配置");
            } finally {
                refreshing.set(false);
            }
        });
    }

    private void saveValidated(String content) throws IOException {
        synchronized (cacheLock) {
            FileOutputStream output = null;
            try {
                output = cache.startWrite();
                output.write(content.getBytes(StandardCharsets.UTF_8));
                cache.finishWrite(output);
            } catch (IOException error) {
                if (output != null) cache.failWrite(output);
                throw error;
            }
        }
    }

    private static boolean isRecent(long now, long previous, long interval) {
        return previous > 0 && now >= previous && now - previous < interval;
    }

    private static String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > CatalogPolicy.MAX_BYTES) {
                throw new IOException("目录内容过大");
            }
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
