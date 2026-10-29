package com.github.tvbox.osc.source.dm84;

import com.github.tvbox.osc.cloud.CloudException;
import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class Dm84ParserTest {
    private static final String BASE = "https://dm84.tv/";
    @Test public void actualEpisodePathsAndUnknownCountryRemainDistinct() throws Exception {
        String html = "<h1 class=v_title>合成动画</h1><meta name='og:video:class' content='国漫'><ul class=tab_control><li>线路一</li></ul><ul class=play_list><li><a href='/p/42-1-3.html'>第3集</a></li><li><a href='https://evil.example/p/42-1-4.html'>假集数</a></li></ul>";
        JsonObject result = Dm84Parser.detail(html, BASE, "/v/42.html");
        assertEquals("国产动漫", result.get("type_name").getAsString()); assertEquals("", result.get("vod_area").getAsString());
        assertEquals("第3集$/p/42-1-3.html", result.get("vod_play_url").getAsString());
        assertFalse(result.has("subtitle_confirmed"));
    }
    @Test public void bootstrapIsDataAndCannotChooseArbitraryEndpoint() throws Exception {
        String html = "<script>window.__HHJX_BOOTSTRAP__={\"url\":\"0123456789ABCDEF\",\"t\":123456,\"key\":\"ABCDEF0123456789\",\"endpoint\":\"http://127.0.0.1/admin\",\"client_fallback\":true};</script>";
        JsonObject parsed = Dm84Resolver.parseBootstrap(html);
        assertFalse(parsed.get("client_fallback").getAsBoolean()); assertFalse(parsed.has("endpoint"));
        assertNull(Dm84Resolver.direct("https://example.org/player.html", "youku"));
        assertTrue(Dm84Resolver.direct("https://example.org/stream/index.m3u8?ticket=fake", "").hls);
        try { Dm84Resolver.parseBootstrap("<script>window.__HHJX_BOOTSTRAP__=runRemoteCode();</script>"); fail(); }
        catch (CloudException expected) { assertEquals(CloudException.Code.UNSUPPORTED, expected.code); }
    }
}
