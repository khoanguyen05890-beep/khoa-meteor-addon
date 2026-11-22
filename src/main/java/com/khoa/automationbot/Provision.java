package com.khoa.automationbot;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.khoa.automationbot.Lang.t;

/**
 * Một "việc vặt" có đầu có cuối để kiếm vật phẩm:
 *  - item(): lấy trong rương/thùng gần đó -> nếu không có thì /ah <tên> mua cái RẺ NHẤT.
 *  - food(): /shop -> vào danh mục FOOD -> bấm món -> (chỉnh số lượng bằng ô "+ Thêm") -> bấm KÍNH XANH để mua.
 *  - Trước khi mua / lấy đồ: nếu túi gần đầy thì vứt đồ không cần thiết (xem Junk).
 * Gọi tick() mỗi tick cho tới khi trả về false; kết quả ở success/status.
 */
public final class Provision {
    private enum S { START, PICK_CHEST, WALK, OPEN, TAKE, AH_CMD, AH_LIST, AH_AFTER, SHOP_CMD, SHOP_RUN, ROOM }

    public boolean done, success;
    public String status = "";

    private final Item item;
    private final int want;
    private final boolean food;
    // shop tổng quát (shulker, ender chest, exp...): danh mục + từ khoá món + so khớp item tuỳ biến
    private boolean shop;
    private List<String> catKeys, itemKeys;
    private java.util.function.Predicate<Item> matcher;

    private S s = S.START;
    private int life, timer, startCount, before, buys, clicks, confirmClicks, cmdSent;
    private int noGui, wait, itemClicks, addClicks, buyClicks, lastQty = -1, qtyStall, pageClicks, lastGain;
    private int roomTries, roomDrops, gainAtCmd;
    private S afterRoom = S.START;
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

    /**
     * Mua 1 món bất kỳ ở /shop: /shop -> bấm danh mục (catKeys) -> bấm món (itemKeys) -> bấm "+ Thêm N" -> bấm kính xanh.
     * matcher (có thể null) cho phép coi nhiều item là "món cần mua" (vd mọi màu shulker box).
     */
    public static Provision shop(Item it, java.util.function.Predicate<Item> matcher, int amount,
                                 List<String> catKeys, List<String> itemKeys) {
        Provision p = new Provision(it, amount, false);
        p.shop = true;
        p.matcher = matcher;
        p.catKeys = catKeys;
        p.itemKeys = itemKeys;
        return p;
    }

    private boolean matches(ItemStack st) {
        return !st.isEmpty() && (matcher != null ? matcher.test(st.getItem()) : st.getItem() == item);
    }

