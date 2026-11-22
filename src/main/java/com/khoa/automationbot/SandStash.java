package com.khoa.automationbot;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import static com.khoa.automationbot.Lang.t;

/**
 * Chế độ GIỮ CÁT khi đầy kho:
 *  (nếu thiếu) /shop -> End Stone -> shulker -> xác nhận;  /shop -> End Stone -> ender chest -> xác nhận;
 *  đặt ender chest, đặt shulker, mở shulker, bỏ hết cát vào, đập shulker (auto tool), nhặt lại shulker,
 *  mở ender chest, bỏ shulker vào, rồi (tuỳ chọn) đập ender chest lấy lại.
 */
public final class SandStash {
    private enum S { START, BUY_SHULKER, BUY_ENDER, SPOT, PLACE_ENDER, PLACE_SHULKER, OPEN_SHULKER, FILL,
        BREAK_SHULKER, PICKUP, OPEN_ENDER, STORE, BREAK_ENDER, PICKUP_ENDER }

    public boolean done, success;
    public String status = "";

    private S s = S.START;
    private int life, timer, placeTimer, stall, lastCount = -1;
    private BlockPos enderPos, boxPos;
    private Provision buy;
    private final ItemCollector collector = new ItemCollector();

    public SandStash() { collector.pickAll = true; }

    /** Đang đặt/đập block: tuyệt đối không để module khác chen vào. */
    public boolean critical() { return s != S.START && s != S.BUY_SHULKER && s != S.BUY_ENDER; }

    private void finish(boolean ok, String vi, String en) {
        done = true;
        success = ok;
        status = t(vi, en);
        collector.stop();
        Motion.release();
    }

    private void closeGui(ClientPlayerEntity p) {
        if (GuiUtil.open(p) != null) p.closeHandledScreen();
    }

    private void go(S next) { s = next; timer = 0; placeTimer = 0; stall = 0; lastCount = -1; }

    // ------------------------------------------------------------------ tiện ích

