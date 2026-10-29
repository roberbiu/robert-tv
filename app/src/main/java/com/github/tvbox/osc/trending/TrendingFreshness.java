package com.github.tvbox.osc.trending;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** 源数据已旧时不能通过重复GET续命。 */
public final class TrendingFreshness {
    public static final long RECENT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;

    private TrendingFreshness() {
    }

    public static long dataTime(String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return 0;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(date, position);
        return parsed != null && position.getIndex() == date.length() ? parsed.getTime() : 0;
    }

    public static boolean isHistorical(TrendingSnapshot snapshot, long now) {
        // 月日精度以首次观察该内容的年份为锚，跨年重复GET不能把同一旧榜续成新榜。
        long anchor = dataTime(snapshot.dataDate, snapshot.firstSeenAt);
        if (anchor == 0) anchor = snapshot.firstSeenAt;
        return anchor <= 0 || anchor > now + 24L * 60 * 60 * 1000 || now - anchor > RECENT_WINDOW_MS;
    }

    public static long dataTime(String date, long observedAt) {
        if (date == null || !date.matches("--\\d{2}-\\d{2}")) return dataTime(date);
        Calendar reference = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"), Locale.ROOT);
        reference.setTimeInMillis(observedAt);
        int year = reference.get(Calendar.YEAR);
        long candidate = dataTime(year + date.substring(1));
        // 网站仅给月日，按最近的非未来日期判断新鲜度，显示仍保留月日精度。
        if (candidate > observedAt) candidate = dataTime((year - 1) + date.substring(1));
        return candidate;
    }

    public static long firstSeen(TrendingSnapshot previous, String fingerprint, long fetchedAt) {
        if (previous != null && fingerprint.equals(previous.fingerprint)) return previous.firstSeenAt;
        return fetchedAt;
    }
}
