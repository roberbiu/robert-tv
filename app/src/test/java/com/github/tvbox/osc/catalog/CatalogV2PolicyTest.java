package com.github.tvbox.osc.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class CatalogV2PolicyTest {
    @Test public void executableAndGlobalFieldsRemainRejected() {
        for (String field : Arrays.asList("spider", "rules", "hosts", "doh", "parses")) {
            JsonObject bundle = bundle();
            bundle.addProperty(field, "https://example.org/code.jar");
            rejected(() -> CatalogV2Policy.splitBundle(bundle.toString()));
        }
        for (String field : Arrays.asList("jar", "ext", "script", "className", "cookie")) {
            JsonObject selected = profile("selected", 1, nativeSource("fty_wogg", "wogg"));
            selected.getAsJsonArray("sources").get(0).getAsJsonObject().addProperty(field, "private");
            rejected(() -> CatalogV2Policy.validateProfile(selected.toString(), "selected"));
        }
    }

    @Test public void nativeAdaptersAndTargetsAreExplicitlyLimited() {
        JsonObject unknown = profile("selected", 1, nativeSource("fty_test", "remote_class"));
        rejected(() -> CatalogV2Policy.validateProfile(unknown.toString(), "selected"));
        for (String endpoint : Arrays.asList("file:///data/private", "http://www.wogg.live/",
                "https://account:password@example.org/", "https://127.0.0.1/", "https://localhost./",
                "https://example.org/path", "https://example.org/?cookie=private", "https://example.org/#x")) {
            JsonObject source = nativeSource("fty_wogg", "wogg");
            JsonArray sites = new JsonArray();
            sites.add(endpoint);
            source.add("sites", sites);
            rejected(() -> CatalogV2Policy.validateProfile(profile("selected", 1, source).toString(), "selected"));
        }
    }

    @Test public void oldKeysAndDefensiveSnapshotsSurviveCoexistence() {
        JsonObject cms = profile("cms", 1, cmsSource("robert_liangzi", "a.example.org"));
        JsonObject selected = profile("selected", 1, nativeSource("fty_wogg", "wogg"));
        SourceSnapshot snapshot = CatalogV2Policy.merge(7, cms.toString(), selected.toString());
        assertEquals(2, snapshot.getSources().size());
        assertEquals(7, snapshot.getRevision());
        SourceDescriptor original = snapshot.getSource("robert_liangzi");
        original.getSourceBean().setApi("https://changed.example.org/");
        original.getSourceBean().getCategories().clear();
        assertEquals("https://a.example.org/api.php/provide/vod/", original.getApi());
        assertEquals(1, original.getCategories().size());
        try { snapshot.getSources().clear(); fail(); } catch (UnsupportedOperationException expected) { }
        assertEquals("fty_wogg", snapshot.getSources().get(0).getId());
    }

    @Test public void disabledSourcesRemainKnownButDoNotConsumeActiveBudget() {
        JsonObject cms = profile("cms", 1);
        for (int i = 0; i < 15; i++) cms.getAsJsonArray("sources").add(cmsSource("cms_" + i, "s" + i + ".example.org"));
        JsonObject selected = profile("selected", 1, nativeSource("fty_wogg", "wogg"), nativeSource("fty_dm84", "dm84"));
        rejected(() -> CatalogV2Policy.merge(1, cms.toString(), selected.toString()));
        cms.getAsJsonArray("sources").get(14).getAsJsonObject().addProperty("enabled", false);
        SourceSnapshot valid = CatalogV2Policy.merge(1, cms.toString(), selected.toString());
        assertEquals(16, valid.getSources().size());
        assertFalse(valid.getSource("cms_14").isEnabled());
        assertEquals(17, valid.getAllSources().size());
    }

    @Test public void duplicateIdentityAndUnverifiedCountryCapabilityAreRejected() {
        JsonObject duplicate = profile("selected", 1, nativeSource("fty_wogg", "wogg"), nativeSource("fty_alias", "wogg"));
        rejected(() -> CatalogV2Policy.validateProfile(duplicate.toString(), "selected"));
        JsonObject baofeng = cmsSource("robert_baofeng", "bf.example.org");
        baofeng.addProperty("countryFilterMode", "verified_server");
        rejected(() -> CatalogV2Policy.validateProfile(profile("cms", 1, baofeng).toString(), "cms"));
    }

    @Test public void invalidCurrentFallsBackAndOlderBundledCannotRollback() {
        String asset = profile("cms", 1, cmsSource("robert_liangzi", "old.example.org")).toString();
        String backup = profile("cms", 2, cmsSource("robert_liangzi", "working.example.org"),
                cmsSource("second_cms", "second.example.org")).toString();
        String chosen = CatalogV2Policy.chooseLocalProfile("cms", asset, "broken", backup);
        assertEquals(2, CatalogV2Policy.version(chosen));
        assertTrue(chosen.contains("working.example.org"));
        String removedIdentity = profile("cms", 3, cmsSource("new_key", "new.example.org")).toString();
        assertEquals(2, CatalogV2Policy.version(CatalogV2Policy.chooseLocalProfile("cms", removedIdentity, backup, null)));
        JsonObject disabled = JsonParser.parseString(backup).getAsJsonObject();
        disabled.getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("enabled", false);
        assertTrue(CatalogV2Policy.preservesIds(backup, disabled.toString()));
        assertFalse(CatalogV2Policy.preservesIds(backup, removedIdentity));
    }

    @Test public void selectedGroupCanBeEmptyButAnonymousCmsCannotDisappear() {
        String cms = profile("cms", 1, cmsSource("robert_liangzi", "a.example.org")).toString();
        assertEquals(1, CatalogV2Policy.merge(1, cms, CatalogV2Policy.emptySelected()).getSources().size());
        JsonObject disabled = JsonParser.parseString(cms).getAsJsonObject();
        disabled.getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("enabled", false);
        rejected(() -> CatalogV2Policy.merge(1, disabled.toString(), CatalogV2Policy.emptySelected()));
        assertEquals(CatalogV2Policy.validateProfile(cms, "cms"),
                CatalogV2Policy.chooseLocalProfile("cms", cms, disabled.toString(), null));
    }

    @Test public void changedEndpointRequiresNewSourceRevisionAndStableAdapter() {
        String old = CatalogV2Policy.validateProfile(profile("cms", 1, cmsSource("robert_liangzi", "old.example.org")).toString(), "cms");
        JsonObject changed = profile("cms", 2, cmsSource("robert_liangzi", "new.example.org"));
        changed.getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("configRevision", 1);
        String reusedRevision = CatalogV2Policy.validateProfile(changed.toString(), "cms");
        assertFalse(CatalogV2Policy.compatibleUpdate(old, reusedRevision));
        changed.getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("configRevision", 2);
        assertTrue(CatalogV2Policy.compatibleUpdate(old, CatalogV2Policy.validateProfile(changed.toString(), "cms")));
    }

    private static JsonObject bundle() {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 2);
        JsonArray groups = new JsonArray();
        groups.add(profile("cms", 1, cmsSource("robert_liangzi", "a.example.org")));
        groups.add(profile("selected", 1, nativeSource("fty_wogg", "wogg")));
        result.add("profiles", groups);
        return result;
    }
    private static JsonObject profile(String id, int version, JsonObject... sources) {
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("version", version);
        JsonArray list = new JsonArray();
        for (JsonObject source : sources) list.add(source);
        result.add("sources", list);
        return result;
    }
    private static JsonObject cmsSource(String id, String host) {
        JsonObject source = base(id, "cms");
        source.addProperty("api", "https://" + host + "/api.php/provide/vod/");
        source.addProperty("authorization", "none");
        return source;
    }
    private static JsonObject nativeSource(String id, String adapter) {
        JsonObject source = base(id, adapter);
        source.addProperty("priority", 80);
        JsonArray sites = new JsonArray();
        sites.add("https://" + ("dm84".equals(adapter) ? "dm84.tv" : "www.wogg.live") + "/");
        source.add("sites", sites);
        return source;
    }
    private static JsonObject base(String id, String adapter) {
        JsonObject source = new JsonObject();
        source.addProperty("id", id);
        source.addProperty("name", id);
        source.addProperty("adapterId", adapter);
        JsonArray categories = new JsonArray();
        categories.add("电影");
        source.add("categories", categories);
        return source;
    }
    private static void rejected(Runnable action) {
        try { action.run(); fail("应拒绝未准入输入"); } catch (IllegalArgumentException expected) { }
    }
}
