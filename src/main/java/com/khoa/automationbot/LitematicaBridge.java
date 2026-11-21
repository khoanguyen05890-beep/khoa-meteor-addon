package com.khoa.automationbot;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.World;

import java.lang.reflect.Field;

/**
 * Tích hợp Litematica bằng reflection (không cần phụ thuộc lúc biên dịch).
 * Đọc: WorldSchematic (thế giới schematic) + hộp bao của placement đang được chọn.
 */
public final class LitematicaBridge {
    private LitematicaBridge() {}

    public static String lastError = "";

    public static boolean loaded() { return FabricLoader.getInstance().isModLoaded("litematica"); }

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

    /** {minX, minY, minZ, maxX, maxY, maxZ} của placement đang chọn, hoặc null. */
    public static int[] box() {
        try {
            Class<?> dm = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object mgr = dm.getMethod("getSchematicPlacementManager").invoke(null);
            Object pl = mgr.getClass().getMethod("getSelectedSchematicPlacement").invoke(mgr);
            if (pl == null) { lastError = "no placement selected"; return null; }
            Object bb = pl.getClass().getMethod("getEnclosingBox").invoke(pl);
            if (bb == null) { lastError = "enclosing box = null"; return null; }
            return new int[]{num(bb, "minX"), num(bb, "minY"), num(bb, "minZ"),
                    num(bb, "maxX"), num(bb, "maxY"), num(bb, "maxZ")};
        } catch (Throwable t) {
            lastError = t.toString();
            return null;
        }
    }

    private static int num(Object o, String f) throws Exception {
        try {
            Field fl = o.getClass().getField(f);
            return ((Number) fl.get(o)).intValue();
        } catch (NoSuchFieldException e) {
            String m = "get" + Character.toUpperCase(f.charAt(0)) + f.substring(1);
            return ((Number) o.getClass().getMethod(m).invoke(o)).intValue();
        }
    }
}
