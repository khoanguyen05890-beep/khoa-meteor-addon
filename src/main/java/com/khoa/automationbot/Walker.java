package com.khoa.automationbot;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Đi theo một Path bằng cách giả lập phím W/Space và xoay camera. Path dig thì tự đào ô cản. */
public final class Walker {
    public enum Result { RUNNING, DONE, FAILED }

    private List<BlockPos> path = List.of();
    private int idx;
    private int stuck, digTicks;
    private Vec3d last;
    private boolean dig;
    public boolean complete;

    public void set(Path p) { path = p.nodes; idx = 0; stuck = 0; digTicks = 0; last = null; complete = p.complete; dig = p.dig; }
    public void clear() { path = List.of(); idx = 0; stuck = 0; digTicks = 0; last = null; dig = false; }
    public boolean active() { return idx < path.size(); }

    /** Các ô còn lại của đường đi (để vẽ "sợi chỉ"). */
    public List<BlockPos> remaining() { return idx < path.size() ? path.subList(idx, path.size()) : List.of(); }

    private void stopKeys() { Motion.forward(false); Motion.jump(false); }

    /** null = không còn gì phải đào; RUNNING = đang đào; FAILED = không đào được. */
    private Result digFor(MinecraftClient mc, BlockPos pb, BlockPos t) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        Config cfg = Config.get();
        BlockPos[] cells = t.getY() > pb.getY()
                ? new BlockPos[]{pb.up(2), t.up(), t}
                : new BlockPos[]{t.up(), t};
        for (BlockPos cell : cells) {
            if (Path.passable(w, cell)) continue;
            BlockState st = w.getBlockState(cell);
            if (!st.getFluidState().isEmpty() || !Path.safeToDig(w, cell)) return Result.FAILED;
            stopKeys();
            if (!Tools.selectTool(mc, st, cfg.autoTool)) return Result.FAILED;
            Vec3d c = Vec3d.ofCenter(cell);
            Vec3d e = p.getEyePos();
            if (e.squaredDistanceTo(c) > 4.5 * 4.5) return Result.FAILED;
            stuck = 0;
            if (++digTicks > 400) return Result.FAILED;
            if (!Motion.lookAt(p, c, cfg.turnSpeed())) return Result.RUNNING;
            Direction side = Direction.getFacing(e.x - c.x, e.y - c.y, e.z - c.z);
            mc.interactionManager.updateBlockBreakingProgress(cell, side);
            p.swingHand(Hand.MAIN_HAND);
            return Result.RUNNING;
        }
        digTicks = 0;
        return null;
    }

    public Result tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (idx >= path.size()) { stopKeys(); return Result.DONE; }
        BlockPos pb = p.getBlockPos();
        for (int i = path.size() - 1; i >= idx; i--) {
            if (path.get(i).equals(pb)) { idx = i + 1; break; }
        }
        if (idx >= path.size()) { stopKeys(); return Result.DONE; }
        BlockPos t = path.get(idx);

        // dung nham/vực mới xuất hiện trên đường đi (dung nham chảy...): dừng để tìm đường vòng
        if (!Path.lavaNear(mc.world, pb) && Path.lavaNear(mc.world, t)) { stopKeys(); return Result.FAILED; }

        if (dig) {
            Result d = digFor(mc, pb, t);
            if (d != null) return d;
        }

        double dx = t.getX() + 0.5 - p.getX(), dz = t.getZ() + 0.5 - p.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        boolean dropping = t.getY() < pb.getY();
        if (hd < 0.3 && !dropping && Math.abs(p.getY() - t.getY()) < 1.2) {
            idx++;
            if (idx >= path.size()) { stopKeys(); return Result.DONE; }
            return Result.RUNNING;
        }
        if (hd < 0.3 && dropping) {
            // đang đứng ngay trên ô cần rơi xuống: chờ rơi (khi rơi tới nơi thì pb == t và idx tự tăng)
            stopKeys();
            if (++stuck > 80) return Result.FAILED;
            return Result.RUNNING;
        }
        float yaw = (float) (MathHelper.atan2(dz, dx) * 57.29577951308232) - 90f;
        Motion.turn(p, yaw, p.getPitch(), Math.max(30f, Config.get().turnSpeed()));
        float diff = Math.abs(MathHelper.wrapDegrees(yaw - p.getYaw()));
        Motion.sneak(false);
        Motion.forward(diff < 50f);
        Motion.jump(t.getY() > p.getY() + 0.15);

        Vec3d pos = p.getEntityPos();
        if (last != null && pos.squaredDistanceTo(last) < 0.0004) stuck++; else stuck = 0;
        last = pos;
        if (stuck > 30) { stopKeys(); return Result.FAILED; }
        return Result.RUNNING;
    }
}
