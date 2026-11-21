package com.khoa.automationbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

import static com.khoa.automationbot.Lang.t;

/**
 * Xây theo schematic đang chọn trong Litematica.
 * - Phân tích toàn bộ volume theo từng đợt nhỏ (không treo game).
 * - Chia theo Layer (trục Y), đặt block bằng interactBlock (cơ chế đặt block hợp lệ của client/server).
 * GIỚI HẠN: chỉ so khớp LOẠI block, không tái tạo hướng/thuộc tính (stairs, slab, ...).
 */
public final class LitematicaBuilder extends Task {
    public enum Mode { ALL, LAYER }

    public Mode mode = Mode.ALL;
    public int layer = 0, layers = 0;
    public int minX, minY, minZ, maxX, maxY, maxZ;
    public int[] total = new int[0], built = new int[0];
    public Map<Block, Integer> required = new HashMap<>(), missing = new HashMap<>();
    public boolean analyzing, analyzedOnce, awaitingLayer;
    public String error = "";
    public int placedSince;

    private World schem;
    private long cursor, volume;
    private int[] tTot, tBuilt;
    private Map<Block, Integer> tReq, tMiss;
    private int analysisCooldown;

    private final List<BlockPos> pending = new ArrayList<>();
    private final Set<Long> bad = new HashSet<>();
    private final Walker walker = new Walker();
    private BlockPos goalTarget, lastPlaced;
    private BlockState lastDesired;
    private int scanCooldown, placeCooldown, verifyWait, idle;

    @Override public String name() { return "Litematica Builder"; }

    @Override
    protected void onStart() {
        bad.clear();
        pending.clear();
        awaitingLayer = false;
        walker.clear();
        goalTarget = lastPlaced = null;
        idle = 0;
        analyzedOnce = false;
        if (!loadBox()) { state = State.ERROR; status = error; return; }
        layer = Math.max(0, Math.min(layer, layers - 1));
        beginAnalysis();
    }

    @Override protected void onStop() { walker.clear(); pending.clear(); }

    public boolean loadBox() {
        if (!LitematicaBridge.loaded()) {
            error = t("Chưa cài Litematica", "Litematica is not installed");
            return false;
        }
        int[] b = LitematicaBridge.box();
        if (b == null) {
            error = t("Chưa chọn placement trong Litematica (M > Loaded schematics > Placements)",
                    "No schematic placement selected in Litematica") + " [" + LitematicaBridge.lastError + "]";
            return false;
        }
        schem = LitematicaBridge.schematicWorld();
        if (schem == null) {
            error = t("Không truy cập được thế giới schematic: ", "Cannot access schematic world: ") + LitematicaBridge.lastError;
            return false;
        }
        minX = b[0]; minY = b[1]; minZ = b[2]; maxX = b[3]; maxY = b[4]; maxZ = b[5];
        layers = maxY - minY + 1;
        volume = (long) (maxX - minX + 1) * (maxZ - minZ + 1) * layers;
        if (layers <= 0 || volume <= 0 || volume > 60_000_000L) {
            error = t("Kích thước schematic không hợp lệ/quá lớn", "Invalid or too large schematic size");
            return false;
        }
        error = "";
        return true;
    }

    /** Dùng cho nút "Phân tích lại" trên GUI. */
    public void reanalyze() {
        if (loadBox()) beginAnalysis();
    }

    private void beginAnalysis() {
        tTot = new int[layers];
        tBuilt = new int[layers];
        tReq = new HashMap<>();
        tMiss = new HashMap<>();
        cursor = 0;
        analyzing = true;
    }

    private void analyzeStep(MinecraftClient mc, int budget) {
        ClientWorld w = mc.world;
        int dx = maxX - minX + 1, dz = maxZ - minZ + 1;
        long area = (long) dx * dz;
        long end = Math.min(volume, cursor + budget);
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (; cursor < end; cursor++) {
            int y = (int) (cursor / area);
            long r = cursor % area;
            int z = (int) (r / dx), x = (int) (r % dx);
            m.set(minX + x, minY + y, minZ + z);
            BlockState s = schem.getBlockState(m);
            if (s.isAir() || s.getBlock() instanceof FluidBlock) continue;
            Block b = s.getBlock();
            tTot[y]++;
            tReq.merge(b, 1, Integer::sum);
            if (w.getBlockState(m).getBlock() == b) tBuilt[y]++;
            else tMiss.merge(b, 1, Integer::sum);
        }
        if (cursor >= volume) {
            total = tTot; built = tBuilt; required = tReq; missing = tMiss;
            analyzing = false;
            analyzedOnce = true;
            placedSince = 0;
            analysisCooldown = 400;
        }
    }

