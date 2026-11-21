package com.khoa.automationbot;

import java.util.ArrayList;
import java.util.List;

/**
 * Giá trị cấu hình dùng chung cho các task. Không còn đọc/ghi file JSON:
 * các module Meteor ghi settings của chúng vào đây mỗi tick (xem KhoaModule).
 */
public class Config {
    private static final Config instance = new Config();

    public static final String DEFAULT_PRICE_REGEX =
            "(?i)(?:price|giá|gia|cost)\\s*[:：]?\\s*\\$?\\s*([0-9][0-9.,]*\\s*[kmb]?)|\\$\\s*([0-9][0-9.,]*\\s*[kmb]?)";

    // ---- chung ----
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

    // ---- miner: quét toàn bộ chiều cao + đào xuyên ----
    public boolean fullHeight = true;
    public int mineMinY = -64;
    public int mineMaxY = 320;
    public boolean digThrough = true;

    // ---- sinh tồn (đói -> ăn / mua ở /shop) ----
    public boolean autoEat = true;
    public int hungerThreshold = 14;
    public String shopCommand = "shop";
    public List<String> foodKeywords = new ArrayList<>(List.of("cooked beef", "steak", "thịt bò nướng", "thit bo nuong", "bò nướng"));
    public List<String> categoryKeywords = new ArrayList<>(List.of("food", "thức ăn", "thuc an", "đồ ăn", "do an", "thực phẩm", "thuc pham"));
    public List<String> eatItems = new ArrayList<>();
    public int foodAmount = 16;

    // ---- cung cấp vật liệu (rương -> /ah) ----
    public boolean autoSupply = true;
    public boolean useStorage = true;
    public int storageRadius = 32;
    public boolean useAh = true;
    public String ahCommand = "ah {item}";
    public String ahNameMode = "ID_UNDERSCORE";   // ID_UNDERSCORE / ID_SPACE / DISPLAY_NAME
    public double ahMaxPrice = 0;                 // 0 = không giới hạn
    public boolean ahPerUnit = false;
    public int ahMaxBuys = 5;
    public int supplyMax = 64;
    public String priceRegex = DEFAULT_PRICE_REGEX;
    public List<String> confirmKeywords = new ArrayList<>(List.of(
            "confirm", "xác nhận", "xac nhan", "accept", "đồng ý", "dong y", "purchase", "buy", "mua", "yes"));

    // ---- builder ----
    public boolean autoLayer = true;
    public int layerOffset = 0;
    public boolean autoPillar = true;
    public List<String> scaffoldItems = new ArrayList<>(List.of("minecraft:cobblestone", "minecraft:dirt"));

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
