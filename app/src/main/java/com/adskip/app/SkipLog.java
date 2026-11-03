package com.adskip.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** 最近动作的环形记录，用于在主界面展示，帮助用户确认是否生效。 */
public final class SkipLog {

    private static final int MAX = 12;
    private static final ArrayList<String> ITEMS = new ArrayList<>();

    private SkipLog() {}

    public static void add(String what) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        synchronized (ITEMS) {
            ITEMS.add(0, time + "  " + what);
            while (ITEMS.size() > MAX) {
                ITEMS.remove(ITEMS.size() - 1);
            }
        }
    }

    public static String dump() {
        synchronized (ITEMS) {
            if (ITEMS.isEmpty()) {
                return "暂无记录";
            }
            StringBuilder sb = new StringBuilder();
            for (String s : ITEMS) {
                sb.append(s).append('\n');
            }
            return sb.toString().trim();
        }
    }
}
