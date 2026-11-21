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

    // ---- kho đồ: vứt đồ không cần thiết khi cần chỗ trống ----
    public boolean autoDrop = true;
    public int minFreeSlots = 2;
    public List<String> keepItems = new ArrayList<>();
    /** Item (id) mà schematic đang cần - builder tự cập nhật, không bao giờ bị vứt. */
    public java.util.Set<String> protectedItems = new java.util.HashSet<>();

    // ---- builder ----
    public boolean followLayer = true;      // xây đúng layer Litematica đang hiển thị
    public boolean advanceLayer = true;     // xong layer thì tự chuyển layer Litematica lên
    public boolean autoOrient = true;       // tự chỉnh hướng khi đặt block (stairs, log, slab, furnace...)
    public boolean usePearl = true;         // ném ender pearl để lên layer đang xây
    public boolean autoLayer = true;
    public int layerOffset = 0;
    public boolean autoPillar = true;
    public boolean autoBridge = true;       // đặt block liên tục (cầu/cột) tới chỗ không đi bộ tới được
    public boolean cleanScaffold = true;    // đập block tạm sau khi xong
    public int placeDelay = 8;              // tick giữa 2 lần đặt block (server có anti-cheat: đặt chậm)
    public int placeJitter = 3;             // ngẫu nhiên +- tick cho giống người
    public double placeReach = 3.0;         // đứng cách chỗ đặt tối đa (mắt -> block), vanilla cho 4.5 nhưng bot hack hay đứng xa
    public boolean fluidWalls = true;       // chỉ đổ nước/lava khi block chặn xung quanh đã đặt xong
    public boolean placeFluids = true;      // đổ nước/lava theo schematic (cần xô)
    public List<String> scaffoldItems = new ArrayList<>(List.of("minecraft:cobblestone", "minecraft:dirt"));

    public static Config get() { return instance; }

    /** Số tick giữa hai hành động, phụ thuộc chế độ. */
    public int delay() {
        int d = actionDelay;
        if ("SAFE".equals(mode)) d += 3;
        else if ("FAST".equals(mode)) d -= 1;
        return Math.max(1, d);
    }

    private final java.util.Random rnd = new java.util.Random();

    /** Số tick chờ giữa hai lần đặt block (chậm + ngẫu nhiên nhẹ). */
    public int placeWait() {
        int j = placeJitter <= 0 ? 0 : rnd.nextInt(placeJitter * 2 + 1) - placeJitter;
        return Math.max(delay(), placeDelay + j);
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
