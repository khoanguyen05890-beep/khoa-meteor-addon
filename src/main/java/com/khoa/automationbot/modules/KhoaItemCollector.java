package com.khoa.automationbot.modules;

import com.khoa.automationbot.Config;
import com.khoa.automationbot.ItemCollector;
import com.khoa.automationbot.Task;
import com.khoa.automationbot.Tools;
import meteordevelopment.meteorclient.settings.*;
import net.minecraft.item.Item;

import java.util.ArrayList;
import java.util.List;

public class KhoaItemCollector extends KhoaModule {
    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("Bán kính nhặt vật phẩm.")
        .defaultValue(12)
        .min(2)
        .sliderMax(32)
        .build());

    private final Setting<Boolean> onlyListed = sgGeneral.add(new BoolSetting.Builder()
        .name("only-listed")
        .description("Chỉ nhặt vật phẩm trong danh sách 'items'.")
        .defaultValue(false)
        .build());

    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Vật phẩm ưu tiên nhặt.")
        .defaultValue(new ArrayList<>())
        .build());

    private final Setting<List<Item>> ignoreItems = sgGeneral.add(new ItemListSetting.Builder()
        .name("ignore-items")
        .description("Không nhặt, và vứt đi khi túi đầy.")
        .defaultValue(new ArrayList<>())
        .build());

    public KhoaItemCollector() {
        super("khoa-item-collector", "Tự đi nhặt vật phẩm rơi quanh bạn.");
    }

    @Override protected Task createTask() { return new ItemCollector(); }

    @Override
    protected void applyConfig(Config c) {
        super.applyConfig(c);
        c.itemRadius = radius.get();
        c.collectOnlyListed = onlyListed.get();
        c.collectItems = items.get().stream().map(Tools::itemId).toList();
        c.ignoreItems = ignoreItems.get().stream().map(Tools::itemId).toList();
    }
}
