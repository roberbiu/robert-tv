package com.github.tvbox.osc.source.wogg;

import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.CloudReference;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 本地适配器输出本项目AbsJson字段，而不是依赖另一fork的Result模型。 */
public final class NativeJson {
    private NativeJson() { }
    public static String list(JsonArray list, int page, boolean hasMore) {
        JsonObject result = new JsonObject(); result.addProperty("code", 1); result.addProperty("page", page);
        result.addProperty("pagecount", hasMore ? page + 1 : page); result.add("list", list);
        return result.toString();
    }
    public static JsonObject video(String id, String title, String picture, String note, String type) {
        JsonObject result = new JsonObject();
        result.addProperty("vod_id", id); result.addProperty("vod_name", title); result.addProperty("vod_pic", picture);
        result.addProperty("vod_remarks", note); result.addProperty("type_name", type == null ? "" : type);
        return result;
    }
    public static String failure(CloudException error, CloudReference reference) {
        JsonObject result = new JsonObject(); result.addProperty("parse", 0); result.addProperty("url", "");
        result.addProperty("error_code", error.code.name()); result.addProperty("message", error.getMessage());
        if (reference != null) { result.addProperty("provider", reference.provider); result.addProperty("cloud_ref", reference.encode()); }
        else if (error.provider != null) result.addProperty("provider", error.provider.id);
        if (error.code == CloudException.Code.AUTH_REQUIRED || error.code == CloudException.Code.SESSION_EXPIRED || (error.code == CloudException.Code.PRIVATE_STORAGE && reference != null)) result.addProperty("cloud_action", "authorize");
        return result.toString();
    }
    public static String label(String text) { return text.replace('$', '＄').replace('#', '＃'); }
}
