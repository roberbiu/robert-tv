package com.github.tvbox.osc.util;

import android.util.Log;
import com.github.tvbox.osc.BuildConfig;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class LOG {
    private static String TAG = "RobertTV-runtime";
    private static final int MAX_LOG_LENGTH = 3000;

    public static void e(String msg) {
        if (BuildConfig.DEBUG) Log.e(TAG, "" + msg);
    }

    public static void i(String msg) {
        if (BuildConfig.DEBUG) Log.i(TAG, "" + msg);
    }

    public static void longI(String prefix, String msg) {
        longLog(Log.INFO, prefix, msg);
    }

    public static void longE(String prefix, String msg) {
        longLog(Log.ERROR, prefix, msg);
    }

    private static void longLog(int priority, String prefix, String msg) {
        // 正式包不输出来源响应及播放地址，反馈问题时也不应公开原始调试日志。
        if (!BuildConfig.DEBUG) return;
        String text = msg == null ? "null" : msg;
        String title = prefix == null ? "" : prefix;
        int length = text.length();
        if (length <= MAX_LOG_LENGTH) {
            Log.println(priority, TAG, title + text);
            return;
        }
        int count = (length + MAX_LOG_LENGTH - 1) / MAX_LOG_LENGTH;
        for (int i = 0; i < count; i++) {
            int start = i * MAX_LOG_LENGTH;
            int end = Math.min(start + MAX_LOG_LENGTH, length);
            Log.println(priority, TAG, title + "[" + (i + 1) + "/" + count + "] " + text.substring(start, end));
        }
    }
}
