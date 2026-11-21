package com.khoa.automationbot.modules;

import com.khoa.automationbot.AutoMiner;
import com.khoa.automationbot.Config;
import com.khoa.automationbot.Task;
import com.khoa.automationbot.Tools;
import meteordevelopment.meteorclient.settings.*;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

public class KhoaAutoMiner extends KhoaModule {
    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Các block cần đào.")
        .defaultValue(new ArrayList<>(List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)))
        .build());

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("Bán kính quét/đào.")
        .defaultValue(16)
        .min(2)
        .sliderMax(64)
        .build());

    private final Setting<Boolean> fullHeight = sgGeneral.add(new BoolSetting.Builder()
        .name("scan-full-height")
        .description("Quét block từ mặt đất xuống tận bedrock và lên tới giới hạn độ cao (bán kính chỉ tính theo chiều ngang).")
        .defaultValue(true)
        .build());

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y")
        .description("Độ cao thấp nhất để quét (tự bị giới hạn bởi đáy thế giới/bedrock).")
        .defaultValue(-64)
        .range(-2032, 2032)
        .sliderRange(-64, 320)
        .build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Độ cao cao nhất để quét (tự bị giới hạn bởi giới hạn xây dựng).")
        .defaultValue(320)
        .range(-2032, 2032)
        .sliderRange(-64, 320)
        .build());

    private final Setting<Boolean> digThrough = sgGeneral.add(new BoolSetting.Builder()
        .name("dig-through")
        .description("Tự đào đường xuyên đá/đất tới block ở sâu (tránh dung nham, nước, bedrock).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> onlySelected = sgGeneral.add(new BoolSetting.Builder()
        .name("only-selected")
        .description("Chỉ đào block đã chọn (bỏ qua block bị vật cản che).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoTool = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-tool")
        .description("Tự chọn công cụ tốt nhất trên hotbar.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoCollect = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-collect")
        .description("Nhặt vật phẩm rơi sau khi đào.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> itemRadius = sgGeneral.add(new IntSetting.Builder()
        .name("item-radius")
        .description("Bán kính nhặt vật phẩm.")
        .defaultValue(12)
        .min(2)
        .sliderMax(32)
        .build());

    private final Setting<List<Item>> ignoreItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("ignore-items")
        .description("Không nhặt, và vứt đi khi túi đầy.")
        .defaultValue(new ArrayList<>(List.of(Items.COBBLESTONE, Items.DIRT)))
        .build());

    public KhoaAutoMiner() {
        super("khoa-auto-miner", "Tự đi tới, chọn công cụ và đào các block đã chọn.");
    }

    @Override protected Task createTask() { return new AutoMiner(); }

    @Override
    protected void applyConfig(Config c) {
        super.applyConfig(c);
        c.mineRadius = radius.get();
        c.fullHeight = fullHeight.get();
        c.mineMinY = minY.get();
        c.mineMaxY = maxY.get();
        c.digThrough = digThrough.get();
        c.onlyMineSelected = onlySelected.get();
        c.autoTool = autoTool.get();
        c.autoCollect = autoCollect.get();
        c.itemRadius = itemRadius.get();
        c.collectOnlyListed = false;
        c.minerBlocks = blocks.get().stream().map(Tools::blockId).toList();
        c.ignoreItems = ignoreItems.get().stream().map(Tools::itemId).toList();
        c.collectItems = new ArrayList<>();
    }
}