    private int cnt(ClientPlayerEntity p) {
        if (matcher == null) return Tools.count(p, item);
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isEmpty() && matcher.test(s.getItem())) n += s.getCount();
        }
        return n;
    }

    private List<String> catKeywords() { return catKeys != null ? catKeys : Config.get().categoryKeywords; }
    private List<String> itemKeywords() { return itemKeys != null ? itemKeys : Config.get().foodKeywords; }

    private int gained(ClientPlayerEntity p) { return cnt(p) - startCount; }

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
        if (++life > 3000 + want * 40) { closeGui(p); fail("Quá thời gian", "Timed out"); return false; }

        switch (s) {
            case START -> {
                startCount = cnt(p);
                if (food || shop) { s = S.SHOP_CMD; break; }
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
            case ROOM -> roomTick(mc);
        }
        return !done;
    }

    // ------------------------------------------------------------------ vứt đồ thừa

    private boolean needRoom(ClientPlayerEntity p) {
        Config c = Config.get();
        return c.autoDrop && Junk.emptySlots(p) < Math.max(1, c.minFreeSlots);
    }

    /** Túi gần đầy -> chuyển sang trạng thái ROOM (vứt đồ thừa) rồi quay lại trạng thái `next`. */
    private boolean roomFirst(ClientPlayerEntity p, S next) {
        if (roomTries >= 6 || !needRoom(p)) return false;
        roomTries++;
        afterRoom = next;
        roomDrops = 0;
        timer = 0;
        s = S.ROOM;
        return true;
    }

    private void roomTick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        Config c = Config.get();
        if (GuiUtil.open(p) != null) { closeGui(p); return; }
        status = t("Vứt đồ không cần thiết để có chỗ trống...", "Dropping unneeded items to free up space...");
        if (Junk.emptySlots(p) >= Math.max(1, c.minFreeSlots)) { s = afterRoom; timer = 0; return; }
        if (++timer % 5 != 0) return;                       // mỗi lần vứt cách nhau vài tick
        if (roomDrops >= 16 || !Junk.dropOne(mc, item)) {
            if (Junk.emptySlots(p) > 0) { s = afterRoom; timer = 0; return; }
            fail("Túi đầy và không có gì để vứt", "Inventory full and nothing safe to drop");
            return;
        }
        roomDrops++;
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
                if (!Tools.fits(p, st)) {
                    closeGui(p);
                    if (Config.get().autoDrop && roomTries < 6) {          // vứt đồ thừa rồi quay lại rương này
                        chests.add(0, chest);
                        visited.remove(chest.asLong());
                        roomTries++;
                        afterRoom = S.PICK_CHEST;
                        roomDrops = 0;
                        timer = 0;
                        s = S.ROOM;
                        return;
                    }
                    fail("Túi đồ đầy", "Inventory full");
                    return;
                }
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
        if (roomFirst(p, S.AH_CMD)) return;
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
        if (GuiUtil.isGreen(st) && !GuiUtil.isAdd(st)) return true;      // kính xanh = xác nhận
        String n = GuiUtil.name(st).trim();
        if (n.isEmpty()) return false;
        return GuiUtil.nameMatches(st, Config.get().confirmKeywords);
    }

    /** Danh mục khớp theo TÊN hoặc (với đồ không phải thức ăn) theo ICON: vd "end stone" khớp icon end_stone, "totem" khớp totem_of_undying. */
    private boolean catMatch(ItemStack st) {
        if (GuiUtil.nameMatches(st, catKeywords())) return true;
        if (food || st.isEmpty()) return false;
        String path = Registries.ITEM.getId(st.getItem()).getPath();
        for (String k : catKeywords()) {
            if (k == null || k.isBlank()) continue;
            String kn = k.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
            if (path.contains(kn)) return true;
        }
        return false;
    }

    private String menuNames(ScreenHandler h, int n) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < n && seen.size() < 12; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (st.isEmpty()) continue;
            String nm = GuiUtil.name(st).trim();
            if (nm.isEmpty()) nm = Registries.ITEM.getId(st.getItem()).getPath();
            seen.add(nm);
        }
        for (String x : seen) { if (sb.length() > 0) sb.append(", "); sb.append(x); }
        return sb.toString();
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
    //
    // Luồng GUI của server (đã xem trong video):
    //   /shop            -> menu chính, mỗi danh mục là 1 icon (tiêu đề "SHOP")
    //   bấm FOOD         -> trang "SHOP -> FOOD", mỗi món là 1 icon (tooltip "Giá mua: ...")
    //   bấm món          -> trang "Mua <tên món>": hàng giữa có ô kính xanh "+ Thêm N", hàng dưới có
    //                       KÍNH ĐỎ (hủy) và KÍNH XANH (xác nhận mua)  => phải bấm kính xanh mới mua.

    private void shopCommand(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        Config cfg = Config.get();
        if (cfg.shopCommand == null || cfg.shopCommand.isBlank()) { fail("Chưa đặt lệnh shop", "No shop command set"); return; }
        if (GuiUtil.open(p) != null) { closeGui(p); return; }
        if (roomFirst(p, S.SHOP_CMD)) return;
        status = t("Mở /shop mua ", "Opening /shop to buy ") + item.getName().getString() + "...";
        GuiUtil.sendCommand(mc, cfg.shopCommand);
        cmdSent++;
        gainAtCmd = gained(p);
        timer = 0;
        noGui = 0;
        wait = 0;
        itemClicks = 0;
        addClicks = 0;
        lastQty = -1;
        qtyStall = 0;
        pageClicks = 0;
        clickedCategories.clear();
        s = S.SHOP_RUN;
    }

    /** Ô kính xanh xác nhận mua; -1 nếu GUI hiện tại không phải trang mua. */
    private int findConfirmSlot(ScreenHandler h, int n) {
        int bottomRow = Math.max(9, n - 9);
        int best = -1, fallback = -1;
        for (int i = 0; i < n; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (!GuiUtil.isGreen(st) || GuiUtil.isAdd(st)) continue;
            if (i >= bottomRow) {
                if (GuiUtil.nameMatches(st, Config.get().confirmKeywords)) return i;
                if (best < 0 || i == 23) best = i;
            } else if (fallback < 0) fallback = i;
        }
        return best >= 0 ? best : fallback;
    }

    /** Số lượng đang hiển thị trên món ở trang mua (1 nếu không đọc được). */
    private int displayedQty(ScreenHandler h, int n) {
        int q = 1;
        boolean seen = false;
        for (int i = 0; i < n; i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (matches(st)) { q = seen ? Math.max(q, st.getCount()) : st.getCount(); seen = true; }
        }
        return q;
    }

    /** Nút "Đặt thành N" (đặt thẳng số lượng) lớn nhất mà curQty < N <= maxQty; -1 nếu không có. */
    private int bestSetButton(ScreenHandler h, int n, int confirm, int maxQty, int curQty) {
        Pattern num = Pattern.compile("(\\d+)");
        int best = -1, bestV = 0;
        for (int i = 0; i < n; i++) {
            if (i == confirm) continue;
            ItemStack st = h.slots.get(i).getStack();
            if (!GuiUtil.isSet(st)) continue;
            Matcher m = num.matcher(GuiUtil.name(st));
            if (!m.find()) continue;
            int v;
            try { v = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { continue; }
            if (v > curQty && v <= maxQty && v > bestV) { bestV = v; best = i; }
        }
        return best;
    }

    /** Nút "+ Thêm N" lớn nhất mà N <= remaining; -1 nếu không có. */
    private int bestAddButton(ScreenHandler h, int n, int confirm, int remaining) {
        Pattern num = Pattern.compile("(\\d+)");
        int best = -1, bestV = 0;
        for (int i = 0; i < n; i++) {
            if (i == confirm) continue;
            ItemStack st = h.slots.get(i).getStack();
            if (!GuiUtil.isGreen(st) || !GuiUtil.isAdd(st) || GuiUtil.isSet(st)) continue;
            Matcher m = num.matcher(GuiUtil.name(st));
            if (!m.find()) continue;
            int v;
            try { v = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { continue; }
            if (v >= 1 && v <= remaining && v > bestV) { bestV = v; best = i; }
        }
        return best;
    }

    private void shopRun(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        Config cfg = Config.get();
        timer++;
        int gain = gained(p);
        if (gain >= want) { closeGui(p); finish(true); return; }
        if (gain > lastGain) { lastGain = gain; buyClicks = 0; }

        ScreenHandler h = GuiUtil.open(p);
        if (h == null) {
            // GUI đóng (server có thể đóng sau khi mua) -> mở lại /shop nếu còn thiếu
            if (++noGui > 30) {
                noGui = 0;
                if ((cmdSent < 6 || gain > gainAtCmd) && (clicks > 0 || gain > 0)) { s = S.SHOP_CMD; return; }   // server đóng GUI sau mỗi lần mua: mở lại khi vẫn đang có tiến triển
                fail("/shop không mở được", "/shop did not open");
            }
            return;
        }
        noGui = 0;
        if (wait > 0) { wait--; return; }               // chờ server cập nhật GUI sau mỗi lần bấm
        if (timer < 8) return;
        if (clicks > 90 + want * 2) { closeGui(p); finish(gain > 0); return; }

        int n = GuiUtil.topCount(h);
        String tl = GuiUtil.title(mc).toLowerCase(Locale.ROOT);
        int confirm = findConfirmSlot(h, n);

        // ---- Trang mua: chỉnh số lượng rồi bấm kính xanh ----
        if (confirm >= 0) {
            itemClicks = 0;
            // Không chỉnh số lượng: cứ bấm kính xanh xác nhận liên tục tới khi đủ `want` thì dừng
            status = t("Trang mua: bấm xác nhận liên tục ", "Buy page: clicking confirm repeatedly ") + gain + "/" + want;
            if (buyClicks >= 5) {
                closeGui(p);
                fail("Bấm mua nhiều lần nhưng không nhận được hàng (đủ tiền chưa?)", "Clicked buy several times but got nothing (enough money?)");
                return;
            }
            buyClicks++;
            GuiUtil.click(mc, h, confirm, 0, SlotActionType.PICKUP);   // kính xanh = MUA
            clicks++;
            wait = 6;
            return;
        }

        status = t("Đang tìm đồ ăn trong /shop ", "Looking for food in /shop ") + gain + "/" + want;

        // ---- Đang ở danh mục: bấm vào món cần mua (mở trang mua) ----
        int slot = findSlot(h, this::matches);
        if (slot < 0) slot = findSlot(h, st -> GuiUtil.nameMatches(st, itemKeywords()));
        if (slot >= 0) {
            if (++itemClicks > 8) {
                closeGui(p);
                fail("Bấm món nhưng không ra trang mua", "Clicked the item but no buy page appeared");
                return;
            }
            GuiUtil.click(mc, h, slot, 0, SlotActionType.PICKUP);
            clicks++;
            wait = 10;
            return;
        }

        // ---- Menu chính: chọn danh mục FOOD (theo tên, rồi theo icon là đồ ăn) ----
        boolean inCategory = tl.contains("->") || tl.contains("»") || tl.contains(">");
        if (!inCategory) {
            slot = findSlot(h, st -> catMatch(st) && !clickedCategories.contains(GuiUtil.name(st)));
            if (slot < 0) {
                slot = !food ? -1 : findSlot(h, st -> st.get(DataComponentTypes.FOOD) != null
                        && !clickedCategories.contains("#" + Tools.itemId(st.getItem())));
                if (slot >= 0) clickedCategories.add("#" + Tools.itemId(h.slots.get(slot).getStack().getItem()));
            } else {
                clickedCategories.add(GuiUtil.name(h.slots.get(slot).getStack()));
            }
            if (slot >= 0) {
                GuiUtil.click(mc, h, slot, 0, SlotActionType.PICKUP);
                clicks++;
                wait = 10;
                return;
            }
        }

        // ---- Danh mục nhiều trang: bấm trang kế ----
        if (pageClicks < 4) {
            slot = findSlot(h, st -> {
                String nm = GuiUtil.name(st).toLowerCase(Locale.ROOT);
                return nm.contains("next") || nm.contains("trang sau") || nm.contains("tiếp") || nm.contains("»") || nm.contains(">>");
            });
            if (slot >= 0) {
                pageClicks++;
                GuiUtil.click(mc, h, slot, 0, SlotActionType.PICKUP);
                clicks++;
                wait = 10;
                return;
            }
        }
        String seen = menuNames(h, n);
        closeGui(p);
        fail("Không thấy " + item.getName().getString() + " trong /shop (menu: " + seen + ")",
             item.getName().getString() + " not found in /shop (menu: " + seen + ")");
    }
}
