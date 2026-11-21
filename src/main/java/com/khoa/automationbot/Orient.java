package com.khoa.automationbot;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Property;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Tính cách đặt block để ra ĐÚNG HƯỚNG / thuộc tính của schematic (stairs, slab trên/dưới, log trục X/Y/Z,
 * furnace, chest, piston, observer, hopper, trapdoor, button, sign...).
 *
 * Cách làm: thử mọi tổ hợp (mặt tựa, điểm bấm trên mặt, yaw, pitch) và hỏi chính block đó
 * `getPlacementState(ItemPlacementContext)` xem nó sẽ ra trạng thái nào; chọn tổ hợp khớp schematic
 * và gần hướng nhìn tự nhiên nhất. Nhờ vậy không cần viết luật riêng cho từng loại block.
 */
public final class Orient {
    private Orient() {}

    public static final class Plan {
        public final Direction face;     // hướng từ ô cần đặt tới block tựa (sup = tgt.offset(face))
        public final Vec3d hit;          // điểm bấm trên block tựa
        public final float yaw, pitch;   // hướng nhìn cần có lúc đặt

        Plan(Direction face, Vec3d hit, float yaw, float pitch) {
            this.face = face;
            this.hit = hit;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static final Set<String> NAMES = Set.of("facing", "axis", "half", "face", "rotation", "type");

    private static boolean tracked(Property<?> p, Object v) {
        String n = p.getName();
        if (!NAMES.contains(n)) return false;
        String s = String.valueOf(v);
        if (n.equals("type")) return s.equalsIgnoreCase("bottom") || s.equalsIgnoreCase("top");   // slab trên/dưới
        if (n.equals("half")) return !(s.equalsIgnoreCase("upper") || s.equalsIgnoreCase("lower")); // cửa/cây cao tự ghép
        return true;
    }

    private static List<Property<?>> keys(BlockState want) {
        List<Property<?>> r = new ArrayList<>();
        for (Property<?> p : want.getProperties()) {
            if (tracked(p, want.get(p))) r.add(p);
        }
        return r;
    }

    /** Block này có thuộc tính hướng/vị trí cần chỉnh khi đặt không. */
    public static boolean needs(BlockState want) { return !keys(want).isEmpty(); }

    /** Nửa trên của cửa/cây cao, đầu giường: tự xuất hiện khi đặt nửa dưới, không đặt riêng. */
    public static boolean isSecondPart(BlockState st) {
        for (Property<?> p : st.getProperties()) {
            String n = p.getName();
            String v = String.valueOf(st.get(p));
            if (n.equals("half") && v.equalsIgnoreCase("upper")) return true;
            if (n.equals("part") && v.equalsIgnoreCase("head")) return true;
        }
        return false;
    }

    private static boolean matches(BlockState got, BlockState want, List<Property<?>> keys) {
        if (got == null || got.getBlock() != want.getBlock()) return false;
        for (Property<?> k : keys) {
            if (!got.contains(k)) return false;
            if (!Objects.equals(got.get(k), want.get(k))) return false;
        }
        return true;
    }

    private static Vec3d hitPoint(BlockPos sup, Direction click, double fy) {
        Vec3d c = Vec3d.ofCenter(sup).add(click.getOffsetX() * 0.5, click.getOffsetY() * 0.5, click.getOffsetZ() * 0.5);
        if (click.getAxis() != Direction.Axis.Y) c = new Vec3d(c.x, sup.getY() + fy, c.z);
        return c;
    }

    /** Kế hoạch đặt; null nếu không cần chỉnh hoặc không tìm được cách (khi đó đặt như bình thường). */
    public static Plan plan(MinecraftClient mc, BlockPos tgt, BlockState want, Item item) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        List<Property<?>> keys = keys(want);
        if (keys.isEmpty()) return null;

        boolean fine = false;
        for (Property<?> k : keys) if (k.getName().equals("rotation")) fine = true;     // biển báo/banner: 16 hướng
        float[] yaws;
        if (fine) {
            yaws = new float[16];
            for (int i = 0; i < 16; i++) yaws[i] = MathHelper.wrapDegrees(i * 22.5f);
        } else {
            yaws = new float[]{0f, 90f, 180f, -90f};
        }
        float[] pitches = {0f, 80f, -80f};

        float oy = p.getYaw(), op = p.getPitch();
        ItemStack stack = new ItemStack(item);
        Vec3d eye = p.getEyePos();
        Plan best = null;
        double bestScore = Double.MAX_VALUE;
        try {
            for (Direction face : Direction.values()) {
                BlockPos sup = tgt.offset(face);
                BlockState ss = w.getBlockState(sup);
                if (ss.isAir() || ss.isReplaceable() || !ss.getFluidState().isEmpty()) continue;
                Direction click = face.getOpposite();
                double[] fys = click.getAxis() == Direction.Axis.Y ? new double[]{0.5} : new double[]{0.25, 0.75};
                for (double fy : fys) {
                    Vec3d hit = hitPoint(sup, click, fy);
                    BlockHitResult bhr = new BlockHitResult(hit, click, sup, false);
                    double dx = hit.x - eye.x, dy = hit.y - eye.y, dz = hit.z - eye.z;
                    double natYaw = MathHelper.atan2(dz, dx) * 57.29577951308232 - 90.0;
                    double natPitch = -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 57.29577951308232);
                    for (float yaw : yaws) {
                        for (float pitch : pitches) {
                            p.setYaw(yaw);
                            p.setPitch(pitch);
                            BlockState got = null;
                            try {
                                got = want.getBlock().getPlacementState(new ItemPlacementContext(p, Hand.MAIN_HAND, stack, bhr));
                            } catch (Throwable ignored) {
                            }
                            if (!matches(got, want, keys) || !got.canPlaceAt(w, tgt)) continue;
                            double score = Math.abs(MathHelper.wrapDegrees((float) (yaw - natYaw))) + 0.5 * Math.abs(pitch - natPitch);
                            if (score < bestScore) {
                                bestScore = score;
                                best = new Plan(face, hit, yaw, pitch);
                            }
                        }
                    }
                }
            }
        } finally {
            p.setYaw(oy);
            p.setPitch(op);
        }
        return best;
    }
}
