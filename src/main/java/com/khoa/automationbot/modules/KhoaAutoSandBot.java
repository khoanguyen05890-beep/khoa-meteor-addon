package com.khoa.automationbot.modules;

import com.khoa.automationbot.AutoSand;
import com.khoa.automationbot.Config;
import com.khoa.automationbot.SandConfig;
import com.khoa.automationbot.Task;
import com.khoa.automationbot.Tools;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Sand Bot: tự dò cát, đào (xẻng "amethyst" = 3x3), vẽ "sợi chỉ" chỉ đường + khung sáng block đang đào.
 * Đầy kho: bán /order (giá cao nhất) hoặc cất cát vào shulker -> ender chest. Đói: /shop mua cà rốt vàng. Xẻng sắp hỏng: mua exp rồi sửa.
 */
public class KhoaAutoSandBot extends KhoaModule {
    public enum FullMode { SELL_ORDER, KEEP_SHULKER }

    // ---- cát ----
    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Các loại cát cần đào.")
        .defaultValue(new ArrayList<>(List.of(Blocks.SAND, Blocks.RED_SAND)))
        .build());

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("Bán kính dò cát theo chiều ngang (chỉ quét được phần chunk đã tải). Càng xa quét càng lâu.")
        .defaultValue(64)
        .min(4)
        .sliderMax(128)
        .build());

    private final Setting<Integer> scanHeight = sgGeneral.add(new IntSetting.Builder()
        .name("scan-height")
        .description("Quét ± số block theo chiều cao quanh người chơi.")
        .defaultValue(16)
        .min(2)
        .sliderMax(64)
        .build());

    private final Setting<Boolean> fastMine = sgGeneral.add(new BoolSetting.Builder()
        .name("fast-mine")
        .description("Đào nhanh: luôn chọn cát GẦN NHẤT, đào hết cát trong tầm với trước khi di chuyển, chuyển block ngay (không chờ), xoay camera nhanh, nhặt gộp một lượt.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> amethyst3x3 = sgGeneral.add(new BoolSetting.Builder()
        .name("amethyst-3x3")
        .description("Xẻng có tên chứa từ khoá (mặc định 'amethyst') thì đào 3x3: ưu tiên chỗ có nhiều cát nhất và cầm xẻng đó.")
        .defaultValue(true)
        .build());

    private final Setting<String> shovelKeyword = sgGeneral.add(new StringSetting.Builder()
        .name("shovel-keyword")
        .description("Từ khoá trong tên xẻng 3x3.")
        .defaultValue("amethyst")
        .build());

    private final Setting<Integer> itemRadius = sgGeneral.add(new IntSetting.Builder()
        .name("item-radius")
        .description("Bán kính nhặt cát rơi.")
        .defaultValue(10)
        .min(2)
        .sliderMax(32)
        .build());

    // ---- đầy kho ----
    private final SettingGroup sgFull = settings.createGroup("Full inventory");

    private final Setting<FullMode> fullMode = sgFull.add(new EnumSetting.Builder<FullMode>()
        .name("full-mode")
        .description("SELL_ORDER: /order sand, chọn giá cao nhất, bỏ cát, ESC, xác nhận. KEEP_SHULKER: mua shulker + ender chest ở /shop, đặt, bỏ cát vào shulker, đập shulker, cất vào ender chest.")
        .defaultValue(FullMode.SELL_ORDER)
        .build());

    private final Setting<String> orderCommand = sgFull.add(new StringSetting.Builder()
        .name("order-command")
        .description("Lệnh mở danh sách đơn thu mua cát (không có dấu /).")
        .defaultValue("order sand")
        .build());

    private final Setting<Integer> reserveSlots = sgFull.add(new IntSetting.Builder()
        .name("reserve-slots")
        .description("Còn ít hơn số ô trống này thì coi là đầy kho (nên >= min-free-slots để còn chỗ mua đồ).")
        .defaultValue(3)
        .min(1)
        .sliderMax(8)
        .build());

    private final Setting<Boolean> pickupEnder = sgFull.add(new BoolSetting.Builder()
        .name("pickup-ender-chest")
        .description("KEEP_SHULKER: đập ender chest lấy lại sau khi cất (không có Silk Touch thì chỉ rơi obsidian và lần sau phải mua lại).")
        .defaultValue(true)
        .build());

    private final Setting<List<String>> endCategory = sgFull.add(new StringListSetting.Builder()
        .name("end-category")
        .description("Từ khoá danh mục chứa shulker / ender chest trong /shop (End Stone).")
        .defaultValue(new ArrayList<>(List.of("end stone", "endstone")))
        .build());

    // ---- sửa xẻng ----
    private final SettingGroup sgRepair = settings.createGroup("Repair");

    private final Setting<Boolean> autoRepair = sgRepair.add(new BoolSetting.Builder()
        .name("auto-repair")
        .description("Xẻng sắp hỏng: /shop mua exp, rồi ném exp để Mending sửa (xẻng phải có Mending).")
        .defaultValue(true)
        .build());

    private final Setting<Integer> repairPercent = sgRepair.add(new IntSetting.Builder()
        .name("repair-percent")
        .description("Sửa khi độ bền còn <= % này.")
        .defaultValue(10)
        .range(1, 90)
        .sliderRange(1, 50)
        .build());

    private final Setting<List<String>> expCategory = sgRepair.add(new StringListSetting.Builder()
        .name("exp-category")
        .description("Từ khoá danh mục chứa exp trong /shop (icon totem).")
        .defaultValue(new ArrayList<>(List.of("totem")))
        .build());

    private final Setting<List<String>> expKeywords = sgRepair.add(new StringListSetting.Builder()
        .name("exp-keywords")
        .description("Từ khoá tên món exp trong /shop.")
        .defaultValue(new ArrayList<>(List.of("exp", "experience", "bottle o' enchanting", "kinh nghiệm", "kinh nghiem")))
        .build());

    private final Setting<Integer> expAmount = sgRepair.add(new IntSetting.Builder()
        .name("exp-amount")
        .description("Số chai exp mua mỗi lần (bấm +64).")
        .defaultValue(64)
        .min(1)
        .sliderMax(64)
        .build());

    // ---- đồ ăn ----
    private final Setting<Item> foodItem = sgSurvival.add(new ItemSetting.Builder()
        .name("food-item")
        .description("Món mua ở /shop khi hết đồ ăn (mặc định cà rốt vàng).")
        .defaultValue(Items.GOLDEN_CARROT)
        .build());

    private final Setting<Integer> foodBuyAmount = sgSurvival.add(new IntSetting.Builder()
        .name("food-buy-amount")
        .description("Số đồ ăn mua mỗi lần (bấm +64).")
        .defaultValue(64)
        .min(1)
        .sliderMax(64)
        .build());

    // ---- hiển thị ----
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> renderThread = sgRender.add(new BoolSetting.Builder()
        .name("render-thread")
        .description("Vẽ sợi chỉ chỉ hướng đi của bot tới block đang đào.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> renderBox = sgRender.add(new BoolSetting.Builder()
        .name("render-box")
        .description("Làm sáng block đang đào (và vùng 3x3).")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> threadColor = sgRender.add(new ColorSetting.Builder()
        .name("thread-color")
        .description("Màu sợi chỉ.")
        .defaultValue(new SettingColor(255, 210, 60, 255))
        .build());

    private final Setting<SettingColor> targetSide = sgRender.add(new ColorSetting.Builder()
        .name("target-side-color")
        .description("Màu mặt block đang đào.")
        .defaultValue(new SettingColor(255, 60, 60, 70))
        .build());

    private final Setting<SettingColor> targetLine = sgRender.add(new ColorSetting.Builder()
        .name("target-line-color")
        .description("Màu viền block đang đào.")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .build());

    private final Setting<SettingColor> areaSide = sgRender.add(new ColorSetting.Builder()
        .name("area-side-color")
        .description("Màu mặt các block trong vùng 3x3.")
        .defaultValue(new SettingColor(255, 210, 60, 40))
        .build());

    private final Setting<SettingColor> areaLine = sgRender.add(new ColorSetting.Builder()
        .name("area-line-color")
        .description("Màu viền các block trong vùng 3x3.")
        .defaultValue(new SettingColor(255, 210, 60, 200))
        .build());

    public KhoaAutoSandBot() {
        super("khoa-auto-sand", "Tự dò cát, đào (xẻng amethyst = 3x3), bán/cất khi đầy kho, tự ăn và tự sửa xẻng.");
    }

    @Override protected Task createTask() { return new AutoSand(); }

    @Override
    protected boolean deferHunger() {
        return task instanceof AutoSand s && s.busy();
    }

    @Override
    protected void applyConfig(Config c) {
        super.applyConfig(c);
        List<String> ids = blocks.get().stream().map(Tools::blockId).toList();
        c.minerBlocks = new ArrayList<>(ids);
        c.collectOnlyListed = true;                       // chỉ nhặt cát, không nhặt rác
        c.collectItems = new ArrayList<>(ids);
        c.ignoreItems = new ArrayList<>();
        c.autoCollect = true;
        c.autoTool = true;
        c.scaffoldItems = new ArrayList<>();               // block rác (cobblestone, dirt...) đều bị vứt; leo thì dùng cát
        c.protectedItems = new java.util.HashSet<>(List.of("minecraft:experience_bottle"));
        c.itemRadius = itemRadius.get();

        // đói -> /shop -> thịt bò (icon danh mục) -> cà rốt vàng -> +64 -> xác nhận -> ăn
        String food = Tools.itemId(foodItem.get());
        List<String> eat = new ArrayList<>();
        eat.add(food);
        for (String e : c.eatItems) if (!eat.contains(e)) eat.add(e);
        c.eatItems = eat;
        List<String> kw = new ArrayList<>(List.of("golden carrot", "cà rốt vàng", "ca rot vang"));
        if (foodItem.get() != Items.GOLDEN_CARROT) kw.add(foodItem.get().getName().getString().toLowerCase());
        kw.addAll(c.foodKeywords);
        c.foodKeywords = kw;
        c.foodAmount = foodBuyAmount.get();

        SandConfig s = SandConfig.get();
        s.radius = radius.get();
        s.scanHeight = scanHeight.get();
        s.fast = fastMine.get();
        s.amethyst3x3 = amethyst3x3.get();
        s.shovelKeyword = shovelKeyword.get();
        s.keepMode = fullMode.get() == FullMode.KEEP_SHULKER;
        s.orderCommand = orderCommand.get();
        s.reserveSlots = reserveSlots.get();
        s.pickupEnder = pickupEnder.get();
        s.endCategory = new ArrayList<>(endCategory.get());
        s.autoRepair = autoRepair.get();
        s.repairPercent = repairPercent.get();
        s.expCategory = new ArrayList<>(expCategory.get());
        s.expKeywords = new ArrayList<>(expKeywords.get());
        s.expAmount = expAmount.get();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!(task instanceof AutoSand sand) || mc.player == null || mc.world == null) return;

        if (renderThread.get()) {
            List<Vec3d> pts = sand.thread(mc);
            for (int i = 0; i + 1 < pts.size(); i++) {
                Vec3d a = pts.get(i), b = pts.get(i + 1);
                event.renderer.line(a.x, a.y, a.z, b.x, b.y, b.z, threadColor.get());
            }
        }

        if (renderBox.get()) {
            for (BlockPos b : sand.area()) event.renderer.box(b, areaSide.get(), areaLine.get(), ShapeMode.Both, 0);
            BlockPos t = sand.targetPos();
            if (t != null) event.renderer.box(t, targetSide.get(), targetLine.get(), ShapeMode.Both, 0);
        }
    }
}
