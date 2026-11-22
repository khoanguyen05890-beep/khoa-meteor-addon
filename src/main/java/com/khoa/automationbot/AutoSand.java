package com.khoa.automationbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.khoa.automationbot.Lang.t;

/**
 * Auto Sand Bot: dò cát -> đi tới -> đào (xẻng tên có "amethyst" thì đào 3x3, chọn chỗ có nhiều cát nhất)
 * -> nhặt. Tự xử lý: đầy kho (bán /order hoặc cất shulker -> ender chest), xẻng sắp hỏng (mua exp, sửa bằng Mending).
 * Đói thì module (KhoaModule/Hunger) tự đi /shop mua cà rốt vàng rồi ăn.
 */
public final class AutoSand extends Task {
    private enum Phase { SEARCH, WALK, MINE, COLLECT, EXPLORE, ESCAPE }
    private enum Job { NONE, SELL, STASH, REPAIR }

    private static final double REACH = 4.0;
    private static final int LOCAL_H = 7, LOCAL_DOWN = 5, LOCAL_UP = 4;   // quét tức thời quanh người
    private static final double BIAS = 0.2;                                // ưu tiên nhẹ chỗ có nhiều cát (3x3), vẫn là chỗ GẦN NHẤT

    private final BlockScanner scanner = new BlockScanner();
    private final ItemCollector collector = new ItemCollector();
    private final Walker walker = new Walker();
    private final Set<Long> bad = new HashSet<>();
    private Set<Block> wanted = new HashSet<>();
    private Phase phase = Phase.SEARCH;
    private Job job = Job.NONE;
    private SandOrder order;
    private SandStash stash;
    private SandRepair repair;
    private BlockPos target, cur;
    private List<BlockPos> area = List.of();
    private int timer, cooldown, refresh, repaths, collectTimer, jobCooldown, repairCooldown, dropTimer;
    private boolean breaking, pendingCollect;
    private BlockPos lastHit;
    private int badAge, baseY, caveTicks;
    public int minedCount;

    // chống kẹt / đi tìm cát
    private final Random rnd = new Random();
    private int failStreak, noTarget, escStage, escTimer;
    private double heading;
    private BlockPos escStart = BlockPos.ORIGIN;

    @Override public String name() { return "Auto Sand"; }

    @Override
    protected void onStart() {
        bad.clear();
        phase = Phase.SEARCH;
        job = Job.NONE;
        order = null; stash = null; repair = null;
        target = cur = null;
        area = List.of();
        cooldown = refresh = jobCooldown = repairCooldown = dropTimer = 0;
        failStreak = noTarget = escStage = escTimer = 0;
        pendingCollect = false;
        lastHit = null;
        badAge = 0;
        caveTicks = 0;
        baseY = MinecraftClient.getInstance().player != null ? MinecraftClient.getInstance().player.getBlockY() : 0;
        heading = rnd.nextDouble() * Math.PI * 2;
        minedCount = 0;
        walker.clear();
        scanner.restart();
        scanner.tracker = b -> wanted.contains(b);
        collector.start();
    }

    @Override
    protected void onStop() {
        walker.clear();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        if (mc.player != null && GuiUtil.open(mc.player) != null && job != Job.NONE) mc.player.closeHandledScreen();
        collector.stop();
        job = Job.NONE;
    }

    /** Đang bán/cất/sửa: module không được để Hunger chen vào giữa chừng. */
    public boolean busy() { return job != Job.NONE; }

    // ------------------------------------------------------------------ dữ liệu cho render

    public BlockPos targetPos() { return job != Job.NONE ? null : (cur != null ? cur : target); }

    public List<BlockPos> area() { return job != Job.NONE ? List.of() : area; }

