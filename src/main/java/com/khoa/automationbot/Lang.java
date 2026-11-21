package com.khoa.automationbot;

public final class Lang {
    private Lang() {}
    /** Trả về chuỗi tiếng Việt hoặc tiếng Anh tuỳ theo cấu hình. */
    public static String t(String vi, String en) {
        return "en".equals(Config.get().lang) ? en : vi;
    }
}