    private int findSlot(ClientPlayerEntity p, Predicate<ItemStack> pred) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && pred.test(st)) return i;
        }
        return -1;
    }

    private boolean holdSlot(MinecraftClient mc, ClientPlayerEntity p, int slot) {
        PlayerInventory inv = p.getInventory();
        if (slot < 9) { inv.setSelectedSlot(slot); return true; }
        mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, Tools.slotId(slot), inv.getSelectedSlot(), SlotActionType.SWAP, p);
        return false;
    }

    private List<BlockPos> findSpots(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        BlockPos pb = p.getBlockPos();
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos cell = pb.add(dx, 0, dz);
                if (!w.getBlockState(cell).isAir() || !w.getBlockState(cell.up()).isAir()) continue;
                if (!Path.floorOk(w, cell.down())) continue;
                Vec3d top = new Vec3d(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
                if (p.getEyePos().squaredDistanceTo(top) > 3.6 * 3.6) continue;
                out.add(cell);
            }
        }
        Vec3d pp = p.getEntityPos();
        out.sort(Comparator.comparingDouble(b -> Tools.distSq(b, pp)));
        return out;
    }

    /** 0 = đang làm, 1 = block đã có mặt tại cell, -1 = thất bại. */
    private int place(MinecraftClient mc, BlockPos cell, Predicate<ItemStack> pred) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (!w.getBlockState(cell).isAir()) return 1;
        if (++placeTimer > 100) return -1;
        int slot = findSlot(p, pred);
        if (slot < 0) return -1;
        if (!holdSlot(mc, p, slot)) return 0;
        BlockPos sup = cell.down();
        Vec3d aim = new Vec3d(sup.getX() + 0.5, sup.getY() + 1.0, sup.getZ() + 0.5);
        if (!Motion.lookAt(p, aim, Config.get().turnSpeed())) return 0;
        if (mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK
                && bhr.getBlockPos().equals(sup) && bhr.getSide() == Direction.UP) {
            if (placeTimer % 4 == 0) {
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, bhr);
                p.swingHand(Hand.MAIN_HAND);
            }
        }
        return 0;
    }

    private boolean open(MinecraftClient mc, BlockPos pos) {
        ClientPlayerEntity p = mc.player;
        if (GuiUtil.open(p) != null) return true;
        Motion.sneak(false);
        Vec3d c = Vec3d.ofCenter(pos);
        if (!Motion.lookAt(p, c, Config.get().turnSpeed())) return false;
        if (timer % 20 == 0) {
            Vec3d e = p.getEyePos();
            Direction side = Direction.getFacing(e.x - c.x, e.y - c.y, e.z - c.z);
            Vec3d hv = c.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hv, side, pos, false));
            p.swingHand(Hand.MAIN_HAND);
        }
        return false;
    }

    /** true = block đã biến mất. Tự chọn công cụ (auto tool). */
    private boolean breakBlock(MinecraftClient mc, BlockPos pos) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        BlockState st = w.getBlockState(pos);
        if (st.isAir()) return true;
        if (GuiUtil.open(p) != null) { p.closeHandledScreen(); return false; }
        Tools.selectTool(mc, st, true);
        Vec3d c = Vec3d.ofCenter(pos);
        if (!Motion.lookAt(p, c, Config.get().turnSpeed())) return false;
        Vec3d e = p.getEyePos();
        Direction side = Direction.getFacing(e.x - c.x, e.y - c.y, e.z - c.z);
        mc.interactionManager.updateBlockBreakingProgress(pos, side);
        p.swingHand(Hand.MAIN_HAND);
        return false;
    }

    // ------------------------------------------------------------------ chạy

    /** true = còn đang chạy. */
    public boolean tick(MinecraftClient mc) {
        if (done) return false;
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        SandConfig sc = SandConfig.get();
        if (++life > 4000) { closeGui(p); finish(false, "Cất cát quá thời gian", "Stashing timed out"); return false; }
        timer++;

        switch (s) {
            case START -> {
                closeGui(p);
                collector.start();
                if (findSlot(p, SandUtil::emptyShulker) < 0) go(S.BUY_SHULKER);
                else if (Tools.count(p, Items.ENDER_CHEST) <= 0) go(S.BUY_ENDER);
                else go(S.SPOT);
            }
            case BUY_SHULKER -> {
                if (buy == null) buy = Provision.shop(Items.SHULKER_BOX, i -> SandUtil.path(i).endsWith("shulker_box"), 1,
                        sc.endCategory, List.of("shulker"));
                status = buy.status;
                if (!buy.tick(mc)) {
                    boolean ok = buy.success || findSlot(p, SandUtil::isShulker) >= 0;
                    buy = null;
                    if (!ok) finish(false, "Không mua được shulker ở /shop", "Could not buy a shulker box from /shop");
                    else go(S.START);
                }
            }
            case BUY_ENDER -> {
                if (buy == null) buy = Provision.shop(Items.ENDER_CHEST, null, 1, sc.endCategory,
                        List.of("ender chest", "enderchest", "ender_chest"));
                status = buy.status;
                if (!buy.tick(mc)) {
                    boolean ok = buy.success || Tools.count(p, Items.ENDER_CHEST) > 0;
                    buy = null;
                    if (!ok) finish(false, "Không mua được ender chest ở /shop", "Could not buy an ender chest from /shop");
                    else go(S.START);
                }
            }
            case SPOT -> {
                Motion.release();
                List<BlockPos> spots = findSpots(mc);
                if (spots.size() < 2) {
                    status = t("Tìm chỗ trống để đặt rương...", "Looking for a free spot to place the chests...");
                    if (timer > 40) finish(false, "Không có chỗ đặt ender chest + shulker", "No room to place the ender chest and shulker");
                    return !done;
                }
                enderPos = spots.get(0);
                boxPos = spots.get(1);
                go(S.PLACE_ENDER);
            }
            case PLACE_ENDER -> {
                status = t("Đặt ender chest", "Placing the ender chest");
                int r = place(mc, enderPos, st -> st.getItem() == Items.ENDER_CHEST);
                if (r < 0 || (r == 1 && w.getBlockState(enderPos).getBlock() != Blocks.ENDER_CHEST)) {
                    finish(false, "Đặt ender chest thất bại", "Failed to place the ender chest");
                } else if (r == 1) go(S.PLACE_SHULKER);
            }
            case PLACE_SHULKER -> {
                status = t("Đặt shulker", "Placing the shulker box");
                int r = place(mc, boxPos, SandUtil::emptyShulker);
                if (r < 0 || (r == 1 && !(w.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock))) {
                    finish(false, "Đặt shulker thất bại", "Failed to place the shulker box");
                } else if (r == 1) go(S.OPEN_SHULKER);
            }
            case OPEN_SHULKER -> {
                status = t("Mở shulker", "Opening the shulker box");
                if (open(mc, boxPos)) go(S.FILL);
                else if (timer > 120) finish(false, "Không mở được shulker", "Could not open the shulker box");
            }
            case FILL -> {
                ScreenHandler h = GuiUtil.open(p);
                if (h == null) { go(S.OPEN_SHULKER); return true; }
                if (timer < 5 || timer % 3 != 0) return true;
                int top = GuiUtil.topCount(h);
                int from = -1, cnt = 0;
                for (int i = top; i < h.slots.size(); i++) {
                    ItemStack st = h.slots.get(i).getStack();
                    if (!st.isEmpty() && SandUtil.isSand(st.getItem())) { if (from < 0) from = i; cnt += st.getCount(); }
                }
                status = t("Bỏ cát vào shulker... còn ", "Filling the shulker with sand... left ") + cnt;
                if (from < 0) { closeGui(p); go(S.BREAK_SHULKER); return true; }
                if (cnt == lastCount) { if (++stall >= 4) { closeGui(p); go(S.BREAK_SHULKER); return true; } }   // shulker đầy
                else stall = 0;
                lastCount = cnt;
                GuiUtil.click(mc, h, from, 0, SlotActionType.QUICK_MOVE);
            }
            case BREAK_SHULKER -> {
                status = t("Đập shulker (auto tool)", "Breaking the shulker box (auto tool)");
                if (breakBlock(mc, boxPos)) go(S.PICKUP);
                else if (timer > 600) finish(false, "Không đập được shulker", "Could not break the shulker box");
            }
            case PICKUP -> {
                status = t("Nhặt shulker chứa cát", "Picking up the full shulker box");
                collector.step(mc);
                if (findSlot(p, SandUtil::filledShulker) >= 0) { Motion.release(); go(S.OPEN_ENDER); }
                else if (timer > 160) finish(false, "Không nhặt lại được shulker", "Could not pick the shulker box back up");
            }
            case OPEN_ENDER -> {
                status = t("Mở ender chest", "Opening the ender chest");
                if (open(mc, enderPos)) go(S.STORE);
                else if (timer > 120) finish(false, "Không mở được ender chest", "Could not open the ender chest");
            }
            case STORE -> {
                ScreenHandler h = GuiUtil.open(p);
                if (h == null) { go(S.OPEN_ENDER); return true; }
                if (timer < 5 || timer % 3 != 0) return true;
                int top = GuiUtil.topCount(h);
                int from = -1, cnt = 0;
                for (int i = top; i < h.slots.size(); i++) {
                    ItemStack st = h.slots.get(i).getStack();
                    if (SandUtil.filledShulker(st)) { if (from < 0) from = i; cnt++; }
                }
                status = t("Cất shulker vào ender chest", "Storing the shulker box in the ender chest");
                if (from < 0) { closeGui(p); go(SandConfig.get().pickupEnder ? S.BREAK_ENDER : S.PICKUP_ENDER); return true; }
                if (cnt == lastCount) { if (++stall >= 4) { closeGui(p); finish(false, "Ender chest đã đầy", "The ender chest is full"); return false; } }
                else stall = 0;
                lastCount = cnt;
                GuiUtil.click(mc, h, from, 0, SlotActionType.QUICK_MOVE);
            }
            case BREAK_ENDER -> {
                status = t("Đập ender chest lấy lại", "Breaking the ender chest to take it back");
                if (breakBlock(mc, enderPos)) go(S.PICKUP_ENDER);
                else if (timer > 800) finish(true, "Đã cất cát (không đập được ender chest)", "Sand stored (could not break the ender chest)");
            }
            case PICKUP_ENDER -> {
                collector.step(mc);
                if (timer > 50 || !SandConfig.get().pickupEnder) finish(true, "Đã cất cát vào ender chest", "Sand stored in the ender chest");
            }
        }
        return !done;
    }
}
