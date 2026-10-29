package com.github.tvbox.osc.cloud;

import java.util.Locale;

/** 网盘官方请求边界；不从来源目录接收登录端点。 */
public enum CloudProvider {
    QUARK("quark", "夸克网盘", "https://drive-pc.quark.cn/1/clouddrive/", "ucpro",
            "https://uop.quark.cn/cas/ajax/", "386", "532", "https://pan.quark.cn/"),
    UC("uc", "UC网盘", "https://pc-api.uc.cn/1/clouddrive/", "UCBrowser",
            "https://api.open.uc.cn/cas/ajax/", "381", "381", "https://drive.uc.cn/");

    public final String id;
    public final String displayName;
    public final String apiBase;
    public final String product;
    public final String loginBase;
    public final String tokenClientId;
    public final String scanClientId;
    public final String webBase;

    CloudProvider(String id, String displayName, String apiBase, String product,
                  String loginBase, String tokenClientId, String scanClientId, String webBase) {
        this.id = id;
        this.displayName = displayName;
        this.apiBase = apiBase;
        this.product = product;
        this.loginBase = loginBase;
        this.tokenClientId = tokenClientId;
        this.scanClientId = scanClientId;
        this.webBase = webBase;
    }

    public static CloudProvider fromId(String value) {
        if (value != null) for (CloudProvider provider : values()) {
            if (provider.id.equals(value.toLowerCase(Locale.ROOT))) return provider;
        }
        throw new IllegalArgumentException("不支持的网盘类型");
    }
}
