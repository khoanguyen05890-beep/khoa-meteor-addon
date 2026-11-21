package com.khoa.automationbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tiện ích thao tác với GUI container của server (/ah, /shop, rương...). */
public final class GuiUtil {
    private GuiUtil() {}

    /** Container đang mở (khác túi đồ của người chơi), hoặc null. */
    public static ScreenHandler open(ClientPlayerEntity p) {
        return p.currentScreenHandler != p.playerScreenHandler ? p.currentScreenHandler : null;
    }

    /** Số slot của container (không tính 36 slot túi đồ người chơi nằm ở cuối). */
    public static int topCount(ScreenHandler h) { return Math.max(0, h.slots.size() - 36); }

    public static String name(ItemStack s) { return s.getName().getString(); }

    public static String lore(ItemStack s) {
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Text line : lore.lines()) sb.append(line.getString()).append('\n');
        return sb.toString();
    }

    public static boolean nameMatches(ItemStack s, List<String> keywords) {
        if (s.isEmpty()) return false;
        String n = name(s).toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if (k != null && !k.isBlank() && n.contains(k.toLowerCase(Locale.ROOT).trim())) return true;
        }
        return false;
    }

    public static void click(MinecraftClient mc, ScreenHandler h, int slot, int button, SlotActionType type) {
        mc.interactionManager.clickSlot(h.syncId, slot, button, type, mc.player);
    }

    public static void sendCommand(MinecraftClient mc, String cmd) {
        String c = cmd.trim();
        if (c.startsWith("/")) c = c.substring(1);
        if (!c.isEmpty()) mc.player.networkHandler.sendChatCommand(c);
    }

    // ---- giá ----

    public static Pattern compile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (Throwable t) {
            return Pattern.compile(Config.DEFAULT_PRICE_REGEX);
        }
    }

    /** Đọc giá từ lore của vật phẩm. Trả về -1 nếu không đọc được. */
    public static double price(ItemStack s, Pattern pat) {
        String text = lore(s);
        if (text.isEmpty()) return -1;
        Matcher m = pat.matcher(text);
        while (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g == null) continue;
                double v = num(g);
                if (v >= 0) return v;
            }
        }
        return -1;
    }

    static double num(String raw) {
        String s = raw.replaceAll("\\s+", "");
        if (s.isEmpty()) return -1;
        double mult = 1;
        char l = Character.toLowerCase(s.charAt(s.length() - 1));
        if (l == 'k') mult = 1e3; else if (l == 'm') mult = 1e6; else if (l == 'b') mult = 1e9;
        if (mult != 1) s = s.substring(0, s.length() - 1);
        s = s.replaceAll("[.,]+$", "");
        if (s.isEmpty()) return -1;
        if (s.matches("\\d{1,3}(\\.\\d{3})+")) s = s.replace(".", "");   // 1.500.000 kiểu Việt Nam
        s = s.replace(",", "");
        try {
            return Double.parseDouble(s) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
