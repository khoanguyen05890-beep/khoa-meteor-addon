package com.khoa.automationbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.slot.SlotActionType;
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
import java.util.Set;

import static com.khoa.automationbot.Lang.t;

/**
 * Auto Sand Bot: dò cát -> đi tới -> đào (xẻng tên có "amethyst" thì đào 3x3, chọn chỗ có nhiều cát nhất)
 * -> nhặt. Tự xử lý: đầy kho (bán /order hoặc cất shulker -> ender chest), xẻng sắp hỏng (mua exp, sửa bằng Mending).
 * Đói thì module (KhoaModule/Hunger) tự đi /shop mua cà rốt vàng rồi ăn.
 */
public final class AutoSand extends Task {
    private enum Phase { SEARCH, WALK, MINE, COLLECT }
    private enum Job { NONE, SELL, STASH, REPAIR }

    private static final double REACH = 4.0;

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
    private boolean breaking;
    public int minedCount;

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
        if (t == null || mc.player == null || (phase != Phase.WALK && phase != Phase.MINE)) return pts;
        Vec3d pp = mc.player.getEntityPos();
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
                if (bad.contains(b.asLong())) continue;
                double dist = Math.sqrt(Tools.distSq(b, pp));
                if (dist - (three ? 0.7 * 8 : 0) >= bs) continue;
                if (w.getBlockState(b).getBlock() != e.getKey()) continue;
                if (!exposed(w, b) || nearLava(w, b)) continue;
                if (b.getX() == pb.getX() && b.getZ() == pb.getZ() && b.getY() < pb.getY()) continue;   // không đào cát dưới chân
                double score = dist;
                if (three) score -= 0.7 * (bestPlane(w, b) - 1);     // chỗ nhiều cát (3x3 đầy) thì đáng đi xa hơn
                if (score < bs) { bs = score; best = b; }
            }
        }
        return best;
    }

    private boolean planPath(MinecraftClient mc) {
        final BlockPos tgt = target;
        final Vec3d c = Vec3d.ofCenter(tgt);
        Path path = Path.find(mc.world, mc.player.getBlockPos(), n -> {
            Vec3d eye = new Vec3d(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5);
            return eye.squaredDistanceTo(c) <= 3.6 * 3.6 && !n.equals(tgt) && !n.up().equals(tgt);
        }, tgt, 4000);
        if (path == null) return false;
        walker.set(path);
        return true;
    }

    private void markBad(BlockPos p) {
        if (p != null) bad.add(p.asLong());
        target = cur = null;
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

    private void runJob(MinecraftClient mc) {
        switch (job) {
            case SELL -> {
                boolean run = order.tick(mc);
                status = order.status;
                if (!run) endJob(order.success ? 20 : 600);
            }
            case STASH -> {
                boolean run = stash.tick(mc);
                status = stash.status;
                if (!run) endJob(stash.success ? 20 : 600);
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
        scanner.radius = SandConfig.get().radius;
        scanner.fullHeight = false;

        switch (phase) {
            case SEARCH -> {
                scanner.tick(mc);
                if (cooldown > 0) { cooldown--; return; }
                BlockPos best = rescueTarget(mc);
                if (best == null) best = pickTarget(mc);
                if (best == null) {
                    status = t("Đang dò cát... (đã quét ", "Scanning for sand... (scanned ") + scanner.scannedTotal + ")";
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
                    else if (++repaths > 15 || !planPath(mc)) markBad(target);
                }
            }
            case MINE -> mine(mc);
            case COLLECT -> {
                collectTimer++;
                boolean busy = collector.step(mc);
                status = t("Đang nhặt cát...", "Collecting sand...");
                if (collector.full) { phase = Phase.SEARCH; cooldown = cfg.delay(); return; }   // đầy -> đầu tick sau sẽ bán/cất
                if ((busy || collectTimer < 8) && collectTimer < 200) return;
                phase = Phase.SEARCH;
                cooldown = cfg.delay();
            }
        }
    }

    private void mine(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Config cfg = Config.get();
        if (target == null || cur == null) { phase = Phase.SEARCH; return; }

        if (!wanted.contains(w.getBlockState(cur).getBlock())) {       // block đã vỡ
            if (breaking) { Stats.mined++; minedCount++; }
            target = cur = null;
            area = List.of();
            Motion.release();
            if (cfg.autoCollect && breaking) { phase = Phase.COLLECT; collectTimer = 0; collector.start(); }
            else { phase = Phase.SEARCH; cooldown = cfg.delay(); }
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

        if (!Motion.lookAt(p, hit.getPos(), cfg.turnSpeed())) return;
        mc.interactionManager.updateBlockBreakingProgress(cur, hit.getSide());
        p.swingHand(Hand.MAIN_HAND);
        breaking = true;
        if (++timer > 240) markBad(target);
    }
}
