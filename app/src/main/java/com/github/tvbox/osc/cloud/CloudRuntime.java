package com.github.tvbox.osc.cloud;

import android.content.Context;

import com.github.tvbox.osc.playback.MediaLease;
import com.github.tvbox.osc.playback.ProxyRouter;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.EnumMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;

/** 应用级服务入口。get与站点构造不联网，密钥/会话首次在后台实际使用时加载。 */
public final class CloudRuntime {
    /** 本机保存状态，不代表已经向服务端验证授权或账号权益。 */
    public enum LocalAuthorization { STORED, NONE }

    private static volatile CloudRuntime instance;
    private final Context context;
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor();
    private final QrLoginCoordinator login;
    private volatile Services services;

    private static final class Services implements QrLoginCoordinator.Services {
        final CloudSessionManager sessions;
        final CloudProviderClient client;
        final Map<CloudProvider, TransferService> transfers = new EnumMap<>(CloudProvider.class);
        Services(Context context) {
            SecretStore store = new EncryptedSessionStore(context);
            sessions = new CloudSessionManager(store);
            client = new CloudProviderClient(sessions);
            for (CloudProvider provider : CloudProvider.values()) transfers.put(provider, new TransferService(sessions, client, new TransferLedger(store, provider)));
        }
        @Override public CloudSessionManager sessions() { return sessions; }
        @Override public CloudProviderClient client() { return client; }
        @Override public void beforeAuthorizationCommit(CloudProvider provider) throws CloudException { transfers.get(provider).recoverAfterAuthorization(); }
    }

    private CloudRuntime(Context context) {
        this.context = context.getApplicationContext();
        login = new QrLoginCoordinator(this::services);
    }
    public static CloudRuntime get(Context context) {
        CloudRuntime value = instance;
        if (value != null) return value;
        synchronized (CloudRuntime.class) {
            if (instance == null) instance = new CloudRuntime(context);
            return instance;
        }
    }

    private Services services() throws CloudException {
        Services value = services;
        if (value != null) return value;
        synchronized (this) {
            if (services == null) try { services = new Services(context); }
            catch (RuntimeException ignored) { throw new CloudException(CloudException.Code.PRIVATE_STORAGE, null); }
            return services;
        }
    }

    /** 仅反映已加载缓存；false也可能是尚未加载，界面不能据此显示“未授权”。 */
    public boolean isAuthorizedCached(CloudProvider provider) { Services value = services; return value != null && value.sessions.hasSession(provider); }

    /** 仅从后台调用；加载本机私有状态，不发请求、不暴露账号标识或凭据。 */
    public LocalAuthorization readLocalAuthorization(CloudProvider provider) throws CloudException {
        if (provider == null) throw new CloudException(CloudException.Code.UNSUPPORTED, null);
        try {
            services().sessions.require(provider);
            return LocalAuthorization.STORED;
        } catch (CloudException failure) {
            if (failure.code == CloudException.Code.AUTH_REQUIRED) return LocalAuthorization.NONE;
            throw failure;
        }
    }

    public QrLoginCoordinator.Handle login(CloudProvider provider, QrLoginCoordinator.Listener listener, Executor callbacks) {
        return login.start(provider, listener, callbacks);
    }

    /** 从后台调用；返回真实目录的一页，不递归扫用户云盘。 */
    public CloudProviderClient.SharePage listShare(CloudReference reference, int page, Cancellation cancellation) throws CloudException {
        return services().client.listShare(reference, page, cancellation);
    }

    public void logout(CloudProvider provider) throws CloudException { services().sessions.logout(provider); }

    /** 来源解析不会启动二维码或文件对话框，界面根据cloud_action和用户意图决定。 */
    public JsonObject playerResult(CloudReference reference, String adapterId, long generation, Cancellation cancellation) throws CloudException {
        Services value = services();
        CloudSessionManager.Snapshot identity = value.sessions.require(reference.cloudProvider());
        if (!reference.isFile()) {
            JsonObject result = new JsonObject(); result.addProperty("parse", 0); result.addProperty("url", "");
            result.addProperty("cloud_action", "select_file"); result.addProperty("provider", reference.provider);
            result.addProperty("cloud_ref", reference.encode()); result.addProperty("message", "请选择实际文件与集数");
            return result;
        }
        cancellation.check();
        TransferService transfers = value.transfers.get(identity.provider);
        TransferLedger.Operation operation = transfers.prepare(reference, identity, cancellation);
        MediaLease published = null;
        try {
            CloudProviderClient.Stream stream = value.client.playFile(identity.provider, operation.fileId, reference.quality, identity, cancellation);
            cancellation.check();
            AtomicReference<String> currentOrigin = new AtomicReference<>(stream.url);
            MediaLease lease = ProxyRouter.get(context).publish(reference.sourceId, adapterId, generation,
                    identity.provider.id, identity.accountId, identity.authEpoch, stream.url, !"original".equals(stream.resolution),
                    () -> sameSession(value.sessions, identity),
                    target -> headersForMedia(value.sessions, identity, currentOrigin.get(), target),
                    refreshCancellation -> {
                        CloudProviderClient.Stream refreshed = value.client.playFile(identity.provider, operation.fileId,
                                "original".equals(stream.resolution) ? "original" : stream.resolution, identity, refreshCancellation);
                        currentOrigin.set(refreshed.url); return refreshed.url;
                    },
                    () -> transfers.releaseAndCleanup(operation, identity.authEpoch));
            published = lease;
            ProxyRouter.get(context).bindPendingDelivery(lease.getLocalUrl(), cancellation); cancellation.check();
            JsonObject result = new JsonObject(); result.addProperty("parse", 0); result.addProperty("url", lease.getLocalUrl());
            result.add("header", new JsonObject()); result.addProperty("cloud_quality", stream.resolution);
            result.addProperty("cloud_ref", reference.encode()); result.addProperty("provider", reference.provider);
            JsonArray qualities = new JsonArray(); for (String quality : stream.availableResolutions) qualities.add(quality); result.add("cloud_quality_options", qualities);
            result.addProperty("cloud_file_name", reference.fileName); return result;
        } catch (CloudException failure) {
            if (published != null) ProxyRouter.get(context).release(published.getLocalUrl());
            else cleanup.execute(() -> transfers.releaseAndCleanup(operation, identity.authEpoch));
            throw failure;
        }
    }

    private static boolean sameSession(CloudSessionManager manager, CloudSessionManager.Snapshot expected) {
        return manager.matchesSession(expected);
    }

    static Map<String, String> headersForMedia(CloudSessionManager manager, CloudSessionManager.Snapshot identity, String original, String target) throws CloudException {
        CloudSessionManager.Snapshot current = manager.require(identity.provider);
        if (current.authEpoch != identity.authEpoch || !current.accountId.equals(identity.accountId)) throw new CloudException(CloudException.Code.CANCELLED, identity.provider);
        HttpUrl url = NetworkPolicy.requireUrl(target, null, true), origin = HttpUrl.parse(original);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", CloudHttpTransport.USER_AGENT); headers.put("Referer", identity.provider.webBase);
        String suffix = identity.provider == CloudProvider.QUARK ? ".quark.cn" : ".uc.cn";
        // 仅服务端给出的同一官方媒体主机可接收Cookie；跨host跳转默认不继承。
        if (origin != null && origin.host().equals(url.host()) && url.host().endsWith(suffix)) headers.put("Cookie", current.credential());
        return headers;
    }
}
