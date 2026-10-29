package com.github.tvbox.osc.browse;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BrowsePolicyTest {
    @Test
    public void identityDoesNotMergeUnknownYearOrSeasonAcrossSources() {
        assertFalse(BrowseIdentity.key(ContentKind.MOVIE, "a", "1", "同名电影", 0)
                .equals(BrowseIdentity.key(ContentKind.MOVIE, "b", "2", "同名电影", 0)));
        assertFalse(BrowseIdentity.key(ContentKind.TV, "a", "1", "同名剧", 2026)
                .equals(BrowseIdentity.key(ContentKind.TV, "b", "2", "同名剧", 2026)));
        assertEquals(BrowseIdentity.key(ContentKind.TV, "a", "1", "示例剧 第一季", 2026),
                BrowseIdentity.key(ContentKind.TV, "b", "2", "示例剧 S1", 2026));
        assertFalse(BrowseIdentity.key(ContentKind.TV, "a", "1", "示例剧 第一季", 2026)
                .equals(BrowseIdentity.key(ContentKind.TV, "b", "2", "示例剧 第二季", 2026)));
        assertEquals(BrowseIdentity.key(ContentKind.MOVIE, "a", "1", "同名电影", 2026),
                BrowseIdentity.key(ContentKind.MOVIE, "b", "2", "同名电影", 2026));
    }
    @Test
    public void leavesUseNamesAndDoNotTurnParentOrCommaIdIntoAllLibrary() {
        assertFalse(BrowseCategoryPolicy.isCmsLeaf("1", "电影"));
        assertFalse(BrowseCategoryPolicy.isCmsLeaf("15,16", "欧美剧"));
        assertTrue(BrowseCategoryPolicy.isCmsLeaf("26", "剧情片"));
        assertEquals(ContentKind.MOVIE, BrowseCategoryPolicy.kindOf("剧情片"));
        assertEquals(ContentKind.ANIMATION, BrowseCategoryPolicy.kindOf("动画片"));
        assertEquals(ContentKind.DOCUMENTARY, BrowseCategoryPolicy.kindOf("记录片"));
        assertEquals(ContentKind.SHORT_DRAMA, BrowseCategoryPolicy.kindOf("短漫剧"));
        assertEquals(ContentKind.UNKNOWN, BrowseCategoryPolicy.kindOf("电影解说"));
        assertEquals(ContentKind.UNKNOWN, BrowseCategoryPolicy.kindOf("伦理片"));
    }

    @Test
    public void countryTokensAllowCoproductionsWithoutSubstringFalseMatches() {
        assertTrue(CountryMatcher.matches("英国 / 美国", "英国"));
        assertTrue(CountryMatcher.matches("United States and United Kingdom", "美国"));
        assertTrue(CountryMatcher.matches("韓國、法国", "韩国"));
        assertFalse(CountryMatcher.matches("美国萨摩亚", "美国"));
        assertFalse(CountryMatcher.matches("欧美", "美国"));
        assertFalse(CountryMatcher.matches("日韩", "日本"));
        assertFalse(CountryMatcher.matches(null, "日本"));
        assertFalse(BrowseCategoryPolicy.accepts(ContentKind.TV, "欧美剧", "英国", "美国"));
    }

    @Test
    public void fixedNavigationKeepsProductOrderAndDistinctCountryQueries() {
        StringBuilder labels = new StringBuilder();
        Set<String> ids = new HashSet<>();
        for (FixedNavigation.Item item : FixedNavigation.ITEMS) {
            if (labels.length() > 0) labels.append(',');
            labels.append(item.label);
            assertTrue(ids.add(item.id));
        }
        assertEquals("首页,热播,电视剧,电影,综艺,动漫,韩剧,美剧,英剧,日剧", labels.toString());
        assertEquals("韩国", FixedNavigation.ITEMS.get(6).country);
        assertEquals("英国", FixedNavigation.ITEMS.get(8).country);
    }

    @Test
    public void aFullFirstLeafDoesNotStarveOtherLeaves() {
        FairPageSchedule schedule = new FairPageSchedule();
        for (int index = 0; index < 12; index++) {
            assertEquals(index % 4, schedule.take(Arrays.asList(false, false, false, false), 1).get(0).intValue());
        }
        assertEquals(1, schedule.take(Arrays.asList(true, false, true, false), 1).get(0).intValue());
        assertEquals(3, schedule.take(Arrays.asList(true, false, true, false), 1).get(0).intValue());
    }

    @Test
    public void cancelledPageCanDiscardItsScheduleWithoutMovingCommittedCursor() {
        FairPageSchedule committed = new FairPageSchedule();
        assertEquals(0, committed.take(Arrays.asList(false, false, false), 1).get(0).intValue());
        FairPageSchedule candidate = committed.copy();
        assertEquals(1, candidate.take(Arrays.asList(false, false, false), 1).get(0).intValue());
        assertEquals(1, committed.take(Arrays.asList(false, false, false), 1).get(0).intValue());
    }
}
