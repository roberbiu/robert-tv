package com.github.tvbox.osc.cloud;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 手机授权是可取消的独立事务，不在来源线程中等待界面或递归重新登录。 */
public final class QrLoginCoordinator {
    public enum State { LOADING, WAITING, RETRYABLE_NETWORK, AUTHORIZED, EXPIRED, CANCELLED, FAILED }
    public interface Listener {
        void onState(State state, String safeMessage);
        void onQr(String qrContent, long localWaitUntil);
        void onAuthorized();
        void onError(CloudException error);
    }
    public interface Services {
        CloudSessionManager sessions();
        CloudProviderClient client();
        default void beforeAuthorizationCommit(CloudProvider provider) throws CloudException { }
    }
    public interface Loader { Services load() throws CloudException; }

    public final class Handle {
        private final CloudProvider provider;
        private final Listener listener;
        private final Executor callbacks;
        private final Cancellation cancellation = new Cancellation();
        // 0待授权、1已经收到授权并开始提交、2成功、3取消、4失败。
        private final AtomicInteger transaction = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final long deadlineNanos = System.nanoTime() + 180000000000L;
        private volatile Services services;
        private volatile CloudSessionManager.Authorization authorization;

        Handle(CloudProvider provider, Listener listener, Executor callbacks) { this.provider = provider; this.listener = listener; this.callbacks = callbacks; }
        public void cancel() {
            closed.set(true);
            if (transaction.compareAndSet(0, 3)) {
                cancellation.cancel();
                // 串号清理由后台finally完成；界面取消不等待任何私有存储/密钥锁。
            }
        }
        public boolean isClosed() { return closed.get(); }
        private void callback(Runnable action) { callbacks.execute(() -> { if (!closed.get()) action.run(); }); }
    }

    private final Loader loader;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Map<CloudProvider, Handle> current = new EnumMap<>(CloudProvider.class);

    public QrLoginCoordinator(Loader loader) { this.loader = loader; }

    public synchronized Handle start(CloudProvider provider, Listener listener, Executor callbacks) {
        Handle old = current.get(provider); if (old != null) old.cancel();
        Handle handle = new Handle(provider, listener, callbacks); current.put(provider, handle);
        workers.execute(() -> run(handle)); return handle;
    }

    private void run(Handle handle) {
        boolean completed = false;
        try {
            handle.cancellation.check();
            handle.callback(() -> handle.listener.onState(State.LOADING, "正在获取二维码"));
            handle.services = loader.load();
            handle.cancellation.check();
            handle.authorization = handle.services.sessions().beginAuthorization(handle.provider);
            long remainingMillis = Math.max(0, (handle.deadlineNanos - System.nanoTime()) / 1000000L);
            if (remainingMillis <= 0) throw new CloudException(CloudException.Code.QR_EXPIRED, handle.provider);
            CloudProviderClient.LoginTicket ticket = handle.services.client().createLoginTicket(handle.provider, handle.cancellation, remainingMillis);
            handle.callback(() -> {
                handle.listener.onQr(ticket.qrContent(), ticket.createdAt + ticket.waitBudgetMillis);
                handle.listener.onState(State.WAITING, "请使用" + handle.provider.displayName + "手机端扫码确认");
            });
            int networkFailures = 0;
            while (!ticket.isExpired()) {
                handle.cancellation.check();
                try {
                    CloudProviderClient.LoginResult result = handle.services.client().pollLogin(ticket, handle.cancellation);
                    networkFailures = 0;
                    if (result != null) {
                        // 提交点在服务端授权到达后；取消先到则不能进入此分支。
                        if (!handle.transaction.compareAndSet(0, 1)) throw new CloudException(CloudException.Code.CANCELLED, handle.provider);
                        handle.services.beforeAuthorizationCommit(handle.provider);
                        if (!handle.services.sessions().complete(handle.authorization, result.account, result.cookie)) throw new CloudException(CloudException.Code.CANCELLED, handle.provider);
                        handle.transaction.set(2); completed = true;
                        handle.callback(() -> { handle.listener.onState(State.AUTHORIZED, "授权成功"); handle.listener.onAuthorized(); });
                        return;
                    }
                } catch (CloudException failure) {
                    if (failure.code != CloudException.Code.NETWORK || ++networkFailures > 2) throw failure;
                    handle.callback(() -> handle.listener.onState(State.RETRYABLE_NETWORK, "网络暂时失败，正在重试本次等待"));
                }
                CloudProviderClient.pause(handle.cancellation, Math.min(3000, ticket.remainingMillis()));
            }
            throw new CloudException(CloudException.Code.QR_EXPIRED, handle.provider);
        } catch (CloudException failure) {
            if (handle.transaction.get() != 3) handle.transaction.set(4);
            handle.callback(() -> {
                handle.listener.onState(failure.code == CloudException.Code.QR_EXPIRED ? State.EXPIRED : failure.code == CloudException.Code.CANCELLED ? State.CANCELLED : State.FAILED, failure.getMessage());
                handle.listener.onError(failure);
            });
        } finally {
            if (!completed && handle.services != null && handle.authorization != null) handle.services.sessions().cancel(handle.authorization);
            synchronized (this) { if (current.get(handle.provider) == handle) current.remove(handle.provider); }
        }
    }
}
