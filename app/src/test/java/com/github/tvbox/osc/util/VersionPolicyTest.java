package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VersionPolicyTest {
    @Test
    public void metadataDoesNotPretendToBeSubtitleDetection() {
        assertEquals("字幕待确认", VersionPolicy.subtitleLabel("纪录片", "国语1080P"));
        assertEquals("来源标注中文", VersionPolicy.subtitleLabel("电影[中英字幕]", "HD"));
        assertEquals("来源标注无中文", VersionPolicy.subtitleLabel("电影", "无中文字幕"));
        assertFalse(VersionPolicy.claimsChineseSubtitle("电影", "无中文字幕"));
        assertFalse(VersionPolicy.claimsChineseSubtitle("中英街1号", "正片"));
        assertFalse(VersionPolicy.claimsChineseSubtitle("电影", "双语字幕"));
        assertFalse(VersionPolicy.claimsChineseSubtitle("电影", "中文字幕未知"));
    }

    @Test
    public void unknownAuthorizationAndQualityRemainUnknown() {
        assertEquals("授权待确认", VersionPolicy.authorizationLabel(null));
        assertEquals("无需网盘授权", VersionPolicy.authorizationLabel("none"));
        assertEquals("清晰度待确认", VersionPolicy.qualityLabel("电影2026", "正片"));
        assertEquals("来源标注4K", VersionPolicy.qualityLabel("电影", "2160P"));
        assertEquals("来源标注4K", VersionPolicy.qualityLabel("电影4K版", "正片"));
    }

    @Test
    public void autoSwitchRejectsRemakesAndUnknownYears() {
        assertTrue(VersionPolicy.canAutoSwitch("电影[1080P]", 2020, "电影[中字]", 2020));
        assertFalse(VersionPolicy.canAutoSwitch("电影", 2020, "电影", 2025));
        assertFalse(VersionPolicy.canAutoSwitch("电影", 2020, "电影", 0));
        assertFalse(VersionPolicy.canAutoSwitch("电影", 0, "电影", 2020));
    }

    @Test
    public void seasonsAndEpisodesAreNotInterchangeable() {
        assertTrue(VersionPolicy.canAutoSwitch("剧集 第一季", 2020, "剧集 第1季", 2020));
        assertFalse(VersionPolicy.canAutoSwitch("剧集 第一季", 2020, "剧集 第二季", 2020));
        assertTrue(VersionPolicy.sameEpisode("第06集", "EP06"));
        assertFalse(VersionPolicy.sameEpisode("第06集", "第01集"));
        assertFalse(VersionPolicy.sameEpisode("S01E03", "S02E03"));
        assertFalse(VersionPolicy.sameEpisode("第06集", "正片"));
        assertTrue(VersionPolicy.sameEpisode("正片", "HD中字"));
    }

    @Test
    public void balancedSelectionDoesNotBlindlyPrefer4k() {
        assertTrue(VersionPolicy.balancedScore("电影", "1080P", "none")
                > VersionPolicy.balancedScore("电影", "4K", "required"));
        assertTrue(VersionPolicy.balancedScore("电影", "1080P中字", "none")
                > VersionPolicy.balancedScore("电影", "1080P", "none"));
    }

    @Test
    public void chineseTrackTagsRecognizeScriptAndRegionVariants() {
        for (String language : new String[]{"zh", "zh-Hans", "zh-Hant", "zh_TW", "zho", "chi", "cmn", "yue", "国语"}) {
            assertTrue(language, VersionPolicy.isChineseLanguage(language));
        }
        assertFalse(VersionPolicy.isChineseLanguage("en"));
        assertFalse(VersionPolicy.isChineseLanguage("unknown"));
    }

    @Test
    public void episodePartsAndEditionsMustMatch() {
        assertFalse(VersionPolicy.sameEpisode("第1期上", "第1期下"));
        assertFalse(VersionPolicy.sameEpisode("第1期中", "第1期"));
        assertFalse(VersionPolicy.sameEpisode("第6集预告", "第6集"));
        assertFalse(VersionPolicy.sameEpisode("第3期纯享版", "第3期"));
        assertFalse(VersionPolicy.sameEpisode("第3期加更", "第3期纯享"));
        assertTrue(VersionPolicy.sameEpisode("第1期（上）", "EP01上半场"));
        assertTrue(VersionPolicy.sameEpisode("第6集预告片", "EP06预告"));
    }

    @Test
    public void attemptedSourceIdentityIsScopedToEpisodeAndPart() {
        assertEquals(VersionPolicy.workEpisodeIdentity("综艺", 2026, "第1期上"),
                VersionPolicy.workEpisodeIdentity("综艺", 2026, "EP01上半场"));
        assertFalse(VersionPolicy.workEpisodeIdentity("综艺", 2026, "第1期上").equals(
                VersionPolicy.workEpisodeIdentity("综艺", 2026, "第1期下")));
        assertFalse(VersionPolicy.workEpisodeIdentity("剧集", 2026, "第1集").equals(
                VersionPolicy.workEpisodeIdentity("剧集", 2026, "第2集")));
        assertEquals(VersionPolicy.workEpisodeIdentity("剧集 第1季", 2026, "EP01"),
                VersionPolicy.workEpisodeIdentity("剧集 第1季", 2026, "S01E01"));
    }

    @Test
    public void confirmedChineseTrackWinsOverUnknownSourceSubtitle() {
        assertEquals(VersionPolicy.SubtitleChoice.INTERNAL_CHINESE,
                VersionPolicy.chooseSubtitle(false, true, true, true));
        assertEquals(VersionPolicy.SubtitleChoice.MANUAL_EXTERNAL,
                VersionPolicy.chooseSubtitle(true, true, true, true));
        assertEquals(VersionPolicy.SubtitleChoice.SOURCE_EXTERNAL,
                VersionPolicy.chooseSubtitle(false, false, true, true));
        assertEquals(VersionPolicy.SubtitleChoice.INTERNAL_OTHER,
                VersionPolicy.chooseSubtitle(false, false, false, true));
        assertEquals(VersionPolicy.SubtitleChoice.NONE,
                VersionPolicy.chooseSubtitle(false, false, false, false));
    }
}
