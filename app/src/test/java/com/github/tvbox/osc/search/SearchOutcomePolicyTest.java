package com.github.tvbox.osc.search;

import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.source.DomainError;
import com.github.tvbox.osc.source.wogg.NativeJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 使用本地适配器真正输出和AbsJson转换，不靠人为补total绕过宿主模型。 */
public class SearchOutcomePolicyTest {
    @Test public void actualNativeListWithDefaultTotalSurvivesModelFilterAndMetadata() {
        JsonArray list = new JsonArray();
        for (int i = 1; i <= 6; i++) list.add(NativeJson.video("/index.php/voddetail/" + i + ".html", "老友记 第" + i + "季",
                "https://images.example.org/poster.jpg", "中文", ""));
        String payload = NativeJson.list(list, 1, false);
        assertFalse(payload.contains("total"));
        AbsXml result = SearchOutcomePolicy.decodeNative(payload, "fty_wogg", "request-1");
        assertFalse(SearchOutcomePolicy.isFailure(result));
        assertNotNull(result.movie);
        assertEquals(0, result.movie.recordcount);
        assertEquals(6, result.movie.videoList.size());
        assertEquals("fty_wogg", result.sourceKey);
        assertEquals("request-1", result.searchToken);
        for (Movie.Video video : result.movie.videoList) {
            assertEquals("fty_wogg", video.sourceKey);
            assertTrue(video.id.startsWith("/index.php/voddetail/"));
            assertTrue(video.name.startsWith("老友记"));
        }
    }

    @Test public void actualConversionKeepsAllowedAnimationButFiltersExplicitAdultType() {
        JsonArray list = new JsonArray();
        list.add(NativeJson.video("dm84-item", "动画", "", "更新", "国产动漫"));
        list.add(NativeJson.video("blocked", "其他", "", "", "成人情色"));
        AbsXml result = SearchOutcomePolicy.decodeNative(NativeJson.list(list, 1, false), "fty_dm84", "token");
        assertFalse(SearchOutcomePolicy.isFailure(result));
        assertEquals(1, result.movie.videoList.size());
        assertEquals("dm84-item", result.movie.videoList.get(0).id);
        assertEquals("fty_dm84", result.movie.videoList.get(0).sourceKey);
    }

    @Test public void nativeSnakeCaseFailureAndRuntimeDomainFailureHaveOneSafeState() {
        String payload = "{\"error_code\":\"NETWORK\",\"message\":\"Cookie=private; https://private.example/?token=secret\",\"url\":\"private-url\"}";
        AbsXml nativeFailure = SearchOutcomePolicy.decodeNative(payload, "fty_wogg", "a");
        AbsXml domainFailure = SearchOutcomePolicy.decodeNative(new DomainError(DomainError.Code.NETWORK).toJson().toString(), "fty_wogg", "a");
        assertTrue(SearchOutcomePolicy.isFailure(nativeFailure));
        assertEquals("NETWORK", nativeFailure.searchErrorCode);
        assertEquals(domainFailure.searchErrorCode, nativeFailure.searchErrorCode);
        assertEquals(domainFailure.searchErrorMessage, nativeFailure.searchErrorMessage);
        assertFalse(nativeFailure.searchErrorMessage.contains("private"));
        assertFalse(nativeFailure.searchErrorMessage.contains("secret"));
        assertNull(nativeFailure.msg);
        assertNull(nativeFailure.movie);
    }

    @Test public void realEmptyListAndNoMatchAreNotReportedAsRequestFailure() {
        AbsXml empty = SearchOutcomePolicy.decodeNative(NativeJson.list(new JsonArray(), 1, false), "fty_wogg", "a");
        assertFalse(SearchOutcomePolicy.isFailure(empty));
        assertEquals(0, empty.movie.videoList.size());
        assertFalse(SearchOutcomePolicy.isFailure(SearchOutcomePolicy.failure("fty_wogg", "a", "NO_MATCH")));
    }

    @Test public void malformedPayloadAndUnknownErrorCannotBecomeSuccessfulEmptyOrRawText() {
        for (String value : new String[]{"", "<html>captcha</html>", "[]", "null", "{}", "{\"list\":null}", "{\"list\":\"private\"}"}) {
            AbsXml result = SearchOutcomePolicy.decodeNative(value, "fty_wogg", "a");
            assertTrue(SearchOutcomePolicy.isFailure(result));
            assertEquals("INVALID_RESPONSE", result.searchErrorCode);
        }
        JsonObject unknown = new JsonObject(); unknown.addProperty("error_code", "Cookie=secret"); unknown.addProperty("message", "private");
        AbsXml result = SearchOutcomePolicy.decodeNative(unknown.toString(), "fty_wogg", "a");
        assertEquals("SOURCE_UNAVAILABLE", result.searchErrorCode);
        assertFalse(result.searchErrorMessage.contains("secret"));
        assertFalse(result.searchErrorMessage.contains("private"));
    }
}
