package com.github.tvbox.osc.search;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExpectedWorkIdentityTest {
    @Test
    public void titleYearTypeAndCountryMustAllBeKnownBeforeFieldsAreConsistent() {
        ExpectedWorkIdentity expected = ExpectedWorkIdentity.of("示例剧 第一季", 2026, "电视剧", "美国", "");
        assertTrue(expected.assess("示例剧 S1", 2026, "欧美剧", "USA").allKnownSame());
        assertFalse(expected.assess("示例剧 第一季", 0, "欧美剧", "美国").allKnownSame());
        assertEquals(ExpectedWorkIdentity.FieldState.UNKNOWN,
                expected.assess("示例剧 第一季", 2026, null, "美国").type);
        assertEquals(ExpectedWorkIdentity.FieldState.UNKNOWN,
                expected.assess("示例剧 第一季", 2026, "欧美剧", null).area);
    }

    @Test
    public void sameTitleWithDifferentYearTypeCountryOrSeasonIsExplicitlyDifferent() {
        ExpectedWorkIdentity expected = ExpectedWorkIdentity.of("示例剧 第一季", 2026, "电视剧", "美国", "");
        assertEquals(ExpectedWorkIdentity.FieldState.DIFFERENT,
                expected.assess("示例剧 第一季", 2025, "欧美剧", "美国").year);
        assertEquals(ExpectedWorkIdentity.FieldState.DIFFERENT,
                expected.assess("示例剧 第一季", 2026, "剧情片", "美国").type);
        assertEquals(ExpectedWorkIdentity.FieldState.DIFFERENT,
                expected.assess("示例剧 第一季", 2026, "欧美剧", "英国").area);
        assertEquals(ExpectedWorkIdentity.FieldState.DIFFERENT,
                expected.assess("示例剧 第二季", 2026, "欧美剧", "美国").title);
    }

    @Test
    public void coproductionsUseFullTokensAndPartialInformationIsUncertain() {
        ExpectedWorkIdentity expected = ExpectedWorkIdentity.of("示例电影", 2026, "电影", "美国 / 英国", "");
        assertTrue(expected.assess("示例电影", 2026, "剧情片", "United Kingdom and United States").allKnownSame());
        assertEquals(ExpectedWorkIdentity.FieldState.UNKNOWN,
                expected.assess("示例电影", 2026, "剧情片", "美国").area);
        assertEquals(ExpectedWorkIdentity.FieldState.UNKNOWN,
                expected.assess("示例电影", 2026, "剧情片", "欧美").area);
        assertEquals(ExpectedWorkIdentity.FieldState.DIFFERENT,
                expected.assess("示例电影", 2026, "剧情片", "美国萨摩亚").area);
    }

    @Test
    public void unknownExpectationDoesNotTurnKnownCandidateIntoAConfirmedWork() {
        ExpectedWorkIdentity expected = ExpectedWorkIdentity.of("合成热播条目", 0, "电影", "", "");
        ExpectedWorkIdentity.Assessment candidate = expected.assess("合成热播条目", 2026, "剧情片", "中国大陆");
        assertFalse(candidate.allKnownSame());
        assertTrue(candidate.label().contains("年份"));
        assertTrue(candidate.label().contains("地区"));
        assertEquals(1, candidate.priority());
    }

    @Test
    public void knownConsistentThenUnknownThenDifferentDefinesIdentityPriority() {
        ExpectedWorkIdentity expected = ExpectedWorkIdentity.of("示例电影", 2026, "电影", "日本", "");
        assertEquals(0, expected.assess("示例电影", 2026, "剧情片", "日本").priority());
        assertEquals(1, expected.assess("示例电影", 0, "剧情片", "日本").priority());
        assertEquals(2, expected.assess("示例电影", 2025, "剧情片", "日本").priority());
    }

    @Test
    public void changingSearchWordDropsWorkContextAndPosterCannotSupplyCandidateIdentity() {
        ExpectedWorkIdentity expected = ExpectedWorkIdentity.of("示例电影", 2026, "电影", "日本", "https://example.test/poster.jpg");
        assertTrue(expected.appliesToSearch("示例 电影"));
        assertFalse(expected.appliesToSearch("示例"));
        assertFalse(expected.assess("示例电影", 0, "", "").allKnownSame());
        assertEquals("", ExpectedWorkIdentity.of("示例", 0, "", "", "file:///private/photo").picture());
        assertEquals("", ExpectedWorkIdentity.of("示例", 0, "", "", "https://user:secret@example.test/poster").picture());
    }

    @Test
    public void placeholderCountryFieldsRemainUnknownEvenWhenTheirTextIsEqual() {
        ExpectedWorkIdentity expected = ExpectedWorkIdentity.of("示例电影", 2026, "电影", "未知", "");
        assertEquals(ExpectedWorkIdentity.FieldState.UNKNOWN,
                expected.assess("示例电影", 2026, "剧情片", "未知").area);
        assertFalse(ExpectedWorkIdentity.none().isActive());
    }

    @Test
    public void automaticFallbackPoolRequiresCompleteFieldsAndKnownFilmOrSeason() {
        ExpectedWorkIdentity movie = ExpectedWorkIdentity.of("示例电影", 2026, "电影", "日本", "");
        assertTrue(movie.canOfferAutomaticFallback("示例电影", 2026, "剧情片", "日本"));
        assertFalse(movie.canOfferAutomaticFallback("示例电影", 2026, "剧情片", ""));
        ExpectedWorkIdentity noSeason = ExpectedWorkIdentity.of("示例剧", 2026, "电视剧", "日本", "");
        assertFalse(noSeason.canOfferAutomaticFallback("示例剧", 2026, "日本剧", "日本"));
        ExpectedWorkIdentity season = ExpectedWorkIdentity.of("示例剧 第一季", 2026, "电视剧", "日本", "");
        assertTrue(season.canOfferAutomaticFallback("示例剧 S1", 2026, "日本剧", "日本"));
        String mixed = movie.assess("示例电影", 2025, "剧情片", "").label();
        assertTrue(mixed.contains("信息不同：年份"));
        assertTrue(mixed.contains("待核对：地区"));
    }
}
