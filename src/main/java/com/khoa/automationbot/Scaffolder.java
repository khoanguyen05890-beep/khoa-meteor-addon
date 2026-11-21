package com.khoa.automationbot;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.khoa.automationbot.Lang.t;

/**
 * Block tạm để TỚI được chỗ cần đặt block thật:
 *  - bridge(): đặt block liên tục (cầu ngang theo hướng tới đích, hoặc dựng cột lên cao), nhớ từng block đã đặt;
 *  - cleanup(): đập hết block tạm đó (từ block đặt sau cùng ngược về), cầu thì lùi dần theo cầu, cột thì đập block dưới chân và rơi xuống.
 */
public final class Scaffolder {
    public enum R { RUNNING, DONE, FAILED }

    public String status = "";

    private final List<BlockPos> placed = new ArrayList<>();
    private final Walker walker = new Walker();
    private BlockPos pathKey;
    private int ticks, cool, wait, cleanTicks;

    public void reset() {
        placed.clear();
        walker.clear();
        pathKey = null;
        ticks = cool = wait = cleanTicks = 0;
    }

    public void startBridge() {
        ticks = 0;
        cool = 0;
        wait = 0;
        walker.clear();
        pathKey = null;
    }

    public boolean has() { return !placed.isEmpty(); }

    public boolean owns(BlockPos p) { return placed.contains(p); }

    public void record(BlockPos p) { if (!placed.contains(p)) placed.add(p.toImmutable()); }

    private static void stopKeys() {
        Motion.forward(false);
        Motion.jump(false);
    }

    // ------------------------------------------------------------------ đặt block tạm tới đích