    public double analysisProgress() { return volume > 0 ? (double) cursor / volume : 0; }

    public int totalBlocks() { int s = 0; for (int v : total) s += v; return s; }
    public int builtBlocks() { int s = placedSince; for (int v : built) s += v; return Math.min(s, totalBlocks()); }

    @Override
    public double progress() {
        int t = totalBlocks();
        return analyzedOnce && t > 0 ? (double) builtBlocks() / t : -1;
    }

    public void nextLayer() { if (layer < layers - 1) { layer++; resetLayer(); } }
    public void prevLayer() { if (layer > 0) { layer--; resetLayer(); } }

    private void resetLayer() {
        pending.clear();
        scanCooldown = 0;
        idle = 0;
        walker.clear();
        if (awaitingLayer) {
            awaitingLayer = false;
            if (state == State.PAUSED) state = State.RUNNING;
        }
    }

    // ------------------------------------------------------------------

    @Override
    public void tick(MinecraftClient mc) {
        if (schem == null && !loadBox()) { state = State.ERROR; status = error; return; }
        if (analyzing) analyzeStep(mc, analyzedOnce ? 1200 : 6000);
        else if (analyzedOnce && --analysisCooldown <= 0) reanalyze();
        if (!analyzedOnce) {
            status = t("Đang phân tích schematic: ", "Analyzing schematic: ") + (int) (analysisProgress() * 100) + "%";
            return;
        }
        buildStep(mc);
    }

