package com.github.tvbox.osc.source;

import com.google.gson.JsonObject;

/** 领域错误只携带公开说明，不把异常、Cookie或上游响应透传给UI与日志。 */
public final class DomainError {
    public enum Code {
        NETWORK, SOURCE_UNAVAILABLE, NO_MATCH, AUTH_REQUIRED, SESSION_EXPIRED,
        ENTITLEMENT_REQUIRED, UNSUPPORTED, CANCELLED, INVALID_RESPONSE
    }

    private final Code code;
    private final String provider;

    public DomainError(Code code) { this(code, ""); }
    public DomainError(Code code, String provider) {
        this.code = code;
        this.provider = provider == null ? "" : provider;
    }
    public Code getCode() { return code; }
    public String getProvider() { return provider; }
    public boolean isRetryable() { return code == Code.NETWORK || code == Code.SOURCE_UNAVAILABLE; }
    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        result.addProperty("errorCode", code.name());
        if (!provider.isEmpty()) result.addProperty("provider", provider);
        return result;
    }
}
