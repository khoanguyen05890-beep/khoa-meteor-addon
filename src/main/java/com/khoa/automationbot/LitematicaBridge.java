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


    // ------------------------------------------------------------------ LAYER đang hiển thị của Litematica

    /** LayerRange của Litematica (DataManager.getRenderLayerRange()), hoặc null. */
    private static Object layerRange() {
        try {
            Class<?> dm = Class.forName("fi.dy.masa.litematica.data.DataManager");
            return dm.getMethod("getRenderLayerRange").invoke(null);
        } catch (Throwable t) {
            lastError = t.toString();
            return null;
        }
    }

    private static String layerModeName(Object lr) {
        Object mode = call(lr, "getLayerMode");
        return mode instanceof Enum<?> e ? e.name() : "";
    }

    public static boolean isEmptyBox(int[] v) {
        return v != null && (v[0] > v[1] || v[2] > v[3] || v[4] > v[5]);
    }

    /**
     * Phần của hộp bao `b` ({minX,minY,minZ,maxX,maxY,maxZ}) mà Litematica đang HIỂN THỊ theo chế độ layer,
     * dạng {x0,x1,y0,y1,z0,z1}. null = chế độ ALL (hiện hết) hoặc không đọc được -> không giới hạn.
     * Hộp rỗng (x0 > x1...) = layer đang hiện không chạm schematic.
     * Dùng isPositionWithinRange() của chính Litematica nên đúng với mọi chế độ (single/above/below/range) và mọi trục.
     */
    public static int[] visibleBox(int[] b) {
        Object lr = layerRange();
        if (lr == null) return null;
        if (layerModeName(lr).equals("ALL")) return null;
        Method m = null;
        boolean pos = false;
        for (Method mm : lr.getClass().getMethods()) {
            if (!mm.getName().equals("isPositionWithinRange")) continue;
            if (mm.getParameterCount() == 3) { m = mm; pos = false; break; }
            if (mm.getParameterCount() == 1 && mm.getParameterTypes()[0] == BlockPos.class) { m = mm; pos = true; }
        }
        if (m == null) return null;
        try {
            int cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
            // Mỗi trục quét với 2 tọa độ còn lại ở TÂM hộp. Nếu tâm nằm ngoài layer (vd. layer đơn y=-60 nhưng
            // tâm schematic ở y khác) thì quét X/Z cho kết quả rỗng dù layer có block -> trước đây bị hiểu nhầm
            // là "layer rỗng" và bot không xây gì. Trục layer thật luôn quét đúng; trục nào rỗng mà trục khác
            // có kết quả thì trục đó không bị giới hạn -> dùng cả đoạn của hộp.
            int[] rx = sweep(lr, m, pos, 0, b, cx, cy, cz);
            int[] ry = sweep(lr, m, pos, 1, b, cx, cy, cz);
            int[] rz = sweep(lr, m, pos, 2, b, cx, cy, cz);
            if (rx == null && ry == null && rz == null) return new int[]{0, -1, 0, -1, 0, -1};
            if (rx == null) rx = new int[]{b[0], b[3]};
            if (ry == null) ry = new int[]{b[1], b[4]};
            if (rz == null) rz = new int[]{b[2], b[5]};
            int[] r = new int[]{rx[0], rx[1], ry[0], ry[1], rz[0], rz[1]};
            if (r[0] == b[0] && r[1] == b[3] && r[2] == b[1] && r[3] == b[4] && r[4] == b[2] && r[5] == b[5]) return null;
            return r;
        } catch (Throwable t) {
            lastError = t.toString();
            return null;
        }
    }

    /** Quét một trục của hộp; trả {min,max} các giá trị nằm trong layer, hoặc null nếu không có giá trị nào. */
    private static int[] sweep(Object lr, Method m, boolean pos, int axis, int[] b, int cx, int cy, int cz) throws Exception {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int v = b[axis]; v <= b[axis + 3]; v++) {
            boolean in = axis == 0 ? within(lr, m, pos, v, cy, cz)
                    : axis == 1 ? within(lr, m, pos, cx, v, cz)
                    : within(lr, m, pos, cx, cy, v);
            if (in) { if (v < lo) lo = v; if (v > hi) hi = v; }
        }
        return lo > hi ? null : new int[]{lo, hi};
    }

    private static boolean within(Object lr, Method m, boolean pos, int x, int y, int z) throws Exception {
        Object r = pos ? m.invoke(lr, new BlockPos(x, y, z)) : m.invoke(lr, x, y, z);
        return Boolean.TRUE.equals(r);
    }

    /** Dời layer của Litematica lên/xuống (như phím tắt Layer +/-). */
    public static boolean moveLayer(int amount) {
        Object lr = layerRange();
        if (lr == null) return false;
        for (Method m : lr.getClass().getMethods()) {
            if (!m.getName().equals("moveLayer") || m.getParameterCount() != 1 || m.getParameterTypes()[0] != int.class) continue;
            try {
                m.invoke(lr, amount);
                return true;
            } catch (Throwable t) {
                lastError = t.toString();
            }
        }
        return false;
    }

    /** Chuyển Litematica sang layer kế tiếp. false nếu đã hết schematic / chế độ không hỗ trợ. */
    public static boolean advanceLayer(int[] box) {
        Object lr = layerRange();
        if (lr == null) return false;
        String mode = layerModeName(lr);
        if (mode.equals("ALL") || mode.equals("ALL_ABOVE") || mode.isEmpty()) return false;
        if (!moveLayer(1)) return false;
        int[] after = visibleBox(box);
        if (after == null || isEmptyBox(after)) {     // đã vượt khỏi schematic -> trả lại
            moveLayer(-1);
            return false;
        }
        return true;
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
