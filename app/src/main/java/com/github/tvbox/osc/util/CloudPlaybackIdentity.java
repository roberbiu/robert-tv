package com.github.tvbox.osc.util;

import com.github.tvbox.osc.cloud.CloudReference;

/** 续播只依赖公开的分享与文件身份，排序、清晰度和临时转存ID不影响进度。 */
public final class CloudPlaybackIdentity {
    private CloudPlaybackIdentity() { }

    public static String progressKey(CloudReference ref) {
        if (ref == null || !ref.isFile()) throw new IllegalArgumentException("必须先选择实际文件");
        return "cloud-v1:" + part(ref.sourceId) + part(ref.provider) + part(ref.shareId) + part(ref.fileId);
    }

    private static String part(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe;
    }
}
