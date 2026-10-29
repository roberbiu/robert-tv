package com.github.tvbox.osc.playback;

import com.github.tvbox.osc.cloud.Cancellation;
import com.github.tvbox.osc.cloud.CloudException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 刷新只允许一个在途任务；任何等待和网络工作都在状态锁外。 */
final class LeaseRefresh {
    interface Work { void run() throws Exception; }
    interface Revision { long current(); }
    private static final class Flight { final CountDownLatch done = new CountDownLatch(1); volatile CloudException error; }
    private Flight current;
    private long lastStarted;
    boolean run(Cancellation cancellation, Work work) throws CloudException {
        return run(cancellation, -1, null, work);
    }
    boolean run(Cancellation cancellation, long failedRevision, Revision revision, Work work) throws CloudException {
        Flight flight; boolean leader;
        synchronized (this) {
            cancellation.check();
            if (revision != null) {
                long latest = revision.current();
                // 旧地址的晚到失败只使用已刷新结果，不触发当前版本的重试冷却。
                if (failedRevision < latest) return false;
                if (failedRevision > latest) throw new CloudException(CloudException.Code.CANCELLED, null);
            }
            leader = current == null || current.done.getCount() == 0;
            if (leader) {
                if (lastStarted != 0 && System.nanoTime() - lastStarted < 5000000000L) throw new CloudException(CloudException.Code.NETWORK, null);
                current = new Flight(); lastStarted = System.nanoTime();
            }
            flight = current;
        }
        if (leader) {
            try { cancellation.check(); work.run(); cancellation.check(); }
            catch (CloudException error) { flight.error = error; }
            catch (Exception ignored) { flight.error = new CloudException(CloudException.Code.NETWORK, null); }
            finally { flight.done.countDown(); }
        } else {
            long deadline = System.nanoTime() + 45000000000L;
            try {
                while (!flight.done.await(100, TimeUnit.MILLISECONDS)) {
                    cancellation.check(); if (System.nanoTime() >= deadline) throw new CloudException(CloudException.Code.NETWORK, null);
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); throw new CloudException(CloudException.Code.CANCELLED, null); }
        }
        cancellation.check(); if (flight.error != null) throw flight.error; return leader;
    }
}
