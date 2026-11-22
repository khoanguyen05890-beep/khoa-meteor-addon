package com.khoa.automationbot;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;

import java.util.Locale;

/** Hàm phụ dùng chung cho Auto Sand Bot. */
public final class SandUtil {
    private SandUtil() {}

    public static boolean isSand(Item it) { return it == Items.SAND || it == Items.RED_SAND; }

    public static int sandCount(PlayerEntity p) {
        return Tools.count(p, Items.SAND) + Tools.count(p, Items.RED_SAND);
    }

    public static String path(Item it) { return Registries.ITEM.getId(it).getPath(); }

    public static boolean isShovel(ItemStack s) {
        return !s.isEmpty() && path(s.getItem()).endsWith("_shovel");
    }

    public static boolean isShulker(ItemStack s) {
        return !s.isEmpty() && path(s.getItem()).endsWith("shulker_box");
    }

    public static boolean emptyShulker(ItemStack s) {
        if (!isShulker(s)) return false;
        ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
        return c == null || c.streamNonEmpty().findAny().isEmpty();
    }

    public static boolean filledShulker(ItemStack s) { return isShulker(s) && !emptyShulker(s); }

    /** Tên hoặc id chứa từ khoá (vd "amethyst"), không phân biệt hoa thường. */
    public static boolean hasKeyword(ItemStack s, String kw) {
        if (s.isEmpty() || kw == null || kw.isBlank()) return false;
        String k = kw.toLowerCase(Locale.ROOT).trim();
        return s.getName().getString().toLowerCase(Locale.ROOT).contains(k) || path(s.getItem()).contains(k);
    }

    public static boolean isPickaxe(ItemStack s) {
        return !s.isEmpty() && path(s.getItem()).endsWith("_pickaxe");
    }

    public static boolean hasEnchant(ItemStack s, String id) {
        ItemEnchantmentsComponent e = s.get(DataComponentTypes.ENCHANTMENTS);
        if (e == null) return false;
        for (var entry : e.getEnchantments()) {
            if (entry.getIdAsString().contains(id)) return true;
        }
        return false;
    }

    public static boolean hasMending(ItemStack s) {
        ItemEnchantmentsComponent e = s.get(DataComponentTypes.ENCHANTMENTS);
        if (e == null) return false;
        for (var entry : e.getEnchantments()) {
            if (entry.getIdAsString().contains("mending")) return true;
        }
        return false;
    }

    /** Độ bền còn lại 0..1 (1 nếu không có độ bền). */
    public static double durability(ItemStack s) {
        if (s.isEmpty() || !s.isDamageable() || s.getMaxDamage() <= 0) return 1;
        return (double) (s.getMaxDamage() - s.getDamage()) / s.getMaxDamage();
    }

    public static boolean lowForRepair(ItemStack s, int percent) {
        if (!s.isDamageable()) return false;
        int left = s.getMaxDamage() - s.getDamage();
        return left <= 5 || durability(s) * 100.0 < percent;
    }

    /** Nút xác nhận: kính xanh (không phải "+ Thêm"), hoặc ô có tên khớp từ khoá xác nhận. */
    public static int findConfirm(ScreenHandler h) {
        int n = GuiUtil.topCount(h);
        for (int i = 0; i < n; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (!st.isEmpty() && GuiUtil.isGreen(st) && !GuiUtil.isAdd(st)) return i;
        }
        for (int i = 0; i < n; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (st.isEmpty() || isSand(st.getItem())) continue;
            if (GuiUtil.nameMatches(st, Config.get().confirmKeywords)) return i;
        }
        return -1;
    }
}