    /** "Sợi chỉ": từ người chơi, qua các ô của đường đi, tới block đang nhắm. */
    public List<Vec3d> thread(MinecraftClient mc) {
        List<Vec3d> pts = new ArrayList<>();
        BlockPos t = targetPos();
        if (mc.player == null || job != Job.NONE) return pts;
        Vec3d pp = mc.player.getEntityPos();
        if (phase == Phase.EXPLORE || phase == Phase.ESCAPE) {
            pts.add(new Vec3d(pp.x, pp.y + 0.1, pp.z));
            for (BlockPos n : walker.remaining()) pts.add(new Vec3d(n.getX() + 0.5, n.getY() + 0.1, n.getZ() + 0.5));
            return pts;
        }
        if (t == null || (phase != Phase.WALK && phase != Phase.MINE)) return pts;
        pts.add(new Vec3d(pp.x, pp.y + 0.1, pp.z));
        if (phase == Phase.WALK) {
            for (BlockPos n : walker.remaining()) pts.add(new Vec3d(n.getX() + 0.5, n.getY() + 0.1, n.getZ() + 0.5));
        }
        pts.add(Vec3d.ofCenter(t));
        return pts;
    }

    // ------------------------------------------------------------------ xẻng

    private boolean wantThreeByThree() {
        SandConfig sc = SandConfig.get();
        if (!sc.amethyst3x3) return false;
        int s = bestShovelSlot(MinecraftClient.getInstance().player, false);
        return s >= 0 && SandUtil.hasKeyword(MinecraftClient.getInstance().player.getInventory().getStack(s), sc.shovelKeyword);
    }

