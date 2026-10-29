package com.github.tvbox.osc.source;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.catalog.SourceDescriptor;
import com.github.tvbox.osc.catalog.SourceRegistry;

import java.util.HashMap;
import java.util.Map;

/** 只创建APK内显式注册的适配器，不反射加载目录给出的类名或执行文件。 */
public final class AdapterRegistry {
    public interface Factory {
        Spider create(Context applicationContext, SourceDescriptor descriptor) throws Exception;
    }

    private static final AdapterRegistry INSTANCE = new AdapterRegistry();
    private final Map<String, Factory> factories = new HashMap<>();
    private final Map<String, Entry> instances = new HashMap<>();
    private final Map<String, Long> retryAfter = new HashMap<>();
    private static final long RETRY_NANOS = 15_000_000_000L;

    private static final class Entry {
        final String adapterId;
        final int revision;
        final Spider spider;
        Entry(SourceDescriptor source, Spider spider) {
            adapterId = source.getAdapterId();
            revision = source.getConfigRevision();
            this.spider = spider;
        }
        boolean matches(SourceDescriptor source) {
            return revision == source.getConfigRevision() && adapterId.equals(source.getAdapterId());
        }
    }

    private AdapterRegistry() { }
    public static AdapterRegistry get() { return INSTANCE; }

    public synchronized void register(String adapterId, Factory factory) {
        if (!("wogg".equals(adapterId) || "dm84".equals(adapterId)) || factory == null) {
            throw new IllegalArgumentException("未准入的本地适配器");
        }
        Factory existing = factories.get(adapterId);
        if (existing != null && existing != factory) throw new IllegalStateException("适配器已注册");
        factories.put(adapterId, factory);
    }

    public Spider getSpider(Context context, SourceBean bean) {
        SourceDescriptor descriptor = SourceRegistry.get(context).snapshot().getSource(bean.getKey());
        if (descriptor == null || descriptor.getConfigRevision() != bean.getConfigRevision()
                || !descriptor.getAdapterId().equals(bean.getAdapterId())) {
            throw new IllegalStateException("来源快照已失效");
        }
        return getSpider(context, descriptor);
    }

    public synchronized Spider getSpider(Context context, SourceDescriptor descriptor) {
        if (descriptor == null || !descriptor.isEnabled() || "cms".equals(descriptor.getAdapterId())) {
            throw new IllegalArgumentException("本地来源不可用");
        }
        Entry existing = instances.get(descriptor.getId());
        if (existing != null && existing.matches(descriptor)) return existing.spider;
        Long next = retryAfter.get(descriptor.getId());
        long now = System.nanoTime();
        if (next != null && now < next) throw new IllegalStateException("来源正在等待重试");
        Factory factory = factories.get(descriptor.getAdapterId());
        if (factory == null) throw new IllegalStateException("本地适配器尚未注册");
        try {
            Context app = context == null ? null : context.getApplicationContext();
            Spider spider = factory.create(app, descriptor);
            if (spider == null) throw new IllegalStateException("本地适配器创建失败");
            // init契约仅允许设置配置，不执行网络请求、登录或UI操作。
            spider.siteKey = descriptor.getId();
            spider.init(app, descriptor.getSourceBean().getExt());
            instances.put(descriptor.getId(), new Entry(descriptor, spider));
            retryAfter.remove(descriptor.getId());
            return spider;
        } catch (Exception | LinkageError error) {
            retryAfter.put(descriptor.getId(), now + RETRY_NANOS);
            throw new IllegalStateException("本地适配器初始化失败");
        }
    }
}
