package com.khoa.automationbot;

public final class Stats {
    private Stats() {}
    public static long mined, collected, built;
    public static void reset() { mined = 0; collected = 0; built = 0; }
}
