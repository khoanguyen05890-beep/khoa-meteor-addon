package com.khoa.automationbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Đi theo một Path bằng cách giả lập phím W/Space và xoay camera. */
public final class Walker {
    public enum Result { RUNNING, DONE, FAILED }

    private List<BlockPos> path = List.of();
    private int idx;
    private int stuck;
    private Vec3d last;
    public boolean complete;

    public void set(Path p) { path = p.nodes; idx = 0; stuck = 0; last = null; complete = p.complete; }
    public void clear() { path = List.of(); idx = 0; stuck = 0; last = null; }
    public boolean active() { return idx < path.size(); }

    public Result tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (idx >= path.size()) { Motion.forward(false); Motion.jump(false); return Result.DONE; }
        BlockPos pb = p.getBlockPos();
        for (int i = path.size() - 1; i >= idx; i--) {
            if (path.get(i).equals(pb)) { idx = i + 1; break; }
        }
        if (idx >= path.size()) { Motion.forward(false); Motion.jump(false); return Result.DONE; }
        BlockPos t = path.get(idx);
        double dx = t.getX() + 0.5 - p.getX(), dz = t.getZ() + 0.5 - p.getZ();
        double hd = Math.sqrt(dx * dx + dz * dz);
        if (hd < 0.3 && Math.abs(p.getY() - t.getY()) < 1.2) {
            idx++;
            if (idx >= path.size()) { Motion.forward(false); Motion.jump(false); return Result.DONE; }
            return Result.RUNNING;
        }
        float yaw = (float) (MathHelper.atan2(dz, dx) * 57.29577951308232) - 90f;
        Motion.turn(p, yaw, p.getPitch(), Math.max(30f, Config.get().turnSpeed()));
        float diff = Math.abs(MathHelper.wrapDegrees(yaw - p.getYaw()));
        Motion.sneak(false);
        Motion.forward(diff < 50f);
        Motion.jump(t.getY() > p.getY() + 0.15);

        Vec3d pos = p.getPos();
        if (last != null && pos.squaredDistanceTo(last) < 0.0004) stuck++; else stuck = 0;
        last = pos;
        if (stuck > 30) { Motion.forward(false); Motion.jump(false); return Result.FAILED; }
        return Result.RUNNING;
    }
}
