package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackLinePolicyTest {
    @Test
    public void directMediaWithQueryIsPreferredOverAWebPlayer() {
        assertEquals(0, PlaybackLinePolicy.priority("line", "https://example.com/video.m3u8?expires=1"));
        assertEquals(2, PlaybackLinePolicy.priority("line", "https://example.com/share/123"));
        assertTrue(PlaybackLinePolicy.isDirectMediaUrl("https://example.com/第01集.MP4"));
    }

    @Test
    public void aQueryParameterIsNotProofOfADirectMediaPath() {
        assertFalse(PlaybackLinePolicy.isDirectMediaUrl("https://example.com/play?url=video.m3u8"));
        assertEquals(1, PlaybackLinePolicy.priority("m3u8", "https://example.com/play?id=123"));
    }

    @Test
    public void nonNetworkAndMalformedUrlsAreNotAutoPreferred() {
        assertFalse(PlaybackLinePolicy.isDirectMediaUrl("file:///video.mp4"));
        assertFalse(PlaybackLinePolicy.isDirectMediaUrl("javascript:video.mp4"));
        assertFalse(PlaybackLinePolicy.isDirectMediaUrl("not a url"));
        assertFalse(PlaybackLinePolicy.isDirectMediaUrl(null));
    }
}