    /** Đi tới chỗ mà mắt cách `goal` <= 3.6 block bằng cách đặt block tạm (cầu ngang / cột). */
    public R bridge(MinecraftClient mc, BlockPos goal, Item sc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Config cfg = Config.get();
        if (++ticks > 700) { stopKeys(); return R.FAILED; }
        if (cool > 0) cool--;

        BlockPos feet = p.getBlockPos();
        Vec3d gc = Vec3d.ofCenter(goal);
        boolean inside = feet.equals(goal) || feet.up().equals(goal);
        if (inside) { stopKeys(); return R.FAILED; }
        if (p.getEyePos().squaredDistanceTo(gc) <= 3.6 * 3.6) { stopKeys(); Motion.sneak(false); return R.DONE; }

        if (!Tools.hold(mc, p, sc)) {
            stopKeys();
            status = t("Cầm block dựng cầu...", "Holding scaffold block...");
            return R.RUNNING;
        }

        double dx = gc.x - p.getX(), dz = gc.z - p.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        int dy = goal.getY() - feet.getY();

        // gần theo chiều ngang nhưng còn cao: dựng cột tại chỗ
        if (horiz <= 2.5) {
            if (dy < 2) { stopKeys(); return R.FAILED; }
            status = t("Đặt block dựng cột lên đích...", "Placing blocks to climb to the target...");
            Motion.forward(false);
            Motion.sneak(false);
            Motion.turn(p, p.getYaw(), 90f, 90f);
            BlockPos under = BlockPos.ofFloored(p.getX(), p.getY(), p.getZ()).down();
            BlockPos sup = under.down();
            if (w.getBlockState(under).isReplaceable() && Path.floorOk(w, sup) && p.getPitch() > 80f) {
                Vec3d hv = Vec3d.ofCenter(sup).add(0, 0.5, 0);
                ActionResult res = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hv, Direction.UP, sup, false));
                if (res.isAccepted()) { p.swingHand(Hand.MAIN_HAND); record(under); }
            }
            Motion.jump(true);
            return R.RUNNING;
        }

        // xa: cầu ngang theo hướng tới đích
        Direction d = Direction.getFacing(dx, 0, dz);
        if (d.getAxis() == Direction.Axis.Y) { stopKeys(); return R.FAILED; }
        BlockPos n = feet.offset(d);
        if (!Path.passable(w, n) || !Path.passable(w, n.up())) { stopKeys(); status = t("Cầu bị chặn", "Bridge is blocked"); return R.FAILED; }

        BlockPos under = feet.down();
        Motion.sneak(true);                      // sneak để không rơi khỏi mép cầu
        if (!Path.floorOk(w, under)) {           // đang rơi / block chưa kịp cập nhật
            stopKeys();
            if (++wait > 60) return R.FAILED;
            status = t("Chờ đứng vững...", "Waiting to stand...");
            return R.RUNNING;
        }
        wait = 0;
        BlockPos floor = n.down();
        if (!Path.floorOk(w, floor)) {
            BlockState fs = w.getBlockState(floor);
            if (!fs.isReplaceable()) { stopKeys(); return R.FAILED; }
            stopKeys();
            status = t("Đặt block dựng cầu...", "Bridging...");
            Vec3d hv = Vec3d.ofCenter(under).add(d.getOffsetX() * 0.5, 0.4, d.getOffsetZ() * 0.5);
            if (!Motion.lookAt(p, hv, cfg.turnSpeed())) return R.RUNNING;
            if (cool > 0) return R.RUNNING;
            ActionResult res = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hv, d, under, false));
            if (res.isAccepted()) { p.swingHand(Hand.MAIN_HAND); record(floor); }
            cool = cfg.placeWait();
            return R.RUNNING;
        }
        status = t("Đi trên cầu...", "Walking the bridge...");
        float yawD = (float) (MathHelper.atan2(d.getOffsetZ(), d.getOffsetX()) * 57.29577951308232) - 90f;
        Motion.turn(p, yawD, 30f, 60f);
        Motion.forward(true);
        return R.RUNNING;
    }

    // ------------------------------------------------------------------ đập block tạm

    /** Đập hết block tạm. DONE khi không còn gì. */
    public R cleanup(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        placed.removeIf(b -> {
            BlockState s = w.getBlockState(b);
            return s.isAir() || s.isReplaceable();
        });
        if (placed.isEmpty()) {
            walker.clear();
            pathKey = null;
            cleanTicks = 0;
            stopKeys();
            Motion.sneak(false);
            return R.DONE;
        }
        if (++cleanTicks > 2500) { placed.clear(); stopKeys(); cleanTicks = 0; return R.FAILED; }
        status = t("Đập block tạm (" + placed.size() + ")", "Removing scaffold blocks (" + placed.size() + ")");

        BlockPos feet = p.getBlockPos();
        BlockPos last = placed.get(placed.size() - 1);
        Motion.sneak(false);

        if (last.equals(feet.down())) {
            if (placed.size() >= 2) {
                BlockPos prev = placed.get(placed.size() - 2);
                if (!last.equals(prev.up())) {
                    // cầu: lùi về block đặt trước đó rồi mới đập block cuối
                    return walk(mc, n -> n.equals(prev.up()), prev.up(), last);
                }
            }
            return breakBlock(mc, last);         // cột (hoặc block cuối cùng): đập block dưới chân rồi rơi xuống
        }
        Vec3d c = Vec3d.ofCenter(last);
        if (p.getEyePos().squaredDistanceTo(c) <= 4.2 * 4.2) {
            walker.clear();
            pathKey = null;
            stopKeys();
            return breakBlock(mc, last);
        }
        return walk(mc, n -> new Vec3d(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5).squaredDistanceTo(c) <= 3.6 * 3.6
                && !n.equals(last) && !n.up().equals(last), last, last);
    }

    private R walk(MinecraftClient mc, Predicate<BlockPos> goal, BlockPos hint, BlockPos dropIfFail) {
        if (!hint.equals(pathKey) || !walker.active()) {
            Path path = Path.find(mc.world, mc.player.getBlockPos(), goal, hint, 3000);
            if (path == null || !path.complete) {
                walker.clear();
                pathKey = null;
                placed.remove(dropIfFail);       // không tới được thì bỏ qua block này
                return R.RUNNING;
            }
            walker.set(path);
            pathKey = hint;
        }
        Walker.Result r = walker.tick(mc);
        if (r == Walker.Result.FAILED) {
            walker.clear();
            pathKey = null;
            placed.remove(dropIfFail);
        } else if (r == Walker.Result.DONE) {
            walker.clear();
            pathKey = null;
        }
        return R.RUNNING;
    }

    private R breakBlock(MinecraftClient mc, BlockPos pos) {
        ClientPlayerEntity p = mc.player;
        BlockState st = mc.world.getBlockState(pos);
        stopKeys();
        Tools.selectTool(mc, st, true);
        Vec3d c = Vec3d.ofCenter(pos);
        if (!Motion.lookAt(p, c, Config.get().turnSpeed())) return R.RUNNING;
        Vec3d e = p.getEyePos();
        Direction side = Direction.getFacing(e.x - c.x, e.y - c.y, e.z - c.z);
        mc.interactionManager.updateBlockBreakingProgress(pos, side);
        p.swingHand(Hand.MAIN_HAND);
        return R.RUNNING;
    }
}
