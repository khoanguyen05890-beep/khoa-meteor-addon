package com.khoa.automationbot;

import java.util.ArrayList;
import java.util.List;

/**
 * Giá trị cấu hình dùng chung cho các task. Không còn đọc/ghi file JSON:
 * các module Meteor ghi settings của chúng vào đây mỗi tick (xem KhoaModule).
 */
public class Config {
    private static final Config instance = new Config();

    public int scanRadius = 16;
    public int mineRadius = 16;
    public int itemRadius = 12;
    public int actionDelay = 2;       // tick giữa các hành động
    public String mode = "NORMAL";    // SAFE / NORMAL / FAST
    public String lang = "en";        // font của Meteor không có dấu tiếng Việt => dùng en
    public boolean autoTool = true;
    public boolean onlyMineSelected = true;
    public boolean autoCollect = true;
    public boolean collectOnlyListed = false;
    public List<String> minerBlocks = new ArrayList<>();
    public List<String> collectItems = new ArrayList<>();
    public List<String> ignoreItems = new ArrayList<>();

    public static Config get() { return instance; }

    /** Số tick giữa hai hành động, phụ thuộc chế độ. */
    public int delay() {
        int d = actionDelay;
        if ("SAFE".equals(mode)) d += 3;
        else if ("FAST".equals(mode)) d -= 1;
        return Math.max(1, d);
    }

    /** Độ/tick khi xoay camera. */
    public float turnSpeed() {
        return switch (mode) {
            case "SAFE" -> 15f;
            case "FAST" -> 120f;
            default -> 40f;
        };
    }
}
