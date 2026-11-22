package com.khoa.automationbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.regex.Pattern;

import static com.khoa.automationbot.Lang.t;

/**
 * Đầy kho -> bán cát bằng /order:
 *  /order sand -> chọn đơn có GIÁ CAO NHẤT -> bỏ hết cát vào (shift-click) -> ESC (đóng GUI) -> bấm kính xanh xác nhận.
 * Gọi tick() mỗi tick tới khi trả về false; kết quả ở success/status.
 */
public final class SandOrder {
    private enum S { CMD, LIST, DEPOSIT, CLOSE, CONFIRM }

    public boolean done, success;
    public String status = "";

    private S s = S.CMD;
    private int life, timer, listSync = -1, stall, lastInv = -1, confirmClicks, startSand;
    private boolean confirmClicked;

    private void finish(boolean ok, String vi, String en) {
        done = true;
        success = ok;
        status = t(vi, en);
        Motion.release();
    }

    private void closeGui(ClientPlayerEntity p) {
        if (GuiUtil.open(p) != null) p.closeHandledScreen();
    }

    private static int invSand(ScreenHandler h, int top) {
        int n = 0;
        for (int i = top; i < h.slots.size(); i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (!st.isEmpty() && SandUtil.isSand(st.getItem())) n += st.getCount();
        }
        return n;
    }

    /** true = còn đang chạy. */
    public boolean tick(MinecraftClient mc) {
        if (done) return false;
        ClientPlayerEntity p = mc.player;
        if (++life > 1500) { closeGui(p); finish(false, "Bán /order quá thời gian", "/order timed out"); return false; }

        switch (s) {
            case CMD -> {
                if (GuiUtil.open(p) != null) { closeGui(p); return true; }
                if (timer++ < 3) return true;
                startSand = SandUtil.sandCount(p);
                status = t("Mở /order để bán cát...", "Opening /order to sell sand...");
                GuiUtil.sendCommand(mc, SandConfig.get().orderCommand);
                timer = 0;
                s = S.LIST;
            }
            case LIST -> {
                timer++;
                ScreenHandler h = GuiUtil.open(p);
                if (h == null) {
                    if (timer > 60) finish(false, "/order không mở được", "/order did not open");
                    return !done;
                }
                if (timer < 10) return true;                                   // chờ server gửi nội dung
                Pattern pat = GuiUtil.compile(Config.get().priceRegex);
                int n = GuiUtil.topCount(h);
                int best = -1, first = -1;
                double bestPrice = -1;
                for (int i = 0; i < n; i++) {
                    ItemStack st = h.slots.get(i).getStack();
                    if (st.isEmpty() || !SandUtil.isSand(st.getItem())) continue;
                    if (first < 0) first = i;
                    double price = GuiUtil.price(st, pat);
                    if (price > bestPrice) { bestPrice = price; best = i; }
                }
                if (best < 0) best = first;                                   // không đọc được giá -> lấy đơn đầu
                if (best < 0) {
                    if (timer < 30) return true;
                    closeGui(p);
                    finish(false, "Không có đơn /order cát nào", "No sand order found in /order");
                    return false;
                }
                status = t("Chọn đơn /order giá cao nhất", "Picking the highest-paying /order");
                listSync = h.syncId;
                GuiUtil.click(mc, h, best, 0, SlotActionType.PICKUP);
                timer = 0;
                s = S.DEPOSIT;
            }
            case DEPOSIT -> {
                timer++;
                ScreenHandler h = GuiUtil.open(p);
                if (h == null || h.syncId == listSync) {
                    if (timer > 60) finish(false, "Không mở được trang bỏ cát", "The deposit page did not open");
                    return !done;
                }
                if (timer < 6 || timer % 3 != 0) return true;
                int top = GuiUtil.topCount(h);
                int from = -1;
                for (int i = top; i < h.slots.size(); i++) {
                    ItemStack st = h.slots.get(i).getStack();
                    if (!st.isEmpty() && SandUtil.isSand(st.getItem())) { from = i; break; }
                }
                int inv = invSand(h, top);
                if (from < 0) { s = S.CLOSE; timer = 0; return true; }
                if (inv == lastInv) { if (++stall >= 4) { s = S.CLOSE; timer = 0; return true; } }   // đơn đã đầy
                else stall = 0;
                lastInv = inv;
                status = t("Bỏ cát vào đơn... còn ", "Depositing sand... left ") + inv;
                GuiUtil.click(mc, h, from, 0, SlotActionType.QUICK_MOVE);
            }
            case CLOSE -> {
                status = t("Bấm ESC", "Pressing ESC");
                closeGui(p);                                                  // ESC
                timer = 0;
                s = S.CONFIRM;
            }
            case CONFIRM -> {
                timer++;
                ScreenHandler h = GuiUtil.open(p);
                if (h == null) {
                    boolean sold = SandUtil.sandCount(p) < startSand;
                    if (confirmClicked || timer > 40) {
                        finish(sold, sold ? "Đã bán cát" : "Không bán được cát", sold ? "Sand sold" : "Nothing was sold");
                        return false;
                    }
                    return true;
                }
                if (timer < 6 || timer % 8 != 0) return true;
                int slot = SandUtil.findConfirm(h);
                if (slot < 0) { if (timer > 60) { closeGui(p); finish(false, "Không thấy nút xác nhận", "Confirm button not found"); } return !done; }
                if (confirmClicks >= 4) { closeGui(p); finish(false, "Bấm xác nhận nhiều lần không được", "Confirm did not go through"); return false; }
                status = t("Bấm xác nhận bán", "Clicking confirm");
                GuiUtil.click(mc, h, slot, 0, SlotActionType.PICKUP);
                confirmClicks++;
                confirmClicked = true;
            }
        }
        return !done;
    }
}
