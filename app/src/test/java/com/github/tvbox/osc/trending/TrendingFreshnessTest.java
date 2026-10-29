package com.github.tvbox.osc.trending;

import org.junit.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TrendingFreshnessTest {
    @Test
    public void partialDateDoesNotMoveToAnotherYearWhenUnchangedContentIsFetchedAgain() {
        long observed = Instant.parse("2025-10-29T03:00:00Z").toEpochMilli();
        long fetchedAgain = Instant.parse("2026-10-29T03:00:00Z").toEpochMilli();
        TrendingSnapshot snapshot = new TrendingSnapshot(TrendingBoard.HONGGUO_SHORT, "--10-29", "月日", "热度",
                fetchedAgain, observed, observed, "unchanged", java.util.Collections.emptyList());
        assertTrue(TrendingFreshness.isHistorical(snapshot, fetchedAgain));
    }
    private static final long NOW = Instant.parse("2026-10-29T03:00:00Z").toEpochMilli();

    @Test
    public void fetchingAnOldSourceDayNowDoesNotRenewItsFreshness() {
        TrendingSnapshot snapshot = new TrendingSnapshot(TrendingBoard.NETWORK_MOVIE, "2026-10-01", "未提供", "日榜",
                NOW, NOW, NOW, "sample", Collections.emptyList());
        assertTrue(TrendingFreshness.isHistorical(snapshot, NOW));
    }

    @Test
    public void unchangedUnknownDateBoardRetainsItsFirstObservationAcrossNewGets() {
        long first = Instant.parse("2026-10-01T03:00:00Z").toEpochMilli();
        TrendingSnapshot before = TrendingParsers.baidu(TrendingParsersTest.baidu("100"), first, null);
        TrendingSnapshot again = TrendingParsers.baidu(TrendingParsersTest.baidu("100"), NOW, before);
        assertEquals(first, again.firstSeenAt);
        assertEquals(NOW, again.fetchedAt);
        assertTrue(TrendingFreshness.isHistorical(again, NOW));
        TrendingSnapshot changed = TrendingParsers.baidu(TrendingParsersTest.baidu("101"), NOW, again);
        assertFalse(TrendingFreshness.isHistorical(changed, NOW));
    }

    @Test
    public void monthDayKeepsSourcePrecisionAndUsesThePastYearAcrossNewYear() {
        long january = Instant.parse("2027-01-01T01:00:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-12-30T16:00:00Z").toEpochMilli(), TrendingFreshness.dataTime("--12-31", january));
        assertEquals(0, TrendingFreshness.dataTime("2026-02-30"));
    }
}
