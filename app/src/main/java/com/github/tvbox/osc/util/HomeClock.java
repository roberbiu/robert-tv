package com.github.tvbox.osc.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 首页统一显示北京时间，不受设备默认时区影响。 */
public final class HomeClock {
    private HomeClock() {
    }

    public static String format(long epochMillis) {
        // Locale 只决定日期语言，时区必须单独指定；不修改设备或 JVM 的默认值。
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy/MM/dd  E  HH:mm", Locale.CHINA);
        formatter.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return formatter.format(new Date(epochMillis));
    }
}
