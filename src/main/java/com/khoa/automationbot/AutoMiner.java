package com.khoa.automationbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.khoa.automationbot.Lang.t;

public final class AutoMiner extends Task {
    private enum Phase { SEARCH, WALK, MINE, COLLECT }

    private static final double REACH = 4.0;

    private final BlockScanner scanner = new BlockScanner();
    private final ItemCollector collector = new ItemCollector();
    private final Walker walker = new Walker();
    private final Set<Long> bad = new HashSet<>();
    private Set<Block> wanted = new HashSet<>();
    private Phase phase = Phase.SEARCH;
    private BlockPos target, cur;
    private int timer, cooldown, refresh, repaths, collectTimer;
    private boolean breaking;
    public int minedCount;

    @Override public String name() { return "Auto Miner"; }

    @Override
    protected void onStart() {
        bad.clear();
        phase = Phase.SEARCH;
        target = cur = null;
        cooldown = 0;
        refresh = 0;
        minedCount = 0;
        walker.clear();
        scanner.restart();
        scanner.tracker = b -> wanted.contains(b);
        collector.start();            // trạng thái RUNNING chỉ mang tính nội bộ
    }

    @Override
    protected void onStop() {
        walker.clear();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        collector.stop();
    }

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

    private void markBad(BlockPos p) {
        if (p != null) bad.add(p.asLong());
        target = cur = null;
        walker.clear();
        Motion.forward(false);
        Motion.jump(false);
        phase = Phase.SEARCH;
    }

    @Override
    public void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Config cfg = Config.get();
        if (--refresh <= 0) { wanted = computeWanted(); refresh = 20; }
        if (wanted.isEmpty()) {
            status = t("Chưa chọn block nào", "No block selected");
            return;
        }
        scanner.radius = cfg.mineRadius;

        switch (phase) {
            case SEARCH -> {
                scanner.tick(mc);
                if (cooldown > 0) { cooldown--; return; }
                BlockPos best = pickTarget(mc);
                if (best == null) {
                    status = t("Đang tìm block... (đã quét ", "Searching... (scanned ") + scanner.scannedTotal + ")";
                    return;
                }
                target = cur = best;
                repaths = 0;
                breaking = false;
                timer = 0;
                if (inReach(p, target)) { phase = Phase.MINE; }
                else if (planPath(mc)) { phase = Phase.WALK; }
                else markBad(best);
            }
            case WALK -> {
                scanner.tick(mc);
                if (target == null || !wanted.contains(w.getBlockState(target).getBlock())) {
                    markBad(null);
                    return;
                }
                if (inReach(p, target)) {
                    walker.clear();
                    Motion.forward(false);
                    Motion.jump(false);
                    phase = Phase.MINE;
                    timer = 0;
                    return;
                }
                status = t("Đang di chuyển tới ", "Moving to ") + target.toShortString();
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
                if (collector.full) {
                    pause();
                    status = t("Túi đồ đầy", "Inventory full");
                    return;
                }
                status = t("Đang nhặt vật phẩm rơi...", "Collecting drops...");
                if ((busy || collectTimer < 8) && collectTimer < 240) return;
                phase = Phase.SEARCH;
                cooldown = cfg.delay();
            }
        }
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

    private BlockPos pickTarget(MinecraftClient mc) {
        ClientWorld w = mc.world;
        Vec3d pp = mc.player.getEntityPos();
        double max = Config.get().mineRadius;
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (var e : scanner.positions.entrySet()) {
            if (!wanted.contains(e.getKey())) continue;
            List<BlockPos> list = e.getValue();
            for (BlockPos b : list) {
                if (bad.contains(b.asLong())) continue;
                double d = Tools.distSq(b, pp);
                if (d > max * max || d >= bd) continue;
                if (w.getBlockState(b).getBlock() != e.getKey()) continue;
                if (!exposed(w, b)) continue;
                bd = d;
                best = b;
            }
        }
        return best;
    }

    private void mine(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Config cfg = Config.get();
        if (target == null || cur == null) { phase = Phase.SEARCH; return; }

        if (!cur.equals(target) && w.getBlockState(cur).isAir()) cur = target;   // đã phá vật cản
        if (cur.equals(target) && !wanted.contains(w.getBlockState(target).getBlock())) {
            if (breaking) {
                Stats.mined++;
                minedCount++;
            }
            target = cur = null;
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
            if (cfg.onlyMineSelected) { markBad(target); return; }
            cur = hp;
            return;
        }
        if (p.getEyePos().squaredDistanceTo(center) > (REACH + 0.5) * (REACH + 0.5)) {
            if (planPath(mc)) phase = Phase.WALK; else markBad(target);
            return;
        }
        BlockState st = w.getBlockState(cur);
        if (!Tools.selectTool(mc, st, cfg.autoTool)) {
            pause();
            status = t("Cần công cụ phù hợp / công cụ sắp hỏng", "Need a suitable tool / tool nearly broken");
            return;
        }
        status = t("Đang đào ", "Mining ") + st.getBlock().getName().getString();
        if (!Motion.lookAt(p, hit.getPos(), cfg.turnSpeed())) return;
        mc.interactionManager.updateBlockBreakingProgress(cur, hit.getSide());
        p.swingHand(Hand.MAIN_HAND);
        breaking = true;
        if (++timer > 240) markBad(target);
    }
}
