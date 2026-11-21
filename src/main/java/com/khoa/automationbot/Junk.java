package com.khoa.automationbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Vứt đồ KHÔNG cần thiết để có chỗ trống trước khi mua (/shop, /ah) hoặc lấy đồ từ rương.
 *
 * Được giữ lại (không bao giờ vứt):
 *  - vật liệu schematic đang cần, block dựng cột, đồ ăn, ender pearl, món đang đi mua
 *  - danh sách keep-items, block/vật phẩm của miner & collector
 *  - công cụ/vũ khí/giáp (có độ bền), đồ có enchant, đồ đã đặt tên, shulker box, đồ quý (diamond, netherite...)
 */
public final class Junk {
    private Junk() {}

    private static final String[] VALUABLE = {"netherite", "diamond", "emerald", "totem", "elytra", "shulker_box",
            "ender_chest", "beacon", "nether_star", "trident", "mace", "heart_of_the_sea", "dragon", "conduit"};

    public static int emptySlots(PlayerEntity p) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getStack(i).isEmpty()) n++;
        return n;
    }

    public static boolean keep(ItemStack st, Item extra) {
        if (st.isEmpty()) return true;
        Item it = st.getItem();
        if (it == extra || it == Items.ENDER_PEARL || it == Items.COOKED_BEEF) return true;
        Config c = Config.get();
        String id = Tools.itemId(it);
        if (c.protectedItems.contains(id) || c.keepItems.contains(id) || c.eatItems.contains(id)) return true;
        if (c.scaffoldItems.contains(id) || c.minerBlocks.contains(id) || c.collectItems.contains(id)) return true;
        if (st.get(DataComponentTypes.FOOD) != null && !Hunger.isBad(it)) return true;
        if (st.isDamageable()) return true;
        if (st.get(DataComponentTypes.CUSTOM_NAME) != null) return true;
        var ench = st.get(DataComponentTypes.ENCHANTMENTS);
        if (ench != null && !ench.isEmpty()) return true;
        for (String v : VALUABLE) if (id.contains(v)) return true;
        return false;
    }

    /** Vứt nguyên 1 stack không cần thiết. true nếu đã gửi lệnh vứt, false nếu không còn gì để vứt. */
    public static boolean dropOne(MinecraftClient mc, Item extra) {
        ClientPlayerEntity p = mc.player;
        PlayerInventory inv = p.getInventory();
        for (int i = 35; i >= 0; i--) {                       // túi chính trước, hotbar sau
            ItemStack st = inv.getStack(i);
            if (st.isEmpty() || keep(st, extra)) continue;
            mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, Tools.slotId(i), 1, SlotActionType.THROW, p);
            return true;
        }
        return false;
    }
}
