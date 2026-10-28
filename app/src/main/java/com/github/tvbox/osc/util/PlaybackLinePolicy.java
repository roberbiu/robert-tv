package com.github.tvbox.osc.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** 默认优先已有直接媒体地址，避免把网页嗅探作为唯一的首次播放路径。 */
public final class PlaybackLinePolicy {
    private PlaybackLinePolicy() {
    }

    public static int priority(String flag, String url) {
        if (isDirectMediaUrl(url)) return 0;
        return flag != null && flag.toLowerCase(Locale.ROOT).contains("m3u8") ? 1 : 2;
    }

    public static boolean isDirectMediaUrl(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String path = uri.getPath();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && path != null
                    && path.toLowerCase(Locale.ROOT).matches(".*\\.(m3u8|mpd|mp4|mkv|webm|flv|ts)");
        } catch (URISyntaxException error) {
            return false;
        }
    }
}
