package com.khoa.automationbot;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

/**
 * Tích hợp Litematica bằng reflection (không cần phụ thuộc lúc biên dịch).
 * Đọc: WorldSchematic (thế giới schematic) + hộp bao của placement đang được chọn.
 *
 * Hộp bao được lấy theo thứ tự (cái nào có thì dùng):
 *   1. placement.getEclosingBox() / getEnclosingBox()
 *   2. hợp các sub-region của placement (getSubRegionBoxes)
 *   3. quét các chunk của thế giới schematic đang được nạp (không phụ thuộc API nội bộ của Litematica)
 */
public final class LitematicaBridge {
    private LitematicaBridge() {}

    public static String lastError = "";

    public static boolean loaded() { return FabricLoader.getInstance().isModLoaded("litematica"); }

    /** Bật "Render schematic" của Litematica: tắt thì thế giới schematic rỗng => bot tưởng đã xây xong. */
    public static void ensureRendering() {
        try {
            Class<?> c = Class.forName("fi.dy.masa.litematica.config.Configs$Visuals");
            Object cfg = c.getField("ENABLE_SCHEMATIC_RENDERING").get(null);
            Object cur = cfg.getClass().getMethod("getBooleanValue").invoke(cfg);
            if (cur instanceof Boolean b && !b) {
                cfg.getClass().getMethod("setBooleanValue", boolean.class).invoke(cfg, true);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Trả về Y của layer đang được Litematica hiển thị khi người dùng đang khóa đúng một layer.
     * Dùng reflection để tương thích nhiều bản Litematica; null nếu đang hiển thị nhiều layer.
     */
    public static Integer visibleLayerY() {
        try {
            Class<?> dm = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object range = call(dm, "getRenderLayerRange");
            if (range == null) return null;
            Integer lo = numberCall(range, "getLayerMinY");
            Integer hi = numberCall(range, "getLayerMaxY");
            if (lo == null) lo = numberCall(range, "getMinY");
            if (hi == null) hi = numberCall(range, "getMaxY");
            if (lo != null && hi != null && lo.equals(hi)) return lo;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Integer numberCall(Object target, String method) {
        if (target == null) return null;
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value instanceof Number n ? n.intValue() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object call(Class<?> type, String name) {
        try {
            return type.getMethod(name).invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static World schematicWorld() {
        try {
            Class<?> c = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
            Object o = c.getMethod("getSchematicWorld").invoke(null);
            if (o instanceof World w) return w;
            lastError = "SchematicWorldHandler.getSchematicWorld() = null";
        } catch (Throwable t) {
            lastError = t.toString();
        }
        return null;
    }

    /** Placement đang chọn; nếu không có thì lấy placement đang bật đầu tiên. */
    public static Object selectedPlacement() {
        try {
            Class<?> dm = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object mgr = dm.getMethod("getSchematicPlacementManager").invoke(null);
            Object pl = call(mgr, "getSelectedSchematicPlacement");
            if (pl != null) return pl;
            Object all = call(mgr, "getAllSchematicsPlacements");
            if (all instanceof Collection<?> col) {
                Object first = null;
                for (Object o : col) {
                    if (o == null) continue;
                    if (first == null) first = o;
                    if (Boolean.TRUE.equals(call(o, "isEnabled"))) return o;
                }
                if (first != null) return first;
            }
            lastError = "no placement selected";
        } catch (Throwable t) {
            lastError = t.toString();
        }
        return null;
    }

    /** {minX, minY, minZ, maxX, maxY, maxZ} của schematic trong thế giới, hoặc null. */
    public static int[] box() {
        lastError = "";
        Object pl = selectedPlacement();
        if (pl != null) {
            int[] r = fromEnclosing(pl);
            if (r != null) return r;
            r = fromSubRegions(pl);
            if (r != null) return r;
        }
        int[] r = scanSchematic();
        if (r != null) return r;
        if (lastError.isEmpty()) lastError = pl == null ? "no placement selected" : "cannot read placement size";
        return null;
    }

    // ------------------------------------------------------------------

    private static int[] fromEnclosing(Object pl) {
        // Litematica viết sai chính tả: "getEclosingBox" (thiếu chữ n); thử cả hai tên.
        for (Method m : pl.getClass().getMethods()) {
            String n = m.getName();
            if (m.getParameterCount() != 0) continue;
            if (!n.equals("getEclosingBox") && !n.equals("getEnclosingBox")) continue;
            try {
                Object bb = m.invoke(pl);
                if (bb == null) continue;
                int[] r = toInts(bb);
                if (r != null) return r;
            } catch (Throwable t) {
                lastError = t.toString();
            }
        }
        return null;
    }

    private static int[] fromSubRegions(Object pl) {
        try {
            for (Method m : pl.getClass().getMethods()) {
                if (!m.getName().equals("getSubRegionBoxes") || m.getParameterCount() != 1) continue;
                Class<?> pt = m.getParameterTypes()[0];
                Object arg = null;
                if (pt.isEnum()) {
                    Object[] consts = pt.getEnumConstants();
                    for (Object e : consts) if (e.toString().equals("PLACEMENT_ENABLED")) arg = e;
                    if (arg == null) for (Object e : consts) if (e.toString().equals("ANY")) arg = e;
                    if (arg == null && consts.length > 0) arg = consts[0];
                }
                Object res = m.invoke(pl, arg);
                if (!(res instanceof Map<?, ?> map)) continue;
                int[] acc = null;
                for (Object v : map.values()) {
                    if (v == null) continue;
                    int[] b = toInts(v);
                    if (b == null) continue;
                    if (acc == null) acc = b;
                    else {
                        acc[0] = Math.min(acc[0], b[0]); acc[1] = Math.min(acc[1], b[1]); acc[2] = Math.min(acc[2], b[2]);
                        acc[3] = Math.max(acc[3], b[3]); acc[4] = Math.max(acc[4], b[4]); acc[5] = Math.max(acc[5], b[5]);
                    }
                }
                if (acc != null) return acc;
            }
        } catch (Throwable t) {
            lastError = t.toString();
        }
        return null;
    }

    /** Quét chunk của thế giới schematic quanh người chơi để tìm hộp bao thực tế. */
    private static int[] scanSchematic() {
        World sw = schematicWorld();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (sw == null || mc.player == null) return null;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        final int R = 24;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        try {
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int cx = pcx - R; cx <= pcx + R; cx++) {
                for (int cz = pcz - R; cz <= pcz + R; cz++) {
                    Chunk ch = sw.getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (ch == null) continue;
                    ChunkSection[] secs = ch.getSectionArray();
                    for (int i = 0; i < secs.length; i++) {
                        if (secs[i] == null || secs[i].isEmpty()) continue;
                        int baseY = ch.getBottomY() + i * 16;
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                for (int x = 0; x < 16; x++) {
                                    int wx = cx * 16 + x, wy = baseY + y, wz = cz * 16 + z;
                                    m.set(wx, wy, wz);
                                    BlockState s = ch.getBlockState(m);
                                    if (s.isAir()) continue;
                                    any = true;
                                    if (wx < minX) minX = wx; if (wx > maxX) maxX = wx;
                                    if (wy < minY) minY = wy; if (wy > maxY) maxY = wy;
                                    if (wz < minZ) minZ = wz; if (wz > maxZ) maxZ = wz;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            lastError = t.toString();
            return null;
        }
        if (!any) {
            lastError = "schematic world is empty (bật Render Schematic và đứng gần placement)";
            return null;
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    // ------------------------------------------------------------------

    private static Object call(Object o, String name) {
        if (o == null) return null;
        try {
            Method m = o.getClass().getMethod(name);
            return m.invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Chuyển một "box" bất kỳ (IntBoundingBox, BlockBox, Box của Litematica...) thành {minX..maxZ}. */
    private static int[] toInts(Object bb) {
        try {
            return new int[]{num(bb, "minX"), num(bb, "minY"), num(bb, "minZ"),
                    num(bb, "maxX"), num(bb, "maxY"), num(bb, "maxZ")};
        } catch (Throwable ignored) {
        }
        Object a = call(bb, "getPos1"), b = call(bb, "getPos2");
        if (a instanceof BlockPos p1 && b instanceof BlockPos p2) {
            return new int[]{Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()),
                    Math.max(p1.getX(), p2.getX()), Math.max(p1.getY(), p2.getY()), Math.max(p1.getZ(), p2.getZ())};
        }
        return null;
    }

    private static int num(Object o, String f) throws Exception {
        try {
            Field fl = o.getClass().getField(f);
            return (int) Math.floor(((Number) fl.get(o)).doubleValue());
        } catch (NoSuchFieldException e) {
            // getter kiểu getMinX() hoặc record minX()
            String g = "get" + Character.toUpperCase(f.charAt(0)) + f.substring(1);
            try {
                return (int) Math.floor(((Number) o.getClass().getMethod(g).invoke(o)).doubleValue());
            } catch (NoSuchMethodException e2) {
                return (int) Math.floor(((Number) o.getClass().getMethod(f).invoke(o)).doubleValue());
            }
        }
    }
}
