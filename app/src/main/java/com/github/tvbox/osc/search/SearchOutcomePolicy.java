package com.github.tvbox.osc.search;

import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.ContentPolicy;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 搜索空结果与失败分离；只展示固定中文和白名单错误码。 */
public final class SearchOutcomePolicy {
    private static final Set<String> CODES = new HashSet<>(Arrays.asList("NETWORK", "TIMEOUT", "SOURCE_UNAVAILABLE",
            "AUTH_REQUIRED", "SESSION_EXPIRED", "ENTITLEMENT_REQUIRED", "STORAGE_FULL", "QR_EXPIRED",
            "CANCELLED", "UNSUPPORTED", "INVALID_RESPONSE", "OWNERSHIP_UNKNOWN", "PRIVATE_STORAGE", "NO_MATCH"));
    private SearchOutcomePolicy() { }

    public static AbsXml decodeNative(String json, String sourceKey, String token) {
        try {
            if (json == null || json.length() > 1024 * 1024) return failure(sourceKey, token, "INVALID_RESPONSE");
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) return failure(sourceKey, token, "INVALID_RESPONSE");
            JsonObject root = element.getAsJsonObject();
            JsonElement error = root.has("error_code") ? root.get("error_code") : root.get("errorCode");
            if (error != null) {
                if (!error.isJsonPrimitive() || !error.getAsJsonPrimitive().isString()) return failure(sourceKey, token, "INVALID_RESPONSE");
                return failure(sourceKey, token, error.getAsString());
            }
            if (!root.has("list") || !root.get("list").isJsonArray()) return failure(sourceKey, token, "INVALID_RESPONSE");
            // 真实共用模型转换，total缺省0不阻断有效列表。
            AbsJson model = new Gson().fromJson(root, AbsJson.class);
            AbsXml data = model.toAbsXml();
            applyMetadata(data, sourceKey, token);
            return data;
        } catch (RuntimeException invalid) { return failure(sourceKey, token, "INVALID_RESPONSE"); }
    }

    public static void applyMetadata(AbsXml data, String sourceKey, String token) {
        data.sourceKey = sourceKey;
        data.searchToken = token;
        if (data.movie != null) {
            data.movie.videoList = allowedVideos(data.movie.videoList);
            for (Movie.Video video : data.movie.videoList) video.sourceKey = sourceKey;
        }
    }

    public static List<Movie.Video> allowedVideos(List<Movie.Video> input) {
        List<Movie.Video> allowed = new ArrayList<>();
        if (input != null) for (Movie.Video video : input) {
            if (video != null && ContentPolicy.isAllowedCategory(video.type)) allowed.add(video);
        }
        return allowed;
    }

    public static AbsXml failure(String sourceKey, String token, String rawCode) {
        AbsXml data = new AbsXml(); data.sourceKey = sourceKey; data.searchToken = token;
        String code = safeCode(rawCode);
        if ("NO_MATCH".equals(code)) return data;
        data.searchErrorCode = code;
        data.searchErrorMessage = message(code);
        return data;
    }

    public static String safeCode(String value) { return value != null && CODES.contains(value) ? value : "SOURCE_UNAVAILABLE"; }
    public static boolean isFailure(AbsXml data) { return data != null && data.searchErrorCode != null && !data.searchErrorCode.isEmpty(); }
    public static String message(String code) {
        switch (safeCode(code)) {
            case "NETWORK": return "网络请求失败，可稍后重试";
            case "TIMEOUT": return "此来源搜索超时，可稍后重试";
            case "AUTH_REQUIRED": return "此来源当前请求需要授权，搜索不会自动打开登录";
            case "SESSION_EXPIRED": return "此来源登录状态已失效，可选择其他来源";
            case "ENTITLEMENT_REQUIRED": return "当前账号权益不支持此请求";
            case "STORAGE_FULL": return "网盘空间不足，可选择其他来源";
            case "PRIVATE_STORAGE": return "无法安全读取本机网盘状态";
            case "OWNERSHIP_UNKNOWN": return "临时文件归属未确认，请选择其他来源";
            case "QR_EXPIRED": return "授权等待已过期";
            case "UNSUPPORTED": return "此来源当前搜索方式暂未支持";
            case "CANCELLED": return "此来源搜索已取消，可重试";
            case "INVALID_RESPONSE": return "此来源返回的数据无法识别，可重试";
            case "NO_MATCH": return "此来源未找到匹配内容";
            default: return "此来源暂不可用，可稍后重试";
        }
    }
}
