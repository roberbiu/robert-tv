package com.github.tvbox.osc.source.wogg;

import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.CloudReference;
import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WoggParserTest {
    @Test public void detailOnlySuppliesPublicCloudChoicesWithoutInventedEpisodes() throws Exception {
        String html = "<div class=video-info-header><h1 class=page-title>合成剧集</h1></div><div class=video-info-itemtitle>类型：</div><div class=video-info-item>剧集</div><div data-clipboard-text='https://pan.quark.cn/s/fakeshare12'></div><div data-clipboard-text='https://pan.baidu.com/s/unsupported'></div>";
        JsonObject result = WoggParser.detail(html, "https://www.wogg.live", "/voddetail/12.html", "fty_wogg");
        assertEquals("电视剧", result.get("type_name").getAsString()); assertEquals("", result.get("vod_area").getAsString());
        String line = result.get("vod_play_url").getAsString(); assertTrue(line.startsWith("选择文件与集数$cloudref:")); assertFalse(line.contains("第1集"));
        assertEquals("fty_wogg", CloudReference.decode(line.substring(line.indexOf('$') + 1)).sourceId);
        assertFalse(result.get("vod_play_from").getAsString().contains("百度"));
    }
    @Test public void qualityColumnsNeverBecomeMovieOrCountryEvidence() {
        assertEquals("", WoggParser.normalizeType("臻彩")); assertEquals("", WoggParser.normalizeType("4K原盘"));
        assertEquals("", WoggParser.detailPath("https://evil.example/voddetail/12.html", "https://www.wogg.live"));
    }
    @Test public void explicitlyExcludedDetailDoesNotBecomePlayable() throws Exception {
        try { WoggParser.detail("<div class=video-info-header><h1 class=page-title>合成样本</h1></div><b>类型</b><div class=video-info-item>成人伦理</div>", "https://www.wogg.live", "/voddetail/12.html", "fty_wogg"); fail(); }
        catch (CloudException expected) { assertEquals(CloudException.Code.UNSUPPORTED, expected.code); }
    }
}
