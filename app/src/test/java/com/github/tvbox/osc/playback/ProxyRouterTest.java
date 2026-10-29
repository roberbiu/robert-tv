package com.github.tvbox.osc.playback;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import com.github.tvbox.osc.cloud.Cancellation;

/** 所有HTTP只读本机合成播放列表，永不请求公网媒体或真人凭据。 */
public class ProxyRouterTest {
    @Test public void cancelledUnclaimedResultIsReclaimedButClaimedPlaybackSurvivesRequestCancellation() throws Exception {
        ProxyRouter router = ProxyRouter.get(null); String playlist = "#EXTM3U\n#EXT-X-ENDLIST\n";
        MediaLease abandoned = router.publishPlaylist("fixture_source", "fixture_adapter", 15, "https://media.example.org/a.m3u8", playlist, Collections.emptyMap());
        Cancellation first = new Cancellation(); router.bindPendingDelivery(abandoned.getLocalUrl(), first); first.cancel();
        assertFalse(router.validateOwnLeaseUrl(abandoned.getLocalUrl())); assertFalse(router.claim(abandoned.getLocalUrl()));
        MediaLease accepted = router.publishPlaylist("fixture_source", "fixture_adapter", 16, "https://media.example.org/b.m3u8", playlist, Collections.emptyMap());
        Cancellation second = new Cancellation(); router.bindPendingDelivery(accepted.getLocalUrl(), second); assertTrue(router.claim(accepted.getLocalUrl())); second.cancel();
        assertTrue(router.validateOwnLeaseUrl(accepted.getLocalUrl())); router.release(accepted.getLocalUrl());
    }
    @Test public void redirectedPlaylistAndRefreshedParentResolveAgainstFinalResponse() throws Exception {
        ProxyRouter.Resource root = new ProxyRouter.Resource("root", "", "https://origin.example.org/a/master.m3u8");
        Response redirected = new Response.Builder().request(new Request.Builder().url("https://cdn.example.org/b/main.m3u8?fake=one").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("fixture").build();
        ProxyRouter.adoptEffectivePlaylistBase(root, redirected, true);
        assertEquals("https://cdn.example.org/b/segment.ts", ProxyRouter.resolveChildTarget(root, "segment.ts", true).toString());
        assertEquals("https://cdn.example.org/key.bin", ProxyRouter.resolveChildTarget(root, "../key.bin", true).toString());
        Response refreshed = new Response.Builder().request(new Request.Builder().url("https://new-cdn.example.org/fresh/child.m3u8").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("fixture refreshed parent").build();
        ProxyRouter.adoptEffectivePlaylistBase(root, refreshed, true);
        assertEquals("https://new-cdn.example.org/fresh/segment.ts", ProxyRouter.resolveChildTarget(root, "segment.ts", true).toString());
    }
    @Test public void hlsKeysMapsAndSegmentsStayInsideTheSameOpaqueLease() throws Exception {
        ProxyRouter router = ProxyRouter.get(null);
        String upstream = "https://media.example.org/fake/master.m3u8?ticket=fake";
        String body = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:42\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin?ticket=fake\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:10,\nsegment42.m4s?ticket=fake\n#EXT-X-ENDLIST\n";
        MediaLease lease = router.publishPlaylist("fixture_source", "fixture_adapter", 1, upstream, body, Collections.emptyMap());
        try {
            HttpURLConnection connection = connection(lease.getLocalUrl()); assertEquals(200, connection.getResponseCode());
            String rewritten = read(connection); assertFalse(rewritten.contains("media.example.org")); assertFalse(rewritten.contains("ticket=fake"));
            String prefix = lease.getLocalUrl().substring(0, lease.getLocalUrl().lastIndexOf('/') + 1);
            assertTrue(rewritten.contains("URI=\"" + prefix)); assertTrue(rewritten.contains("\n" + prefix));
            assertTrue(router.validateOwnLeaseUrl(lease.getLocalUrl()));
            assertFalse(router.validateOwnLeaseUrl(lease.getLocalUrl().replace("root.m3u8", "unknown.bin")));
            assertFalse(router.validateOwnLeaseUrl(lease.getLocalUrl() + "#unexpected"));
            assertEquals(403, connection(prefix + "unpublished.bin").getResponseCode());
            assertEquals(403, connection(lease.getLocalUrl() + "?url=https://127.0.0.1/").getResponseCode());
            assertEquals(403, connection(lease.getLocalUrl().replace("/media/", "/proxy/")).getResponseCode());
        } finally { router.release(lease.getLocalUrl()); }
    }
    @Test public void trialBDoesNotDestroyRetainedAAndAccountRevocationClosesOnce() throws Exception {
        ProxyRouter router = ProxyRouter.get(null); String playlist = "#EXTM3U\n#EXT-X-ENDLIST\n";
        MediaLease original = router.publishPlaylist("fixture_source", "fixture_adapter", 10, "https://media.example.org/a.m3u8", playlist, Collections.emptyMap());
        assertTrue(router.retain(original.getLocalUrl()));
        MediaLease trial = router.publishPlaylist("fixture_source", "fixture_adapter", 11, "https://media.example.org/b.m3u8", playlist, Collections.emptyMap());
        router.release(trial.getLocalUrl()); assertTrue(router.validateOwnLeaseUrl(original.getLocalUrl()));
        router.release(original.getLocalUrl()); assertTrue(router.validateOwnLeaseUrl(original.getLocalUrl()));
        router.release(original.getLocalUrl()); assertFalse(router.validateOwnLeaseUrl(original.getLocalUrl()));
        AtomicBoolean active = new AtomicBoolean(true); AtomicInteger closed = new AtomicInteger(); CountDownLatch released = new CountDownLatch(1);
        MediaLease identity = router.publish("fixture_source", "fixture_adapter", 12, "quark", "fake_account", 4,
                "https://media.example.org/fake.m3u8", true, active::get, target -> Collections.emptyMap(), null,
                () -> { closed.incrementAndGet(); released.countDown(); });
        active.set(false); assertFalse(router.validateOwnLeaseUrl(identity.getLocalUrl()));
        assertEquals(403, connection(identity.getLocalUrl()).getResponseCode());
        assertTrue(released.await(3, TimeUnit.SECONDS)); router.release(identity.getLocalUrl()); assertEquals(1, closed.get());
    }
    private static HttpURLConnection connection(String url) throws Exception { HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(); connection.setConnectTimeout(3000); connection.setReadTimeout(3000); return connection; }
    private static String read(HttpURLConnection connection) throws Exception {
        try (InputStream input = connection.getInputStream()) { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[1024]; int count; while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count); return new String(output.toByteArray(), "UTF-8"); }
        finally { connection.disconnect(); }
    }
}
