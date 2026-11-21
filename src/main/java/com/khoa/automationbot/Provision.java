package com.khoa.automationbot;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.regex.Pattern;

import static com.khoa.automationbot.Lang.t;

/**
 * Một "việc vặt" có đầu có cuối để kiếm vật phẩm:
 *  - item(): lấy trong rương/thùng gần đó -> nếu không có thì /ah <tên> mua cái RẺ NHẤT.
 *  - food(): /shop -> bấm thịt bò nướng (hoặc mục khớp từ khoá) -> mua tới khi đủ số lượng.
 * Gọi tick() mỗi tick cho tới khi trả về false; kết quả ở success/status.
 */
public final class Provision {
    private enum S { START, PICK_CHEST, WALK, OPEN, TAKE, AH_CMD, AH_LIST, AH_AFTER, SHOP_CMD, SHOP_RUN }

    public boolean done, success;
    public String status = "";

    private final Item item;
    private final int want;
    private final boolean food;

    private S s = S.START;
    private int life, timer, startCount, before, buys, clicks, confirmClicks, cmdSent;
    private final Walker walker = new Walker();
    private final List<BlockPos> chests = new ArrayList<>();
    private final Set<Long> visited = new HashSet<>();
    private final Set<String> clickedCategories = new HashSet<>();
    private BlockPos chest;

    private Provision(Item item, int want, boolean food) {
        this.item = item;
        this.want = Math.max(1, want);
        this.food = food;
    }

    public static Provision item(Item it, int amount) { return new Provision(it, amount, false); }
    public static Provision food(Item it, int amount) { return new Provision(it, amount, true); }

    private int gained(ClientPlayerEntity p) { return Tools.count(p, item) - startCount; }

    private void finish(boolean ok) {
        done = true;
        success = ok;
        walker.clear();
        Motion.release();
    }

    private void fail(String vi, String en) {
        status = t(vi, en);
        finish(false);
    }

    private void closeGui(ClientPlayerEntity p) {
        if (GuiUtil.open(p) != null) p.closeHandledScreen();
    }

    /** Trả về true nếu còn đang chạy. */
    public boolean tick(MinecraftClient mc) {
        if (done) return false;
        ClientPlayerEntity p = mc.player;
        Config cfg = Config.get();
        if (++life > 3000) { closeGui(p); fail("Quá thời gian", "Timed out"); return false; }

        switch (s) {
            case START -> {
                startCount = Tools.count(p, item);
                if (food) { s = S.SHOP_CMD; break; }
                if (cfg.useStorage) { findChests(mc); s = S.PICK_CHEST; }
                else afterStorage();
            }
            case PICK_CHEST -> pickChest(mc);
            case WALK -> walk(mc);
            case OPEN -> openChest(mc);
            case TAKE -> take(mc);
            case AH_CMD -> ahCommand(mc);
            case AH_LIST -> ahList(mc);
            case AH_AFTER -> ahAfter(mc);
            case SHOP_CMD -> shopCommand(mc);
            case SHOP_RUN -> shopRun(mc);
        }
        return !done;
    }

    // ------------------------------------------------------------------ rương