    /** Xẻng tốt nhất trong túi (ưu tiên xẻng "amethyst" nếu bật 3x3). -1 nếu không có. */
    private int bestShovelSlot(ClientPlayerEntity p, boolean includeLow) {
        SandConfig sc = SandConfig.get();
        PlayerInventory inv = p.getInventory();
        BlockState sand = net.minecraft.block.Blocks.SAND.getDefaultState();
        int best = -1;
        boolean bestAm = false;
        float bestSp = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!SandUtil.isShovel(s)) continue;
            if (!includeLow && Tools.lowDurability(s)) continue;
            boolean am = sc.amethyst3x3 && SandUtil.hasKeyword(s, sc.shovelKeyword);
            float sp = s.getMiningSpeedMultiplier(sand);
            if (best < 0 || (am && !bestAm) || (am == bestAm && sp > bestSp)) { best = i; bestAm = am; bestSp = sp; }
        }
        return best;
    }

    /** Cầm xẻng tốt nhất. false = đang hoán đổi từ túi chính, thử lại tick sau. */
    private boolean equipShovel(MinecraftClient mc, BlockState st) {
        ClientPlayerEntity p = mc.player;
        int slot = bestShovelSlot(p, false);
        if (slot < 0) { Tools.selectTool(mc, st, true); return true; }   // không có xẻng: dùng công cụ khác / tay không
        PlayerInventory inv = p.getInventory();
        if (slot < 9) { inv.setSelectedSlot(slot); return true; }
        mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, Tools.slotId(slot), inv.getSelectedSlot(), SlotActionType.SWAP, p);
        return false;
    }

    // ------------------------------------------------------------------ chọn mục tiêu

    private Set<Block> computeWanted() {
        Set<Block> s = new HashSet<>();
        for (String id : Config.get().minerBlocks) {
            Identifier i = Identifier.tryParse(id);
            if (i != null && Registries.BLOCK.containsId(i)) s.add(Registries.BLOCK.get(i));
        }
        return s;
    }

    private BlockHitResult ray(MinecraftClient mc, Vec3d to) {
        return mc.world.raycast(new RaycastContext(mc.player.getEyePos(), to,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
    }

    private boolean inReach(ClientPlayerEntity p, BlockPos b) {
        return p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(b)) <= REACH * REACH;
    }

    private boolean exposed(ClientWorld w, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.offset(d);
            if (!w.getBlockState(n).isFullCube(w, n)) return true;
        }
        return false;
    }

    private boolean nearLava(ClientWorld w, BlockPos c) {
        for (Direction d : Direction.values()) {
            if (w.getBlockState(c.offset(d)).getFluidState().isIn(FluidTags.LAVA)) return true;
        }
        return false;
    }

    /** Số cát trong mặt phẳng 3x3 vuông góc với trục (0 = X, 1 = Y, 2 = Z) quanh c. */
    private int countPlane(ClientWorld w, BlockPos c, int axis) {
        int n = 0;
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                BlockPos q = axis == 0 ? c.add(0, a, b) : axis == 1 ? c.add(a, 0, b) : c.add(a, b, 0);
                if (wanted.contains(w.getBlockState(q).getBlock())) n++;
            }
        }
        return n;
    }

    private int bestPlane(ClientWorld w, BlockPos c) {
        return Math.max(countPlane(w, c, 1), Math.max(countPlane(w, c, 0), countPlane(w, c, 2)));
    }

    private List<BlockPos> plane(ClientWorld w, BlockPos c, Direction.Axis axis) {
        List<BlockPos> l = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                BlockPos q = switch (axis) {
                    case X -> c.add(0, a, b);
                    case Y -> c.add(a, 0, b);
                    case Z -> c.add(a, b, 0);
                };
                if (wanted.contains(w.getBlockState(q).getBlock())) l.add(q);
            }
        }
        return l;
    }

    /** Cát đang đè lên đầu/chân mình: đào ngay để khỏi ngạt. */
    private BlockPos rescueTarget(MinecraftClient mc) {
        BlockPos eye = BlockPos.ofFloored(mc.player.getEyePos());
        if (wanted.contains(mc.world.getBlockState(eye).getBlock())) return eye;
        BlockPos feet = mc.player.getBlockPos();
        if (wanted.contains(mc.world.getBlockState(feet).getBlock())) return feet;
        return null;
    }

    private BlockPos pickTarget(MinecraftClient mc) {
        ClientWorld w = mc.world;
        Vec3d pp = mc.player.getEntityPos();
        BlockPos pb = mc.player.getBlockPos();
        boolean three = wantThreeByThree();
        BlockPos best = null;
        double bs = Double.MAX_VALUE;
        for (var e : scanner.positions.entrySet()) {
            if (!wanted.contains(e.getKey())) continue;
            for (BlockPos b : e.getValue()) {
                if (bad.contains(b.asLong()) || tooLow(b)) continue;
                double dist = Math.sqrt(Tools.distSq(b, pp));
                if (dist - (three ? BIAS * 8 : 0) >= bs) continue;
                if (w.getBlockState(b).getBlock() != e.getKey()) continue;
                if (!exposed(w, b) || nearLava(w, b) || !surfaceOk(w, b)) continue;
                if (b.getX() == pb.getX() && b.getZ() == pb.getZ() && b.getY() < pb.getY()) continue;   // không đào cát dưới chân
                double score = dist;
                if (three) score -= BIAS * (bestPlane(w, b) - 1);    // ngang khoảng cách thì chọn chỗ nhiều cát hơn
                if (score < bs) { bs = score; best = b; }
            }
        }
        return best;
    }

    /** Cát lộ thiên: có mặt tiếp xúc với ô trống thấy trời (không phải cát trong hang/dưới lòng đất). */
    private boolean surfaceOk(ClientWorld w, BlockPos b) {
        if (!SandConfig.get().surfaceOnly) return true;
        for (Direction d : Direction.values()) {
            BlockPos n = b.offset(d);
            if (!w.getBlockState(n).isFullCube(w, n) && w.isSkyVisible(n)) return true;
        }
        return false;
    }

    /** Hạn chế đào xuống: không đào block thấp hơn (baseY - max-dig-down). Cát đè lên người thì vẫn đào. */
    private boolean tooLow(BlockPos b) { return b.getY() < baseY - SandConfig.get().maxDown; }

    /**
     * Cát GẦN NHẤT quanh người, quét tức thời (không phải chờ vòng quét lớn xong).
     * Cát trong tầm với (đứng yên đào được) luôn được ưu tiên trước cát phải đi tới.
     */
    private BlockPos localTarget(MinecraftClient mc) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        BlockPos pb = p.getBlockPos();
        Vec3d eye = p.getEyePos();
        boolean three = wantThreeByThree();
        BlockPos best = null;
        double bs = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -LOCAL_H; dx <= LOCAL_H; dx++) {
            for (int dz = -LOCAL_H; dz <= LOCAL_H; dz++) {
                for (int dy = -LOCAL_DOWN; dy <= LOCAL_UP; dy++) {
                    m.set(pb.getX() + dx, pb.getY() + dy, pb.getZ() + dz);
                    if (!wanted.contains(w.getBlockState(m).getBlock())) continue;
                    if (dx == 0 && dz == 0 && dy < 0) continue;            // không đào cát dưới chân
                    if (m.getY() < baseY - SandConfig.get().maxDown) continue; // hạn chế đào xuống
                    if (bad.contains(m.asLong())) continue;
                    double d2 = eye.squaredDistanceTo(m.getX() + 0.5, m.getY() + 0.5, m.getZ() + 0.5);
                    double score = Math.sqrt(d2);
                    if (d2 > REACH * REACH) score += 50;                   // tay chưa tới: xếp sau cát đứng yên đào được
                    if (score - (three ? BIAS * 8 : 0) >= bs) continue;
                    BlockPos b = m.toImmutable();
                    if (!exposed(w, b) || nearLava(w, b) || !surfaceOk(w, b)) continue;
                    if (three) score -= BIAS * (bestPlane(w, b) - 1);
                    if (score < bs) { bs = score; best = b; }
                }
            }
        }
        return best;
    }

    private int sandFound() {
        int n = 0;
        for (List<BlockPos> l : scanner.positions.values()) n += l.size();
        return n;
    }

    private boolean planPath(MinecraftClient mc) {
        final BlockPos tgt = target;
        final Vec3d c = Vec3d.ofCenter(tgt);
        Path.surfaceOnly = SandConfig.get().surfaceOnly;
        Path path;
        try {
            path = Path.find(mc.world, mc.player.getBlockPos(), n -> {
                Vec3d eye = new Vec3d(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5);
                return eye.squaredDistanceTo(c) <= 3.6 * 3.6 && !n.equals(tgt) && !n.up().equals(tgt);
            }, tgt, 12000);
        } finally { Path.surfaceOnly = false; }
        if (path == null) return false;
        walker.set(path);
        return true;
    }

    private void markBad(BlockPos p) {
        if (p != null) { bad.add(p.asLong()); failStreak++; }
        target = cur = null;
        lastHit = null;
        area = List.of();
        walker.clear();
        Motion.forward(false);
        Motion.jump(false);
        phase = Phase.SEARCH;
    }

    // ------------------------------------------------------------------ việc phụ: đầy kho / sửa xẻng

    private void beginJob(MinecraftClient mc) {
        walker.clear();
        Motion.release();
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        target = cur = null;
        lastHit = null;
        area = List.of();
    }

    private void endJob(int cooldownTicks) {
        job = Job.NONE;
        order = null; stash = null; repair = null;
        jobCooldown = Math.max(jobCooldown, cooldownTicks);
        Motion.release();
        phase = Phase.SEARCH;
        cooldown = Config.get().delay();
    }

    private boolean needsRepair(ClientPlayerEntity p) {
        SandConfig sc = SandConfig.get();
        if (!sc.autoRepair || p.isCreative()) return false;
        int slot = bestShovelSlot(p, true);
        return slot >= 0 && SandUtil.lowForRepair(p.getInventory().getStack(slot), sc.repairPercent);
    }

    private boolean nearlyFull(ClientPlayerEntity p) {
        return Junk.emptySlots(p) < SandConfig.get().reserveSlots;
    }

    /** Quăng đồ thừa (không phải cát/đồ cần giữ) trước khi tính là đầy kho. true = còn đồ thừa đang quăng. */
    private boolean dropJunk(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        boolean any = false;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getStack(i);
            if (!st.isEmpty() && !Junk.keep(st, Items.SAND)) { any = true; break; }
        }
        if (!any) return false;
        status = t("Quăng bớt đồ thừa...", "Dropping junk...");
        if (--dropTimer <= 0) { dropTimer = 4; Junk.dropOne(mc, Items.SAND); }
        return true;
    }

    private boolean maybeStartJob(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        SandConfig sc = SandConfig.get();
        if (repairCooldown <= 0 && needsRepair(p)) {
            int slot = bestShovelSlot(p, true);
            if (!SandUtil.hasMending(p.getInventory().getStack(slot))) {
                repairCooldown = 6000;
                status = t("Xẻng sắp hỏng nhưng không có Mending - không sửa được", "Shovel is low but has no Mending - cannot repair");
            } else {
                beginJob(mc);
                repair = new SandRepair(slot);
                job = Job.REPAIR;
                return true;
            }
        }
        if (jobCooldown <= 0 && nearlyFull(p) && SandUtil.sandCount(p) >= 64) {
            if (dropJunk(mc)) return true;
            beginJob(mc);
            if (sc.keepMode) { stash = new SandStash(); job = Job.STASH; }
            else { order = new SandOrder(); job = Job.SELL; }
            return true;
        }
        return false;
    }

    /** Báo kết quả việc phụ (bán/cất) ra chat để biết bot dừng ở đâu. */
    private void say(MinecraftClient mc, String msg) {
        if (mc.player != null && msg != null && !msg.isEmpty()) mc.player.sendMessage(Text.literal("[khoamoxe] " + msg), false);
    }

    private void runJob(MinecraftClient mc) {
        switch (job) {
            case SELL -> {
                boolean run = order.tick(mc);
                status = order.status;
                if (!run) { if (!order.success) say(mc, order.status); endJob(order.success ? 20 : 400); }
            }
            case STASH -> {
                boolean run = stash.tick(mc);
                status = stash.status;
                if (!run) { say(mc, stash.status); endJob(stash.success ? 20 : 400); }
            }
            case REPAIR -> {
                boolean run = repair.tick(mc);
                status = repair.status;
                if (!run) {
                    if (!repair.success) repairCooldown = 3000;
                    endJob(0);
                }
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ vòng lặp chính

    @Override
    public void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Config cfg = Config.get();
        if (--refresh <= 0) { wanted = computeWanted(); refresh = 20; }
        if (jobCooldown > 0) jobCooldown--;
        if (repairCooldown > 0) repairCooldown--;

        if (job != Job.NONE) { runJob(mc); return; }
        if (GuiUtil.open(p) != null) { p.closeHandledScreen(); return; }
        if (maybeStartJob(mc)) { if (job != Job.NONE) runJob(mc); return; }

        if (wanted.isEmpty()) {
            status = t("Chưa chọn block cát nào", "No sand block selected");
            return;
        }
        SandConfig sc = SandConfig.get();
        scanner.maxRadius = 160;
        scanner.maxPerBlock = 30000;
        scanner.budget = sc.radius > 40 ? 6000 : 2500;
        scanner.idleWait = 4;
        scanner.radius = sc.radius;
        scanner.fullHeight = true;                         // quét cực xa theo chiều ngang, ±scanHeight theo chiều cao
        scanner.minY = p.getBlockY() - sc.scanHeight;
        scanner.maxY = p.getBlockY() + sc.scanHeight;

        if (--dropTimer <= 0) { dropTimer = 10; if (phase != Phase.ESCAPE) Junk.dropOne(mc, Items.SAND); }   // luôn vứt block/đồ không liên quan
        { int y = p.getBlockY(); if (y > baseY || y < baseY - SandConfig.get().maxDown - 1) baseY = y; }   // leo cồn / rơi xuống thấp thật thì lấy mốc mới
        if (++badAge > 400) { badAge = 0; bad.clear(); }                       // cho thử lại các chỗ từng bị đánh dấu hỏng
        if (SandConfig.get().surfaceOnly && phase != Phase.ESCAPE) {              // lỡ xuống hang: tự thoát lên mặt đất
            if (w.isSkyVisible(p.getBlockPos().up())) caveTicks = 0;
            else if (++caveTicks > 100) { caveTicks = 0; startEscape(mc); }
        }
        if (phase != Phase.ESCAPE && failStreak >= 4) startEscape(mc);

        switch (phase) {
            case SEARCH -> {
                scanner.tick(mc);
                if (cooldown > 0) { cooldown--; return; }
                BlockPos best = rescueTarget(mc);
                if (best == null) best = localTarget(mc);                  // cát gần nhất, quét tức thời
                if (best == null) best = pickTarget(mc);                   // hết cát gần: lấy từ vòng quét xa
                if (best == null) {
                    if (pendingCollect) { pendingCollect = false; phase = Phase.COLLECT; collectTimer = 0; collector.start(); return; }
                    status = t("Đang dò cát... (đã quét ", "Scanning for sand... (scanned ") + scanner.scannedTotal
                        + t(", thấy ", ", found ") + sandFound() + ")";
                    if (++noTarget > 20 && scanner.cycles >= 1) startExplore(mc);
                    return;
                }
                noTarget = 0;
                if (pendingCollect && !inReach(p, best)) {                 // sắp phải đi chỗ khác: nhặt hết cát vừa đào trước
                    pendingCollect = false; phase = Phase.COLLECT; collectTimer = 0; collector.start();
                    return;
                }
                target = cur = best;
                area = List.of();
                repaths = 0;
                breaking = false;
                timer = 0;
                if (inReach(p, target)) phase = Phase.MINE;
                else if (planPath(mc)) phase = Phase.WALK;
                else markBad(best);
            }
            case WALK -> {
                scanner.tick(mc);
                if (target == null || !wanted.contains(w.getBlockState(target).getBlock())) { markBad(null); return; }
                if (inReach(p, target)) {
                    walker.clear();
                    Motion.forward(false);
                    Motion.jump(false);
                    phase = Phase.MINE;
                    timer = 0;
                    return;
                }
                status = t("Đi tới cát ", "Walking to sand ") + target.toShortString();
                Walker.Result r = walker.tick(mc);
                if (r == Walker.Result.FAILED) markBad(target);
                else if (r == Walker.Result.DONE) {
                    if (inReach(p, target)) { phase = Phase.MINE; timer = 0; }
                    else if (++repaths > 80 || !planPath(mc)) markBad(target);
                }
            }
            case MINE -> mine(mc);
            case EXPLORE -> explore(mc);
            case ESCAPE -> escape(mc);
            case COLLECT -> {
                collectTimer++;
                boolean busy = collector.step(mc);
                status = t("Đang nhặt cát...", "Collecting sand...");
                if (collector.full) { phase = Phase.SEARCH; cooldown = cfg.delay(); return; }   // đầy -> đầu tick sau sẽ bán/cất
                if ((busy || collectTimer < 2) && collectTimer < 120) return;
                phase = Phase.SEARCH;
                cooldown = cfg.delay();
            }
        }
    }

    // ------------------------------------------------------------------ đi tìm cát

    private void startExplore(MinecraftClient mc) {
        final BlockPos o = mc.player.getBlockPos();
        final Vec3d hint = new Vec3d(o.getX() + Math.cos(heading) * 48, o.getY(), o.getZ() + Math.sin(heading) * 48);
        Path.surfaceOnly = SandConfig.get().surfaceOnly;
        Path path;
        try {
            path = Path.find(mc.world, o, n -> {
                double dx = n.getX() - o.getX(), dz = n.getZ() - o.getZ();
                return dx * dx + dz * dz >= 18 * 18;
            }, BlockPos.ofFloored(hint), 8000);
        } finally { Path.surfaceOnly = false; }
        noTarget = 0;
        if (path == null || path.nodes.isEmpty()) {
            failStreak++;
            heading = rnd.nextDouble() * Math.PI * 2;
            return;
        }
        walker.set(path);
        timer = 0;
        phase = Phase.EXPLORE;
    }

    private void explore(MinecraftClient mc) {
        scanner.tick(mc);
        status = t("Không thấy cát gần đây - đi tìm xa hơn", "No sand nearby - exploring");
        if (++timer % 10 == 0 && pickTarget(mc) != null) {
            walker.clear();
            Motion.forward(false);
            Motion.jump(false);
            phase = Phase.SEARCH;
            return;
        }
        Walker.Result r = walker.tick(mc);
        if (r == Walker.Result.FAILED) {
            failStreak++;
            heading = rnd.nextDouble() * Math.PI * 2;          // hướng khác
            walker.clear();
            phase = Phase.SEARCH;
        } else if (r == Walker.Result.DONE) {
            walker.clear();
            baseY = mc.player.getBlockY();
            phase = Phase.SEARCH;
        }
    }

    // ------------------------------------------------------------------ kẹt: đào ra / đặt block leo lên

    private void startEscape(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        walker.clear();
        Motion.release();
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        target = cur = null;
        area = List.of();
        failStreak = 0;
        escStart = p.getBlockPos();
        escTimer = 0;
        phase = Phase.ESCAPE;
        final ClientWorld w = mc.world;
        final BlockPos o = escStart;
        // cách 1: đào xuyên tới chỗ thoáng (thấy trời)
        Path path = Path.find(w, o, n -> n.getManhattanDistance(o) >= 2 && Path.standable(w, n) && w.isSkyVisible(n),
                o.up(12), 9000, true);
        if (path != null && !path.nodes.isEmpty()) { walker.set(path); escStage = 0; }
        else escStage = 1;                                   // cách 2: đặt block dựng cột
    }

    private void endEscape() {
        walker.clear();
        Motion.release();
        bad.clear();
        escTimer = 0;
        baseY = MinecraftClient.getInstance().player.getBlockY();
        phase = Phase.SEARCH;
        cooldown = Config.get().delay();
    }

    /** Block dùng để leo: ưu tiên block rác, hết thì dùng cát. */
    private Item scaffoldItem(ClientPlayerEntity p) {
        Item sand = null;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem) || SandUtil.isShulker(s) || s.getItem() == Items.ENDER_CHEST) continue;
            if (SandUtil.isSand(s.getItem())) { if (sand == null) sand = s.getItem(); }
            else return s.getItem();
        }
        return sand;
    }

    private void escape(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Config cfg = Config.get();
        if (++escTimer > (escStage == 0 ? 700 : 400)) { endEscape(); return; }

        if (escStage == 0) {
            status = t("Bị kẹt: đào đường thoát", "Stuck: digging out");
            if (!walker.active()) { escStage = 1; escTimer = 0; return; }
            Walker.Result r = walker.tick(mc);
            if (r == Walker.Result.DONE) endEscape();
            else if (r == Walker.Result.FAILED) { walker.clear(); Motion.release(); escStage = 1; escTimer = 0; }
            return;
        }

        // cách 2: dựng cột (nhảy + đặt block dưới chân), đập block chắn trên đầu nếu có
        status = t("Bị kẹt: đặt block leo lên", "Stuck: pillaring up");
        int climbed = p.getBlockY() - escStart.getY();
        if (climbed >= 8 || (climbed >= 2 && w.isSkyVisible(p.getBlockPos().up()))) { endEscape(); return; }

        BlockPos head = p.getBlockPos().up(2);
        if (!Path.passable(w, head)) {
            BlockState hs = w.getBlockState(head);
            if (hs.getHardness(w, head) < 0 || !hs.getFluidState().isEmpty()) { endEscape(); return; }
            Motion.jump(false);
            Tools.selectTool(mc, hs, true);
            Vec3d c = Vec3d.ofCenter(head);
            if (!Motion.lookAt(p, c, cfg.turnSpeed())) return;
            Vec3d e = p.getEyePos();
            mc.interactionManager.updateBlockBreakingProgress(head, Direction.getFacing(e.x - c.x, e.y - c.y, e.z - c.z));
            p.swingHand(Hand.MAIN_HAND);
            return;
        }

        Item it = scaffoldItem(p);
        if (it == null) { endEscape(); return; }
        if (!Tools.hold(mc, p, it)) { Motion.jump(false); return; }
        Motion.forward(false);
        Motion.sneak(false);
        Motion.turn(p, p.getYaw(), 90f, 90f);
        BlockPos under = BlockPos.ofFloored(p.getX(), p.getY(), p.getZ()).down();
        BlockPos sup = under.down();
        if (w.getBlockState(under).isReplaceable() && Path.floorOk(w, sup) && p.getPitch() > 80f) {
            Vec3d hv = Vec3d.ofCenter(sup).add(0, 0.5, 0);
            var res = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hv, Direction.UP, sup, false));
            if (res.isAccepted()) p.swingHand(Hand.MAIN_HAND);
        }
        Motion.jump(true);
    }

    private void mine(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Config cfg = Config.get();
        if (target == null || cur == null) { phase = Phase.SEARCH; return; }

        if (!wanted.contains(w.getBlockState(cur).getBlock())) {       // block đã vỡ
            if (breaking) { Stats.mined++; minedCount++; failStreak = 0; noTarget = 0; if (cfg.autoCollect) pendingCollect = true; }
            lastHit = null;
            area = List.of();
            if (SandConfig.get().fast) {                                // đào nhanh: sang ngay cát kế tiếp trong tầm với, nhặt gộp sau
                BlockPos next = rescueTarget(mc);
                if (next == null) next = localTarget(mc);
                if (next != null && inReach(p, next)) { target = cur = next; timer = 0; breaking = false; return; }
            }
            target = cur = null;
            Motion.release();
            if (pendingCollect) { pendingCollect = false; phase = Phase.COLLECT; collectTimer = 0; collector.start(); }
            else { phase = Phase.SEARCH; cooldown = SandConfig.get().fast ? 0 : cfg.delay(); }
            return;
        }

        Vec3d center = Vec3d.ofCenter(cur);
        BlockHitResult hit = ray(mc, center);
        if (hit.getType() != HitResult.Type.BLOCK) { markBad(target); return; }
        BlockPos hp = hit.getBlockPos();
        if (!hp.equals(cur)) {
            if (wanted.contains(w.getBlockState(hp).getBlock())) { cur = target = hp; return; }   // cát khác che trước -> đào nó luôn
            markBad(target);
            return;
        }
        if (p.getEyePos().squaredDistanceTo(center) > (REACH + 0.5) * (REACH + 0.5)) {
            if (planPath(mc)) phase = Phase.WALK; else markBad(target);
            return;
        }
        if (nearLava(w, cur)) { markBad(target); return; }
        BlockState st = w.getBlockState(cur);
        if (!equipShovel(mc, st)) return;

        boolean three = wantThreeByThree() && SandUtil.hasKeyword(p.getMainHandStack(), SandConfig.get().shovelKeyword);
        area = three ? plane(w, cur, hit.getSide().getAxis()) : List.of();
        status = (three ? t("Đào 3x3 (", "Mining 3x3 (") + area.size() + ") " : t("Đang đào ", "Mining ")) + st.getBlock().getName().getString();

        boolean fast = SandConfig.get().fast;
        float ts = fast ? Math.max(cfg.turnSpeed(), 90f) : cfg.turnSpeed();
        if (!Motion.lookAt(p, hit.getPos(), ts)) return;
        if (fast && !cur.equals(lastHit)) {                             // block mới: attackBlock không dính độ trễ 5 tick sau mỗi lần vỡ
            mc.interactionManager.attackBlock(cur, hit.getSide());
            lastHit = cur;
        } else {
            mc.interactionManager.updateBlockBreakingProgress(cur, hit.getSide());
        }
        p.swingHand(Hand.MAIN_HAND);
        breaking = true;
        if (++timer > 240) markBad(target);
    }
}
