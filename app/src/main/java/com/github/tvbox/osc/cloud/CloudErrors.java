package com.github.tvbox.osc.cloud;

import com.google.gson.JsonObject;

import java.util.Locale;

/** 仅官方账号API的明确登录错误可注销会话；分享/媒体票据错误不等于退出登录。 */
public final class CloudErrors {
    private CloudErrors() { }
    public static CloudException.Code classify(int httpStatus, JsonObject json) {
        String message = "";
        for (String key : new String[]{"message", "msg", "error"}) try {
            if (json.has(key) && json.get(key).isJsonPrimitive()) message += " " + json.get(key).getAsString().toLowerCase(Locale.ROOT);
        } catch (RuntimeException ignored) { }
        if (message.contains("stoken") || message.contains("share token") || message.contains("分享已失效") || message.contains("分享不存在"))
            return CloudException.Code.NO_MATCH;
        if (httpStatus == 401 || message.contains("未登录") || message.contains("请先登录")
                || message.contains("登录失效") || message.contains("登录已过期") || message.contains("not login") || message.contains("login expired"))
            return CloudException.Code.SESSION_EXPIRED;
        if (httpStatus == 429 || httpStatus >= 500) return CloudException.Code.NETWORK;
        if (httpStatus == 404 || message.contains("文件不存在") || message.contains("文件已删除") || message.contains("file not found")) return CloudException.Code.NO_MATCH;
        if (message.contains("空间不足") || message.contains("容量不足") || message.contains("quota")) return CloudException.Code.STORAGE_FULL;
        if (message.contains("会员") || message.contains("权益") || message.contains("vip")) return CloudException.Code.ENTITLEMENT_REQUIRED;
        if (httpStatus < 200 || httpStatus >= 300) return CloudException.Code.INVALID_RESPONSE;
        try {
            if (json.has("code") && json.get("code").getAsInt() != 0) return CloudException.Code.INVALID_RESPONSE;
            if (json.has("success") && !json.get("success").getAsBoolean()) return CloudException.Code.INVALID_RESPONSE;
        } catch (RuntimeException ignored) { return CloudException.Code.INVALID_RESPONSE; }
        return null;
    }
}
