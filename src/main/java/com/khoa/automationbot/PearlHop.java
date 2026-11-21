package com.khoa.automationbot;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.khoa.automationbot.Lang.t;

/**
 * Ném ENDER PEARL để "bay" lên layer đang xây (thay cho việc dựng cột khi layer ở quá cao).
 *
 *  1. Tìm ô đứng được (đã có sàn thật) nằm trong tầm với của block cần đặt, schematic ở đó là không khí.
 *  2. Mô phỏng quỹ đạo ngọc (vận tốc 1.5, cản 0.99, trọng lực 0.03/tick) để tìm góc ném (pitch) trúng ô đó.
 *  3. Cầm ngọc, xoay đúng hướng, ném, chờ dịch chuyển.
 * Ngọc gây 5 sát thương (2.5 tim) khi hạ cánh nên chỉ dùng khi máu > 8.
 */
public final class PearlHop {
    public enum R { RUNNING, DONE, FAILED }

    public String status = "";

    private final BlockPos spot;
    private final float yaw, pitch;
    private int stage, timer, wait;
    private float ly = Float.NaN, lp = Float.NaN;
    private Vec3d startPos;

    private PearlHop(BlockPos spot, float yaw, float pitch) {
        this.spot = spot;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    // ------------------------------------------------------------------ lập kế hoạch

    /** null nếu không có chỗ đáp nào ném tới được. `schem` = thế giới schematic của Litematica. */
    public static PearlHop plan(MinecraftClient mc, BlockPos g, World schem) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Vec3d goal = Vec3d.ofCenter(g);
        Vec3d pp = p.getEntityPos();
        List<BlockPos> spots = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos n = new BlockPos(g.getX() + dx, g.getY() + dy, g.getZ() + dz);
                    if (n.equals(g) || n.up().equals(g)) continue;
                    if (!Tools.loaded(w, n.getX(), n.getZ())) continue;
                    if (!Path.standable(w, n)) continue;
                    if (!schem.getBlockState(n).isAir() || !schem.getBlockState(n.up()).isAir()) continue;   // đừng đứng chỗ sẽ xây
                    Vec3d e = new Vec3d(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5);
                    if (e.squaredDistanceTo(goal) > 3.6 * 3.6) continue;
                    double hx = n.getX() + 0.5 - pp.x, hz = n.getZ() + 0.5 - pp.z;
                    if (hx * hx + hz * hz > 48 * 48) continue;
                    spots.add(n);
                }
            }
        }
        spots.sort(Comparator.comparingDouble(n -> Vec3d.ofCenter(n).squaredDistanceTo(goal)));
        Vec3d eye = p.getEyePos();
        for (int i = 0; i < spots.size() && i < 8; i++) {
            float[] sol = solve(w, eye, spots.get(i));
            if (sol != null) return new PearlHop(spots.get(i), sol[0], sol[1]);
        }
        return null;
    }

    private static float[] solve(ClientWorld w, Vec3d eye, BlockPos spot) {
        double cx = spot.getX() + 0.5, cz = spot.getZ() + 0.5, cy = spot.getY();
        float yaw = (float) (MathHelper.atan2(cz - eye.z, cx - eye.x) * 57.29577951308232) - 90f;
        double bestScore = 1e9;
        float bestPitch = Float.NaN;
        for (int pt = -88; pt <= 88; pt++) {
            Vec3d land = simulate(w, eye, yaw, pt);
            if (land == null) continue;
            double dh = Math.hypot(land.x - cx, land.z - cz), dy = land.y - cy;
            if (dh > 1.0 || dy < -0.2 || dy > 1.2) continue;
            double score = dh + Math.abs(dy - 0.1);
            if (score < bestScore) { bestScore = score; bestPitch = pt; }
        }
        return Float.isNaN(bestPitch) ? null : new float[]{yaw, bestPitch};
    }

    /** Điểm cuối (còn trong không khí) của quỹ đạo ngọc trước khi chạm block; null nếu rơi mất. */
    private static Vec3d simulate(ClientWorld w, Vec3d eye, float yaw, float pitch) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double vx = -Math.sin(yr) * Math.cos(pr) * 1.5, vy = -Math.sin(pr) * 1.5, vz = Math.cos(yr) * Math.cos(pr) * 1.5;
        double x = eye.x, y = eye.y - 0.1, z = eye.z;
        for (int tick = 0; tick < 160; tick++) {
            double px = x, py = y, pz = z;
            for (int s = 1; s <= 6; s++) {
                double qx = x + vx * s / 6.0, qy = y + vy * s / 6.0, qz = z + vz * s / 6.0;
                if (solid(w, qx, qy, qz)) return tick == 0 && s == 1 ? null : new Vec3d(px, py, pz);
                px = qx; py = qy; pz = qz;
            }
            x += vx; y += vy; z += vz;
            vx *= 0.99; vy *= 0.99; vz *= 0.99;
            vy -= 0.03;
            if (y < w.getBottomY() - 8) return null;
        }
        return null;
    }

    private static boolean solid(ClientWorld w, double x, double y, double z) {
        BlockPos bp = BlockPos.ofFloored(x, y, z);
        BlockState s = w.getBlockState(bp);
        VoxelShape sh = s.getCollisionShape(w, bp);
        if (sh.isEmpty()) return false;
        Box b = sh.getBoundingBox().offset(bp);
        return b.contains(x, y, z);
    }

    // ------------------------------------------------------------------ thực thi

    public R tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (++timer > 400) return R.FAILED;
        Config cfg = Config.get();

        if (stage == 0) {
            status = t("Cầm ngọc ender và ngắm điểm đáp...", "Holding ender pearl and aiming...");
            int slot = Tools.findItem(p, Items.ENDER_PEARL);
            if (slot < 0) return R.FAILED;
            PlayerInventory inv = p.getInventory();
            if (slot >= 9) {
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, Tools.slotId(slot),
                        inv.getSelectedSlot(), SlotActionType.SWAP, p);
                return R.RUNNING;
            }
            inv.setSelectedSlot(slot);
            Motion.forward(false);
            Motion.jump(false);
            Motion.sneak(false);
            boolean ok = Motion.turn(p, yaw, pitch, Math.max(30f, cfg.turnSpeed()));
            boolean same = Math.abs(p.getYaw() - ly) < 0.05f && Math.abs(p.getPitch() - lp) < 0.05f;
            ly = p.getYaw();
            lp = p.getPitch();
            if (!ok || !same) return R.RUNNING;           // đợi server nhận hướng nhìn rồi mới ném
            startPos = p.getEntityPos();
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            p.swingHand(Hand.MAIN_HAND);
            stage = 1;
            wait = 0;
            return R.RUNNING;
        }

        status = t("Đã ném ngọc - chờ dịch chuyển lên layer...", "Pearl thrown - waiting to teleport up...");
        wait++;
        Vec3d pos = p.getEntityPos();
        double dh = Math.hypot(pos.x - (spot.getX() + 0.5), pos.z - (spot.getZ() + 0.5));
        if (dh < 2.0 && Math.abs(pos.y - spot.getY()) < 2.0) return R.DONE;
        if (startPos != null && pos.squaredDistanceTo(startPos) > 16) return R.DONE;   // đã dịch chuyển (chỗ đáp lệch cũng được)
        if (wait > 80) return R.FAILED;
        return R.RUNNING;
    }
}
