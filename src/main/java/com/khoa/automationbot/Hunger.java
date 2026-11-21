package com.khoa.automationbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.util.Set;

import static com.khoa.automationbot.Lang.t;

/**
 * Tự ăn khi đói. Không có đồ ăn thì /shop mua thịt bò nướng rồi ăn.
 * Dùng chung cho mọi module Khoa (xem KhoaModule).
 */
public final class Hunger {
    private static final Set<Item> BAD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
            Items.PUFFERFISH, Items.CHICKEN, Items.CHORUS_FRUIT, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE);

    public static boolean isBad(Item i) { return BAD.contains(i); }

    public String status = "";
    private Provision shop;
    private boolean eating;
    private int eatTicks, failCooldown;

    public boolean active() { return shop != null || eating; }

    public void reset() {
        shop = null;
        eating = false;
        eatTicks = 0;
        failCooldown = 0;
        status = "";
        Motion.use(false);
    }

    private Item foodItem() {
        for (String id : Config.get().eatItems) {
            Identifier i = Identifier.tryParse(id);
            if (i != null && Registries.ITEM.containsId(i)) return Registries.ITEM.get(i);
        }
        return Items.COOKED_BEEF;
    }

    private int findFood(PlayerEntity p) {
        for (String id : Config.get().eatItems) {
            Identifier i = Identifier.tryParse(id);
            if (i == null || !Registries.ITEM.containsId(i)) continue;
            int s = Tools.findItem(p, Registries.ITEM.get(i));
            if (s >= 0) return s;
        }
        int s = Tools.findItem(p, Items.COOKED_BEEF);
        if (s >= 0) return s;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (!st.isEmpty() && st.get(DataComponentTypes.FOOD) != null && !BAD.contains(st.getItem())) return i;
        }
        return -1;
    }

    private void stopEating() {
        if (eating) Motion.use(false);
        eating = false;
        eatTicks = 0;
    }

    /** Trả về true nếu đang điều khiển người chơi (task chính nên tạm dừng tick này). */
    public boolean tick(MinecraftClient mc) {
        Config c = Config.get();
        ClientPlayerEntity p = mc.player;
        if (!c.autoEat || p == null || p.isCreative()) { stopEating(); shop = null; return false; }
        if (failCooldown > 0) failCooldown--;

        if (shop != null) {
            status = shop.status;
            if (!shop.tick(mc)) {
                if (!shop.success) failCooldown = 1200;
                shop = null;
            }
            return true;
        }

        int food = p.getHungerManager().getFoodLevel();
        if (eating) {
            Motion.release();
            eatTicks++;
            if (food >= 18 || eatTicks > 200 || findFood(p) < 0) { stopEating(); return false; }
            int slot = findFood(p);
            PlayerInventory inv = p.getInventory();
            if (slot >= 9) {
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, Tools.slotId(slot),
                        inv.getSelectedSlot(), SlotActionType.SWAP, p);
                return true;
            }
            inv.setSelectedSlot(slot);
            status = t("Đang ăn...", "Eating...");
            Motion.use(true);
            return true;
        }

        if (food > c.hungerThreshold) return false;
        if (GuiUtil.open(p) != null) return false;
        if (findFood(p) >= 0) {
            eating = true;
            eatTicks = 0;
            Motion.release();
            return true;
        }
        if (failCooldown <= 0 && c.shopCommand != null && !c.shopCommand.isBlank()) {
            shop = Provision.food(foodItem(), c.foodAmount);
            Motion.release();
            return true;
        }
        return false;
    }
}
