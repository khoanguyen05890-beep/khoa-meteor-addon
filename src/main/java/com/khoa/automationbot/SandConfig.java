package com.khoa.automationbot;

import java.util.ArrayList;
import java.util.List;

/** Cấu hình riêng của Auto Sand Bot (module KhoaAutoSandBot ghi vào đây mỗi tick). */
public final class SandConfig {
    private static final SandConfig instance = new SandConfig();
    public static SandConfig get() { return instance; }

    public int radius = 24;
    public boolean amethyst3x3 = true;
    public String shovelKeyword = "amethyst";

    // kho đồ đầy
    public boolean keepMode = false;                 // false = bán /order, true = cất vào shulker -> ender chest
    public String orderCommand = "order sand";
    public int reserveSlots = 3;                     // còn < số ô trống này thì coi là đầy
    public boolean pickupEnder = true;               // đập ender chest lấy lại sau khi cất

    // sửa xẻng
    public boolean autoRepair = true;
    public int repairPercent = 10;
    public List<String> expCategory = new ArrayList<>(List.of("totem"));
    public List<String> expKeywords = new ArrayList<>(List.of("exp", "experience", "bottle o' enchanting", "kinh nghiệm", "kinh nghiem"));
    public int expAmount = 64;

    // shop: shulker / ender chest nằm trong danh mục End Stone
    public List<String> endCategory = new ArrayList<>(List.of("end stone", "endstone"));
}
