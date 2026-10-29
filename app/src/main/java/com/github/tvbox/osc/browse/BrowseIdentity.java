package com.github.tvbox.osc.browse;

import com.github.tvbox.osc.util.VersionPolicy;

/** 信息不足时保留站内身份，避免把重名、未知年份或未知季的作品合并。 */
public final class BrowseIdentity {
    private BrowseIdentity() {
    }

    public static String key(ContentKind kind, String source, String id, String title, int year) {
        String normalized = VersionPolicy.normalizeTitle(title);
        boolean explicitSeason = normalized.matches(".*第[0-9]+季.*");
        if (year > 0 && !normalized.isEmpty() && (kind == ContentKind.MOVIE || explicitSeason)) {
            return kind.name() + '|' + normalized + '|' + year;
        }
        return source + '|' + id;
    }
}
