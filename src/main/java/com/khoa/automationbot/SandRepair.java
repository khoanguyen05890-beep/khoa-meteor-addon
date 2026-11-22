package com.khoa.automationbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import static com.khoa.automationbot.Lang.t;

/**
 * Sửa xẻng bằng Mending:
 *  hết chai exp -> /shop -> danh mục totem -> exp -> "+64" -> xác nhận;
 *  đưa xẻng sang tay phụ, cầm chai exp, nhìn xuống đất và ném liên tục tới khi xẻng gần đầy độ bền; rồi trả xẻng về chỗ cũ.
 */
public final class SandRepair {
    private enum S { BUY, EQUIP, THROW, RESTORE }

    public boolean done, success;
    public String status = "";

    private final int slot;                  // chỉ số trong túi (0..35) của xẻng cần sửa
    private S s = S.BUY;
    private int life, timer;
    private Provision buy;
    private boolean swapped;

    public SandRepair(int slot) { this.slot = slot; }

    private void finish(boolean ok, String vi, String en) {
        done = true;
        success = ok;
        status = t(vi, en);
        Motion.release();
    }

    private void swapOffhand(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, Tools.slotId(slot), 40, SlotActionType.SWAP, p);
    }

    public boolean tick(MinecraftClient mc) {
        if (done) return false;
        ClientPlayerEntity p = mc.player;
        SandConfig sc = SandConfig.get();
        if (++life > 3000 && s != S.RESTORE) { Motion.use(false); s = S.RESTORE; timer = 0; }

        switch (s) {
            case BUY -> {
                if (Tools.count(p, Items.EXPERIENCE_BOTTLE) > 0) { s = S.EQUIP; timer = 0; return true; }
                if (buy == null) {
                    buy = Provision.shop(Items.EXPERIENCE_BOTTLE, null, sc.expAmount, sc.expCategory, sc.expKeywords);
                }
                status = buy.status;
                if (!buy.tick(mc)) {
                    boolean ok = buy.success || Tools.count(p, Items.EXPERIENCE_BOTTLE) > 0;
                    buy = null;
                    if (!ok) finish(false, "Không mua được exp ở /shop", "Could not buy exp from /shop");
                    else { s = S.EQUIP; timer = 0; }
                }
            }
            case EQUIP -> {
                if (GuiUtil.open(p) != null) { p.closeHandledScreen(); return true; }
                ItemStack off = p.getOffHandStack();
                if (swapped && SandUtil.isShovel(off)) { s = S.THROW; timer = 0; return true; }
                ItemStack sh = p.getInventory().getStack(slot);
                if (!SandUtil.isShovel(sh)) { finish(false, "Xẻng đã bị di chuyển", "The shovel was moved"); return false; }
                status = t("Đưa xẻng sang tay phụ...", "Moving the shovel to the off-hand...");
                if (timer % 8 == 0) { swapOffhand(mc); swapped = true; }
                if (++timer > 80) finish(false, "Không đưa được xẻng sang tay phụ", "Could not move the shovel to the off-hand");
            }
            case THROW -> {
                ItemStack off = p.getOffHandStack();
                int bottles = Tools.count(p, Items.EXPERIENCE_BOTTLE);
                if (!SandUtil.isShovel(off)) { Motion.use(false); s = S.RESTORE; timer = 0; return true; }
                double d = SandUtil.durability(off);
                status = t("Đang sửa xẻng bằng exp: ", "Repairing shovel with exp: ") + (int) (d * 100) + "%";
                if (d >= 0.97 || bottles == 0) { Motion.use(false); s = S.RESTORE; timer = 0; return true; }
                if (GuiUtil.open(p) != null) { p.closeHandledScreen(); return true; }
                if (!Tools.hold(mc, p, Items.EXPERIENCE_BOTTLE)) return true;
                if (!Motion.turn(p, p.getYaw(), 90f, Config.get().turnSpeed())) return true;    // nhìn xuống đất
                Motion.use(true);
            }
            case RESTORE -> {
                Motion.use(false);
                ItemStack off = p.getOffHandStack();
                if (SandUtil.isShovel(off) && swapped) {
                    if (timer % 8 == 0) swapOffhand(mc);
                    if (++timer > 80) finish(false, "Không trả được xẻng về túi", "Could not move the shovel back");
                    return !done;
                }
                boolean ok = SandUtil.durability(p.getInventory().getStack(slot)) >= 0.5;
                finish(ok, ok ? "Đã sửa xẻng" : "Sửa xẻng chưa xong (hết exp?)", ok ? "Shovel repaired" : "Repair incomplete (out of exp?)");
                return false;
            }
        }
        return !done;
    }
}
