package com.github.tvbox.osc.util;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContentPolicyTest {
    @Test
    public void keepsRequestedMainstreamCategories() {
        for (String category : new String[]{"国产剧", "欧美剧", "动画片", "纪录片", "大陆综艺", "剧情片"}) {
            assertTrue(category, ContentPolicy.isAllowedCategory(category));
        }
    }

    @Test
    public void excludesAdultCategoriesEvenWhenTheyContainAllowedWords() {
        for (String category : new String[]{"伦理片", "理论片", "福利短剧", "成人动画", "Adult Movie"}) {
            assertFalse(category, ContentPolicy.isAllowedCategory(category));
        }
    }

    @Test
    public void handlesEnglishAndMissingMetadataWithoutGuessing() {
        assertTrue(ContentPolicy.isAllowedCategory("Documentary"));
        assertTrue(ContentPolicy.isAllowedCategory(null));
        assertTrue(ContentPolicy.isAllowedCategory(""));
        assertFalse(ContentPolicy.isAllowedCategory("未分类广告"));
    }
}
