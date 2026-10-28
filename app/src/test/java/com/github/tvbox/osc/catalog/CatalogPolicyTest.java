package com.github.tvbox.osc.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CatalogPolicyTest {
    @Test
    public void rejectsExecutableFieldsAtEveryConfigurationLevel() {
        for (String field : Arrays.asList("spider", "parses", "rules", "hosts", "doh")) {
            JsonObject catalog = catalog();
            catalog.addProperty(field, "https://example.org/payload.jar");
            assertRejected(catalog.toString());
        }
        for (String field : Arrays.asList("jar", "ext", "playUrl", "script", "click")) {
            JsonObject catalog = catalog();
            source(catalog).addProperty(field, "https://example.org/payload.js");
            assertRejected(catalog.toString());
        }
        JsonObject catalog = catalog();
        source(catalog).addProperty("type", 3);
        assertRejected(catalog.toString());
    }

    @Test
    public void rejectsNonCmsAndCredentialBearingEndpoints() {
        for (String endpoint : Arrays.asList(
                "file:///data/catalog.json",
                "https://127.0.0.1/api.php/provide/vod",
                "http://localhost/api.php/provide/vod",
                "https://account:secret@example.org/api.php/provide/vod",
                "https://example.org/api.php/provide/vod?token=secret",
                "https://example.org/payload.js",
                "https://example.org/api.php/provide/vod#fragment")) {
            JsonObject catalog = catalog();
            source(catalog).addProperty("api", endpoint);
            assertRejected(catalog.toString());
        }
    }

    @Test
    public void rejectsEmptyDisabledAndNonSearchableDirectories() {
        JsonObject empty = catalog();
        empty.add("sites", new JsonArray());
        assertRejected(empty.toString());
        JsonObject disabled = catalog();
        source(disabled).addProperty("enabled", false);
        assertRejected(disabled.toString());
        JsonObject unsearchable = catalog();
        source(unsearchable).addProperty("searchable", 0);
        assertRejected(unsearchable.toString());
    }

    @Test
    public void rejectsDuplicateSourcesAndUnboundedInput() {
        JsonObject duplicate = catalog();
        duplicate.getAsJsonArray("sites").add(source(duplicate).deepCopy());
        assertRejected(duplicate.toString());
        JsonObject tooMany = catalog();
        for (int i = 1; i <= CatalogPolicy.MAX_SOURCES; i++) {
            JsonObject extra = source(tooMany).deepCopy();
            extra.addProperty("key", "source_" + i);
            extra.addProperty("api", "https://source" + i + ".example.org/api.php/provide/vod");
            tooMany.getAsJsonArray("sites").add(extra);
        }
        assertRejected(tooMany.toString());
        char[] huge = new char[CatalogPolicy.MAX_BYTES + 1];
        Arrays.fill(huge, ' ');
        assertRejected(new String(huge));
    }

    @Test
    public void rejectsAdultCategoriesAndUnverifiedSubtitleClaims() {
        JsonObject adult = catalog();
        source(adult).getAsJsonArray("categories").add("伦理片");
        assertRejected(adult.toString());
        JsonObject unsupportedClaim = catalog();
        source(unsupportedClaim).addProperty("chineseSubtitlesConfirmed", true);
        assertRejected(unsupportedClaim.toString());
    }

    @Test
    public void fallsBackToBundledDataWhenCachedConfigurationIsInvalid() {
        JsonObject cached = catalog();
        cached.addProperty("version", 2);
        cached.addProperty("spider", "https://example.org/unknown.jar");
        String result = CatalogPolicy.chooseLocalCatalog(cached.toString(), catalog().toString());
        assertEquals(1, JsonParser.parseString(result).getAsJsonObject().get("version").getAsInt());
        String malformed = CatalogPolicy.chooseLocalCatalog("{broken", catalog().toString());
        assertEquals(result, malformed);
    }

    @Test
    public void keepsValidCachedDirectoryAndRebuildsOnlyAllowedFields() {
        JsonObject cached = catalog();
        cached.addProperty("version", 2);
        source(cached).addProperty("enabled", true);
        JsonObject result = JsonParser.parseString(CatalogPolicy.chooseLocalCatalog(
                cached.toString(), catalog().toString())).getAsJsonObject();
        assertEquals(2, result.get("version").getAsInt());
        assertEquals("none", source(result).get("authorization").getAsString());
        assertEquals(2, source(result).get("playerType").getAsInt());
        assertFalse(source(result).has("enabled"));
        assertFalse(result.has("spider"));
    }

    @Test
    public void newerBundledFixWinsOverOlderOrEqualVersionCache() {
        JsonObject cached = catalog();
        source(cached).addProperty("name", "旧缓存");
        JsonObject bundled = catalog();
        bundled.addProperty("version", 2);
        source(bundled).addProperty("name", "新内置目录");
        JsonObject selected = JsonParser.parseString(CatalogPolicy.chooseLocalCatalog(
                cached.toString(), bundled.toString())).getAsJsonObject();
        assertEquals(2, selected.get("version").getAsInt());
        assertEquals("新内置目录", source(selected).get("name").getAsString());

        cached.addProperty("version", 2);
        selected = JsonParser.parseString(CatalogPolicy.chooseLocalCatalog(
                cached.toString(), bundled.toString())).getAsJsonObject();
        assertEquals("新内置目录", source(selected).get("name").getAsString());
    }

    @Test
    public void remoteUpdateRequiresStrictlyIncreasingVersion() {
        JsonObject current = catalog();
        current.addProperty("version", 2);
        JsonObject candidate = catalog();
        assertFalse(CatalogPolicy.isNewerVersion(candidate.toString(), current.toString()));
        candidate.addProperty("version", 2);
        source(candidate).addProperty("name", "同版本内容变更");
        assertFalse(CatalogPolicy.isNewerVersion(candidate.toString(), current.toString()));
        candidate.addProperty("version", 3);
        assertTrue(CatalogPolicy.isNewerVersion(candidate.toString(), current.toString()));
    }

    @Test
    public void validCacheStillWorksWhenBundledDataIsUnavailable() {
        String selected = CatalogPolicy.chooseLocalCatalog(catalog().toString(), null);
        assertEquals(1, JsonParser.parseString(selected).getAsJsonObject().get("version").getAsInt());
        try {
            CatalogPolicy.chooseLocalCatalog("{broken", null);
            fail("没有有效本地目录时不能返回无效配置");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void rejectsFractionalFlagsAndInvalidAuthorizationMetadata() {
        JsonObject fractional = catalog();
        source(fractional).addProperty("searchable", 0.5);
        assertRejected(fractional.toString());
        JsonObject authorization = catalog();
        source(authorization).addProperty("authorization", "always_available");
        assertRejected(authorization.toString());
    }

    private static JsonObject catalog() {
        JsonObject site = new JsonObject();
        site.addProperty("key", "example");
        site.addProperty("name", "测试来源");
        site.addProperty("type", 1);
        site.addProperty("api", "https://example.org/api.php/provide/vod/");
        site.addProperty("authorization", "none");
        JsonArray categories = new JsonArray();
        categories.add("剧情片");
        categories.add("国产剧");
        site.add("categories", categories);
        JsonArray sites = new JsonArray();
        sites.add(site);
        JsonObject catalog = new JsonObject();
        catalog.addProperty("version", 1);
        catalog.add("sites", sites);
        return catalog;
    }

    private static JsonObject source(JsonObject catalog) {
        return catalog.getAsJsonArray("sites").get(0).getAsJsonObject();
    }

    private static void assertRejected(String json) {
        try {
            CatalogPolicy.validate(json);
            fail("目录应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 无效目录必须在持久化或交给 ApiConfig 之前被拒绝。
        }
    }
}
