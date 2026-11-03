package com.adskip.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 统一管理用户设置。 */
public final class Prefs {

    private static final String FILE = "adskip_prefs";

    public static final String KEY_ENABLED   = "enabled";
    public static final String KEY_DELAY     = "delay_ms";
    public static final String KEY_WINDOW    = "window_ms";
    public static final String KEY_STRATEGY  = "strategy";
    public static final String KEY_CORNER    = "corner";
    public static final String KEY_WHITELIST = "whitelist";

    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, true);
    }

    /** 检测到广告后等待多久再点，默认 300ms。 */
    public static int getDelay(Context c) {
        return sp(c).getInt(KEY_DELAY, 300);
    }

    /** 应用启动后多长时间内生效，默认 6000ms。 */
    public static int getWindow(Context c) {
        return sp(c).getInt(KEY_WINDOW, 6000);
    }

    /** 0=仅控件点击，1=+坐标点击，2=+返回键。 */
    public static int getStrategy(Context c) {
        return sp(c).getInt(KEY_STRATEGY, 2);
    }

    /** 0=右上，1=右下，2=左上，3=左下。 */
    public static int getCorner(Context c) {
        return sp(c).getInt(KEY_CORNER, 0);
    }

    /** 逗号分隔的包名列表，空表示全部应用。 */
    public static String getWhitelist(Context c) {
        String v = sp(c).getString(KEY_WHITELIST, "");
        return v == null ? "" : v;
    }
}
