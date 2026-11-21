package com.khoa.automationbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Điều khiển người chơi qua phím bấm ảo và xoay camera (giống input thật). */
public final class Motion {
    private Motion() {}

    private static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    public static void forward(boolean v) { if (mc().options != null) mc().options.forwardKey.setPressed(v); }
    public static void jump(boolean v) { if (mc().options != null) mc().options.jumpKey.setPressed(v); }
    public static void sneak(boolean v) { if (mc().options != null) mc().options.sneakKey.setPressed(v); }
    public static void use(boolean v) { if (mc().options != null) mc().options.useKey.setPressed(v); }

    public static void release() {
        if (mc().options == null) return;
        mc().options.forwardKey.setPressed(false);
        mc().options.jumpKey.setPressed(false);
        mc().options.sneakKey.setPressed(false);
        mc().options.useKey.setPressed(false);
    }

    /** Xoay dần tới (yaw, pitch). Trả về true nếu đã gần đủ chính xác. */
    public static boolean turn(ClientPlayerEntity p, float yaw, float pitch, float max) {
        float dy = MathHelper.wrapDegrees(yaw - p.getYaw());
        float dp = pitch - p.getPitch();
        float sy = MathHelper.clamp(dy, -max, max);
        float sp = MathHelper.clamp(dp, -max, max);
        p.setYaw(p.getYaw() + sy);
        p.setPitch(MathHelper.clamp(p.getPitch() + sp, -90f, 90f));
        return Math.abs(dy - sy) < 3f && Math.abs(dp - sp) < 3f;
    }

    public static boolean lookAt(ClientPlayerEntity p, Vec3d t, float max) {
        Vec3d e = p.getEyePos();
        double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * 57.29577951308232) - 90f;
        float pitch = (float) -(MathHelper.atan2(dy, h) * 57.29577951308232);
        return turn(p, yaw, pitch, max);
    }
}