    private void findChests(MinecraftClient mc) {
        chests.clear();
        ClientPlayerEntity p = mc.player;
        int r = Math.max(4, Math.min(64, Config.get().storageRadius));
        int ry = Math.min(r, 24);
        BlockPos o = p.getBlockPos();
        BlockPos.Mutable m = new BlockPos.Mutable();
        List<BlockPos> found = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!Tools.loaded(mc.world, o.getX() + dx, o.getZ() + dz)) continue;
                for (int dy = -ry; dy <= ry; dy++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    Block b = mc.world.getBlockState(m).getBlock();
                    if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL) found.add(m.toImmutable());
                }
            }
        }
        Vec3d pp = p.getEntityPos();
        found.sort(Comparator.comparingDouble(b -> Tools.distSq(b, pp)));
        for (int i = 0; i < found.size() && i < 40; i++) chests.add(found.get(i));
    }

    private void afterStorage() {
        if (Config.get().useAh) s = S.AH_CMD;
        else fail("Không tìm thấy trong rương", "Not found in storage");
    }

    private boolean inReach(ClientPlayerEntity p, BlockPos b) {
        return p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(b)) <= 3.8 * 3.8;
    }

    private void pickChest(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (gained(p) >= want) { finish(true); return; }
        if (chests.isEmpty()) { afterStorage(); return; }
        BlockPos c = chests.remove(0);
        if (!visited.add(c.asLong())) return;
        Block b = mc.world.getBlockState(c).getBlock();
        for (Direction d : Direction.Type.HORIZONTAL) {          // rương đôi: nửa còn lại coi như đã xem
            BlockPos n = c.offset(d);
            if (mc.world.getBlockState(n).getBlock() == b) visited.add(n.asLong());
        }
        chest = c;
        status = t("Đi tới rương lấy ", "Walking to a chest for ") + item.getName().getString();
        if (inReach(p, c)) { s = S.OPEN; timer = 0; return; }
        final BlockPos g = c;
        Path path = Path.find(mc.world, p.getBlockPos(), n -> {
            Vec3d eye = new Vec3d(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5);
            return eye.squaredDistanceTo(Vec3d.ofCenter(g)) <= 3.4 * 3.4 && !n.equals(g) && !n.up().equals(g);
        }, c, 4000);
        if (path == null) return;
        walker.set(path);
        s = S.WALK;
        timer = 0;
    }

    private void walk(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (inReach(p, chest)) { walker.clear(); Motion.forward(false); Motion.jump(false); s = S.OPEN; timer = 0; return; }
        Walker.Result r = walker.tick(mc);
        if (r == Walker.Result.FAILED) { walker.clear(); s = S.PICK_CHEST; return; }
        if (r == Walker.Result.DONE) {
            walker.clear();
            if (inReach(p, chest)) { s = S.OPEN; timer = 0; } else s = S.PICK_CHEST;
            return;
        }
        if (++timer > 500) { walker.clear(); Motion.release(); s = S.PICK_CHEST; }
    }

    private void openChest(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (GuiUtil.open(p) != null) { s = S.TAKE; timer = 0; return; }
        status = t("Đang mở rương...", "Opening chest...");
        Motion.sneak(false);
        Vec3d c = Vec3d.ofCenter(chest);
        if (!Motion.lookAt(p, c, Config.get().turnSpeed())) return;
        if (timer % 20 == 0) {
            Vec3d e = p.getEyePos();
            Direction side = Direction.getFacing(e.x - c.x, e.y - c.y, e.z - c.z);
            Vec3d hv = c.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hv, side, chest, false));
            p.swingHand(Hand.MAIN_HAND);
        }
        if (++timer > 60) s = S.PICK_CHEST;
    }

    private void take(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ScreenHandler h = GuiUtil.open(p);
        if (h == null) { s = S.PICK_CHEST; return; }
        status = t("Đang lấy ", "Taking ") + item.getName().getString() + t(" trong rương", " from chest");
        timer++;
        if (timer < 4 || timer % 2 != 0) return;               // chờ server gửi nội dung rương
        if (gained(p) < want) {
            int n = GuiUtil.topCount(h);
            for (int i = 0; i < n; i++) {
                ItemStack st = h.slots.get(i).getStack();
                if (st.isEmpty() || st.getItem() != item) continue;
                if (!Tools.fits(p, st)) { closeGui(p); fail("Túi đồ đầy", "Inventory full"); return; }
                GuiUtil.click(mc, h, i, 0, SlotActionType.QUICK_MOVE);
                return;
            }
        }
        closeGui(p);
        if (gained(p) >= want) finish(true);
        else s = S.PICK_CHEST;
    }

    // ------------------------------------------------------------------ /ah

    private String ahName() {
        String path = Registries.ITEM.getId(item).getPath();
        return switch (Config.get().ahNameMode) {
            case "DISPLAY_NAME" -> item.getName().getString();
            case "ID_SPACE" -> path.replace('_', ' ');
            default -> path;
        };
    }

    private void ahCommand(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        Config cfg = Config.get();
        if (buys >= cfg.ahMaxBuys) { finish(gained(p) > 0); return; }
        if (GuiUtil.open(p) != null) { closeGui(p); return; }
        status = t("Đang tìm trên /ah: ", "Searching /ah: ") + ahName();
        GuiUtil.sendCommand(mc, cfg.ahCommand.replace("{item}", ahName()));
        timer = 0;
        s = S.AH_LIST;
    }

    private void ahList(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        Config cfg = Config.get();
        timer++;
        ScreenHandler h = GuiUtil.open(p);
        if (h == null) {
            if (timer > 60) fail("/ah không mở được", "/ah did not open");
            return;
        }
        if (timer < 8) return;
        Pattern pat = GuiUtil.compile(cfg.priceRegex);
        int n = GuiUtil.topCount(h);
        int best = -1, firstListing = -1;
        double bestScore = Double.MAX_VALUE;
        boolean parsedAny = false, tooExpensive = false;
        for (int i = 0; i < n; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (st.isEmpty() || st.getItem() != item) continue;
            if (firstListing < 0) firstListing = i;
            double total = GuiUtil.price(st, pat);
            if (total < 0) continue;
            parsedAny = true;
            if (cfg.ahMaxPrice > 0 && total > cfg.ahMaxPrice) { tooExpensive = true; continue; }
            double score = cfg.ahPerUnit ? total / Math.max(1, st.getCount()) : total;
            if (score < bestScore) { bestScore = score; best = i; }
        }
        if (best < 0 && firstListing >= 0 && !parsedAny) best = firstListing;   // không đọc được giá -> lấy cái đầu
        if (best < 0) {
            if (timer < 24) return;                                             // có thể còn đang tải
            closeGui(p);
            if (tooExpensive) fail("Giá /ah vượt mức tối đa", "/ah price above the limit");
            else fail("Không có ai bán trên /ah", "No /ah listing found");
            return;
        }
        before = Tools.count(p, item);
        confirmClicks = 0;
        status = t("Mua trên /ah (giá nhỏ nhất)", "Buying cheapest on /ah");
        GuiUtil.click(mc, h, best, 0, SlotActionType.PICKUP);
        buys++;
        timer = 0;
        s = S.AH_AFTER;
    }

    private boolean isConfirm(ItemStack st) {
        if (st.isEmpty() || st.getItem() == item) return false;
        String n = GuiUtil.name(st).trim();
        if (n.isEmpty()) return false;
        return GuiUtil.nameMatches(st, Config.get().confirmKeywords);
    }

    private int findSlot(ScreenHandler h, java.util.function.Predicate<ItemStack> test) {
        int n = GuiUtil.topCount(h);
        for (int i = 0; i < n; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (!st.isEmpty() && test.test(st)) return i;
        }
        return -1;
    }

    private void ahAfter(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        timer++;
        if (Tools.count(p, item) > before) {
            closeGui(p);
            if (gained(p) >= want) finish(true);
            else { s = S.AH_CMD; timer = 0; }
            return;
        }
        ScreenHandler h = GuiUtil.open(p);
        if (h != null && timer >= 5 && timer % 5 == 0 && confirmClicks < 4) {
            int slot = findSlot(h, this::isConfirm);
            if (slot >= 0) { GuiUtil.click(mc, h, slot, 0, SlotActionType.PICKUP); confirmClicks++; }
        }
        if (timer > 80) {
            closeGui(p);
            fail("Mua không thành công (đủ tiền chưa?)", "Purchase did not complete (enough money?)");
        }
    }

    // ------------------------------------------------------------------ /shop

    private void shopCommand(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        Config cfg = Config.get();
        if (cfg.shopCommand == null || cfg.shopCommand.isBlank()) { fail("Chưa đặt lệnh shop", "No shop command set"); return; }
        if (GuiUtil.open(p) != null) { closeGui(p); return; }
        status = t("Mở /shop mua đồ ăn...", "Opening /shop to buy food...");
        GuiUtil.sendCommand(mc, cfg.shopCommand);
        cmdSent++;
        timer = 0;
        s = S.SHOP_RUN;
    }

    private void shopRun(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        Config cfg = Config.get();
        timer++;
        if (gained(p) >= want) { closeGui(p); finish(true); return; }
        ScreenHandler h = GuiUtil.open(p);
        if (h == null) {
            if (timer > 40) {
                if (clicks > 0 && gained(p) == 0 && cmdSent < 3) s = S.SHOP_CMD;
                else if (gained(p) > 0) finish(true);
                else fail("/shop không mở được", "/shop did not open");
            }
            return;
        }
        if (timer < 8 || timer % 6 != 0) return;
        if (clicks > 60) { closeGui(p); finish(gained(p) > 0); return; }
        status = t("Đang mua thịt bò nướng ", "Buying cooked beef ") + gained(p) + "/" + want;

        int slot = findSlot(h, this::isConfirm);
        if (slot >= 0) { GuiUtil.click(mc, h, slot, 0, SlotActionType.PICKUP); clicks++; return; }

        slot = findSlot(h, st -> st.getItem() == item || GuiUtil.nameMatches(st, cfg.foodKeywords));
        if (slot >= 0) { GuiUtil.click(mc, h, slot, 0, SlotActionType.PICKUP); clicks++; return; }

        final Set<String> seen = clickedCategories;
        slot = findSlot(h, st -> GuiUtil.nameMatches(st, cfg.categoryKeywords) && !seen.contains(GuiUtil.name(st)));
        if (slot >= 0) {
            clickedCategories.add(GuiUtil.name(h.slots.get(slot).getStack()));
            GuiUtil.click(mc, h, slot, 0, SlotActionType.PICKUP);
            clicks++;
            return;
        }
        closeGui(p);
        fail("Không thấy thịt bò nướng trong /shop", "Cooked beef not found in /shop");
    }
}
