package com.github.tvbox.osc.trending;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TrendingParsersTest {
    private static final long NOW = Instant.parse("2026-10-29T03:00:00Z").toEpochMilli();

    @Test
    public void guduoPreservesEachOriginalBoardDateAndRank() {
        for (TrendingBoard board : TrendingBoard.values()) {
            if (board.guduoCategory == null) continue;
            String json = "{\"code\":0,\"data\":[{\"showId\":\"sample1\",\"name\":\"合成作品\",\"category\":\""
                    + board.guduoCategory + "\",\"gdi\":\"72.50\",\"rank\":3}]}";
            TrendingSnapshot snapshot = TrendingParsers.guduo(board, "2026-10-27", json, NOW, null);
            assertEquals("2026-10-27", snapshot.dataDate);
            assertEquals(3, snapshot.entries.get(0).rank);
            assertEquals("72.50", snapshot.entries.get(0).score);
            assertTrue(snapshot.updatedLabel.contains("未提供"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void aSuccessfulHttpBodyWithWrongCategoryIsNotAValidBoard() {
        TrendingParsers.guduo(TrendingBoard.ALL_ANIME, "2026-10-27",
                "{\"code\":0,\"data\":[{\"showId\":\"1\",\"name\":\"错误类型\",\"category\":\"TV_DRAMA\",\"gdi\":\"9\",\"rank\":1}]}", NOW, null);
    }

    @Test
    public void hongguoReadsOnlyWhitelistedJsonAttributeAndDropsRuntimeAndPlaybackContext() {
        JsonObject entry = new JsonObject();
        entry.addProperty("seriesId", "123456");
        entry.addProperty("title", "合成短剧");
        entry.addProperty("rank", 2);
        entry.addProperty("heatText", "123万热度");
        entry.addProperty("cover", "http://127.0.0.1/private.png");
        entry.addProperty("href", "javascript:alert(1)");
        entry.addProperty("playHref", "private-playback-marker");
        entry.addProperty("description", "private-description-marker");
        JsonArray list = new JsonArray();
        list.add(entry);
        JsonObject content = new JsonObject();
        content.addProperty("isSuccess", true);
        content.add("rankList", list);
        JsonArray arguments = new JsonArray();
        arguments.add("rank_hot-drama/page");
        arguments.add("content");
        arguments.add(content);
        String attribute = arguments.toString().replace("&", "&amp;").replace("\"", "&quot;");
        String html = "<p>10月29日已更新 · 基于红果内观看/互动等综合热度排序</p>"
                + "<script>window.req={cookie:'should-not-copy'}</script>"
                + "<script data-script-src=\"modern-run-router-data-fn\" data-fn-name=\"r\" data-fn-args=\"" + attribute + "\"></script>";
        TrendingSnapshot snapshot = TrendingParsers.hongguo(html, NOW, null);
        assertEquals("合成短剧", snapshot.entries.get(0).title);
        assertEquals("--10-29", snapshot.dataDate);
        assertEquals("", snapshot.entries.get(0).cover);
        assertEquals("", snapshot.entries.get(0).detailUrl);
        String normalized = new Gson().toJson(snapshot);
        assertFalse(normalized.contains("should-not-copy"));
        assertFalse(normalized.contains("private-playback-marker"));
        assertFalse(normalized.contains("private-description-marker"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyOrUnrelatedRouterPayloadDoesNotBecomeACompleteShortDramaBoard() {
        TrendingParsers.hongguo("<p>10月29日已更新 · 综合热度</p><script data-script-src='other'>[]</script>", NOW, null);
    }

    @Test
    public void baiduSearchHeatKeepsUnknownProviderTime() {
        TrendingSnapshot snapshot = TrendingParsers.baidu(baidu("100"), NOW, null);
        assertEquals(1, snapshot.entries.get(0).rank);
        assertEquals("", snapshot.dataDate);
        assertTrue(snapshot.updatedLabel.contains("未提供"));
    }

    static String baidu(String score) {
        return "<!--s-data:{\"data\":{\"curBoardName\":\"电影榜\",\"cards\":[{\"content\":[{\"word\":\"合成电影\",\"hotScore\":\""
                + score + "\",\"index\":0,\"url\":\"https://baike.baidu.com/item/sample\"}]}]}}-->";
    }
}
