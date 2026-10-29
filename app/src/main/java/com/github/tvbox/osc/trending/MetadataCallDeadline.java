package com.github.tvbox.osc.trending;

import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** 单次调用涵盖连接、重定向和整个响应体；慢滴流不能靠刷新readTimeout续命。 */
final class MetadataCallDeadline {
    private MetadataCallDeadline() {
    }

    static Call create(OkHttpClient client, Request request, long remainingMillis) {
        if (remainingMillis <= 0) throw new IllegalArgumentException("请求预算已耗尽");
        return client.newBuilder().callTimeout(remainingMillis, TimeUnit.MILLISECONDS).build().newCall(request);
    }
}