    private void scanLayer(MinecraftClient mc) {
        pending.clear();
        scanCooldown = 100;
        ClientWorld w = mc.world;
        int y = minY + layer;
        BlockPos.Mutable m = new BlockPos.Mutable();
        List<BlockPos> tmp = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                m.set(x, y, z);
                BlockState want = schem.getBlockState(m);
                if (want.isAir() || want.getBlock() instanceof FluidBlock) continue;
                if (bad.contains(m.asLong())) continue;
                if (!Tools.loaded(w, x, z)) continue;
                if (w.getBlockState(m).getBlock() != want.getBlock()) tmp.add(m.toImmutable());
            }
        }
        Vec3d pp = mc.player.getPos();
        tmp.sort(Comparator.comparingDouble(b -> Tools.distSq(b, pp)));
        for (int i = 0; i < tmp.size() && i < 2000; i++) pending.add(tmp.get(i));
    }

    private Direction supportFace(ClientWorld w, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState s = w.getBlockState(n);
            if (!s.isAir() && !s.isReplaceable() && s.getFluidState().isEmpty()) return d;
        }
        return null;
    }

    private boolean ensureHeld(MinecraftClient mc, ClientPlayerEntity p, Item item) {
        int slot = Tools.findItem(p, item);
        if (slot < 0) return false;
        PlayerInventory inv = p.getInventory();
        if (slot < 9) { inv.setSelectedSlot(slot); return true; }
        mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, Tools.slotId(slot),
                inv.getSelectedSlot(), SlotActionType.SWAP, p);
        return false;
    }

    private void buildStep(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Config cfg = Config.get();

        if (lastPlaced != null) {
            if (w.getBlockState(lastPlaced).getBlock() == lastDesired.getBlock()) {
                Stats.built++;
                placedSince++;
                lastPlaced = null;
            } else if (++verifyWait > 20) {
                bad.add(lastPlaced.asLong());
                lastPlaced = null;
            }
        }

        if (pending.isEmpty() || --scanCooldown <= 0) scanLayer(mc);
        if (pending.isEmpty()) {
            Motion.release();
            walker.clear();
            if (mode == Mode.LAYER) {
                awaitingLayer = true;
                state = State.PAUSED;
                status = t("Layer " + (layer + 1) + " đã xong - bấm Next Layer", "Layer " + (layer + 1) + " done - press Next Layer");
                return;
            }
            if (layer < layers - 1) { layer++; scanCooldown = 0; return; }
            state = State.DONE;
            status = t("Hoàn thành công trình!", "Build complete!");
            return;
        }

        // Chọn mục tiêu khả thi gần nhất
        BlockPos tgt = null;
        BlockState want = null;
        Direction face = null;
        String missName = null;
        int checked = 0;
        for (int i = 0; i < pending.size() && checked < 150; i++) {
            BlockPos pos = pending.get(i);
            if (bad.contains(pos.asLong())) { pending.remove(i--); continue; }
            BlockState ws = schem.getBlockState(pos);
            BlockState cs = w.getBlockState(pos);
            if (cs.getBlock() == ws.getBlock()) { pending.remove(i--); continue; }
            if (!cs.isReplaceable()) { bad.add(pos.asLong()); pending.remove(i--); continue; }
            Item it = ws.getBlock().asItem();
            if (it == Items.AIR) { bad.add(pos.asLong()); pending.remove(i--); continue; }
            checked++;
            if (Tools.findItem(p, it) < 0) { missName = it.getName().getString(); continue; }
            Direction d = supportFace(w, pos);
            if (d == null) continue;
            tgt = pos; want = ws; face = d;
            break;
        }

        if (tgt == null) {
            Motion.forward(false);
            if (missName != null) {
                pause();
                status = t("Thiếu vật liệu: ", "Missing material: ") + missName + t(" - bổ sung rồi Resume", " - add it and press Resume");
                return;
            }
            status = t("Chờ điểm tựa để đặt block...", "Waiting for a support block...");
            if (++idle > 300) { bad.addAll(toLongs(pending)); pending.clear(); idle = 0; }
            return;
        }
        idle = 0;

        Vec3d c = Vec3d.ofCenter(tgt);
        BlockPos feet = p.getBlockPos();
        boolean inside = feet.equals(tgt) || feet.up().equals(tgt);
        boolean far = p.getEyePos().squaredDistanceTo(c) > 4.2 * 4.2;
        if (far || inside) {
            Motion.sneak(false);
            final BlockPos g = tgt;
            if (!g.equals(goalTarget) || !walker.active()) {
                Path path = Path.find(w, feet, n -> {
                    Vec3d eye = new Vec3d(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5);
                    return eye.squaredDistanceTo(Vec3d.ofCenter(g)) <= 3.8 * 3.8 && !n.equals(g) && !n.up().equals(g);
                }, g, 4000);
                if (path == null) { bad.add(g.asLong()); pending.remove(g); return; }
                walker.set(path);
                goalTarget = g;
            }
            Walker.Result r = walker.tick(mc);
            status = t("Đang di chuyển tới vị trí xây...", "Moving to build position...");
            if (r == Walker.Result.FAILED || (r == Walker.Result.DONE && (far || inside))) {
                bad.add(g.asLong());
                pending.remove(g);
                walker.clear();
                goalTarget = null;
            }
            return;
        }

        if (walker.active()) { walker.clear(); Motion.forward(false); Motion.jump(false); }
        if (placeCooldown > 0) { placeCooldown--; return; }
        Item item = want.getBlock().asItem();
        if (!ensureHeld(mc, p, item)) return;

        BlockPos sup = tgt.offset(face);
        Direction click = face.getOpposite();
        Vec3d hitVec = Vec3d.ofCenter(sup).add(click.getOffsetX() * 0.5, click.getOffsetY() * 0.5, click.getOffsetZ() * 0.5);
        status = t("Đang đặt ", "Placing ") + want.getBlock().getName().getString() + " (layer " + (layer + 1) + "/" + layers + ")";
        if (!Motion.lookAt(p, hitVec, cfg.turnSpeed())) return;
        if (!p.isSneaking()) { Motion.sneak(true); return; }   // tránh mở rương/bàn craft khi đặt
        ActionResult res = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hitVec, click, sup, false));
        if (res.isAccepted()) p.swingHand(Hand.MAIN_HAND);
        lastPlaced = tgt;
        lastDesired = want;
        verifyWait = 0;
        placeCooldown = cfg.delay();
        pending.remove(tgt);
    }

    private static List<Long> toLongs(List<BlockPos> l) {
        List<Long> r = new ArrayList<>();
        for (BlockPos b : l) r.add(b.asLong());
        return r;
    }
}
