package com.github.tvbox.osc.trending;

import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 仅在回环口模拟持续小流量；不连接或拖慢任何公网服务。 */
public class MetadataCallDeadlineTest {
    @Test
    public void totalCallTimeoutStopsBodyThatKeepsArrivingBeforeReadTimeout() throws Exception {
        try (SlowLocalServer server = new SlowLocalServer()) {
            Call call = MetadataCallDeadline.create(client(), new Request.Builder().url(server.url()).build(), 350);
            long started = System.nanoTime();
            try (Response response = call.execute()) {
                response.body().string();
                fail("持续滴流应被总调用预算终止");
            } catch (IOException expected) {
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue("总时限不能等待完整响应或readTimeout", elapsedMillis < 1500);
            } finally {
                call.cancel();
            }
            assertTrue("测试必须已进入响应体滴流", server.streaming.getCount() == 0);
        }
    }

    @Test
    public void cancellingCallUnblocksAnInFlightResponseBody() throws Exception {
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (SlowLocalServer server = new SlowLocalServer()) {
            Call call = MetadataCallDeadline.create(client(), new Request.Builder().url(server.url()).build(), 5000);
            try {
                Future<Boolean> stopped = reader.submit(() -> {
                    try (Response response = call.execute()) {
                        response.body().string();
                        return false;
                    } catch (IOException expected) {
                        return true;
                    }
                });
                assertTrue(server.streaming.await(1, TimeUnit.SECONDS));
                call.cancel();
                assertTrue("Call.cancel须中断正在读取的body", stopped.get(1500, TimeUnit.MILLISECONDS));
                assertTrue(call.isCanceled());
            } finally {
                call.cancel();
            }
        } finally {
            reader.shutdownNow();
        }
    }

    private static OkHttpClient client() {
        return new OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    }

    private static final class SlowLocalServer implements Closeable {
        final CountDownLatch streaming = new CountDownLatch(1);
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final Thread writer;
        volatile Socket socket;
        volatile boolean closed;

        SlowLocalServer() throws IOException {
            writer = new Thread(() -> {
                try {
                    socket = server.accept();
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                    for (int index = 0; index < 10000 && !closed; index++) {
                        out.write('a');
                        out.flush();
                        streaming.countDown();
                        Thread.sleep(25);
                    }
                } catch (IOException | InterruptedException expected) {
                    // 测试主动取消、超时或关闭后退出。
                }
            }, "metadata-local-slow-fixture");
            writer.setDaemon(true);
            writer.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/fixture";
        }

        @Override
        public void close() throws IOException {
            closed = true;
            server.close();
            if (socket != null) socket.close();
            writer.interrupt();
            try {
                writer.join(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
