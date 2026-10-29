package com.github.tvbox.osc.trending;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 用户进入榜单后才取数，单实例串行更新，离开或切换后旧结果不回写UI。 */
public final class TrendingRepository {
    public interface Listener {
        void onResult(TrendingSnapshot snapshot, boolean fromCache, String message);
    }

    private static final long SOFT_CACHE_MS = 6L * 60 * 60 * 1000;
    private static final long FAILURE_BACKOFF_MS = 15L * 60 * 1000;
    private final TrendingStore store;
    private final PublicMetadataHttp http = new PublicMetadataHttp();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private String guduoDate;
    private long guduoDateAt;
    private volatile boolean closed;

    public TrendingRepository(Context context) {
        store = new TrendingStore(context.getApplicationContext());
    }

    public int load(TrendingBoard board, boolean force, Listener listener) {
        if (closed) return generation.get();
        int ticket = generation.incrementAndGet();
        http.cancelAll();
        worker.execute(() -> {
            if (cancelled(ticket)) return;
            long now = System.currentTimeMillis();
            TrendingStore.Record record = store.read(board);
            TrendingSnapshot cached = record.snapshot;
            long cacheAge = cached == null ? Long.MAX_VALUE : Math.max(0, now - cached.fetchedAt);
            if (!force && cached != null && cacheAge < SOFT_CACHE_MS) {
                deliver(ticket, listener, cached, true, "");
                return;
            }
            long attemptAge = Math.max(0, now - record.lastAttemptAt);
            boolean recentFailure = record.lastAttemptAt > (cached == null ? 0 : cached.fetchedAt);
            if (record.lastAttemptAt > 0 && (attemptAge < 5000 || (!force && recentFailure && attemptAge < FAILURE_BACKOFF_MS))) {
                deliver(ticket, listener, cached, true, cached == null ? "榜单来源暂不可用，请稍后重试" : "显示最近成功数据，来源稍后重试");
                return;
            }
            try {
                store.write(board, cached, now);
            } catch (Exception ignored) {
            }
            try {
                PublicMetadataHttp.Cancellation cancellation = () -> cancelled(ticket);
                TrendingSnapshot result;
                if (board.guduoCategory != null) {
                    if (guduoDate == null || now - guduoDateAt >= SOFT_CACHE_MS) {
                        guduoDate = TrendingParsers.guduoDate(http.get("https://d2.guduomedia.com/m/v3/billboard/configDay", cancellation, 10000));
                        guduoDateAt = now;
                    }
                    String url = "https://d2.guduomedia.com/m/v3/billboard/list?type=DAILY&category="
                            + board.guduoCategory + "&date=" + guduoDate + "&attach=gdi&orderTitle=gdi&platformId=0";
                    result = TrendingParsers.guduo(board, guduoDate, http.get(url, cancellation, 10000), now, cached);
                } else if (board == TrendingBoard.HONGGUO_SHORT) {
                    result = TrendingParsers.hongguo(http.get(board.officialUrl(), cancellation, 12000), now, cached);
                } else {
                    result = TrendingParsers.baidu(http.get(board.officialUrl(), cancellation, 10000), now, cached);
                }
                if (cancelled(ticket)) {
                    restoreCancelledAttempt(board, cached, record.lastAttemptAt);
                    return;
                }
                String warning = "";
                try {
                    store.write(board, result, now);
                } catch (Exception ignored) {
                    warning = "榜单已加载，缓存暂不可用";
                }
                deliver(ticket, listener, result, false, warning);
            } catch (Exception ignored) {
                if (cancelled(ticket)) restoreCancelledAttempt(board, cached, record.lastAttemptAt);
                else deliver(ticket, listener, cached, true,
                        cached == null ? "榜单来源暂不可用，可刷新或选择其他榜单" : "更新暂未成功，显示最近成功数据");
            }
        });
        return ticket;
    }

    private void restoreCancelledAttempt(TrendingBoard board, TrendingSnapshot cached, long attempt) {
        try {
            store.write(board, cached, attempt);
        } catch (Exception ignored) {
        }
    }

    private boolean cancelled(int ticket) {
        return closed || ticket != generation.get();
    }

    private void deliver(int ticket, Listener listener, TrendingSnapshot snapshot, boolean cache, String message) {
        main.post(() -> {
            if (!cancelled(ticket)) listener.onResult(snapshot, cache, message);
        });
    }

    public void cancel() {
        generation.incrementAndGet();
        http.cancelAll();
    }

    public void close() {
        closed = true;
        cancel();
        worker.shutdownNow();
    }
}
