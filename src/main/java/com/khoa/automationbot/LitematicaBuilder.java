package com.khoa.automationbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

import static com.khoa.automationbot.Lang.t;

/**
 * Xây theo schematic đang chọn trong Litematica.
 * - Phân tích toàn bộ volume theo từng đợt nhỏ (không treo game).
 * - Xây ĐÚNG LAYER mà Litematica đang hiển thị (đọc LayerRange: single / above / below / range, mọi trục).
 *   Chế độ hiển thị ALL thì dùng auto-layer theo độ cao đang đứng như cũ.
 * - Đặt block bằng interactBlock, TỰ CHỈNH HƯỚNG (stairs, slab, log, furnace...) qua Orient.
 * - Layer cao quá thì ném ENDER PEARL (PearlHop) hoặc dựng cột để lên.
 * Chỉ so khớp LOẠI block khi quyết định "đã xây xong"; block đã đặt sai hướng sẽ không bị phá.
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

    // vật liệu: quét layer -> thiếu thì lấy trong rương -> /ah
    private record Req(Item item, int need) {}
    private final Deque<Req> queue = new ArrayDeque<>();
    private final Map<Item, Long> unobtainable = new HashMap<>();
    private Provision supply;
    private Item supplyItem;
    private boolean layerChecked;
    private long now;

    // dựng cột (pillar) để lên layer cao
    private int pillarMode, pillarTicks;   // 0 = tắt, 1 = đi tới chân cột, 2 = đang nhảy-đặt block

    // ném ender pearl để lên layer cao
    private PearlHop pearl;
    private long pearlBlockedUntil;

    // layer Litematica đang hiển thị
    private int[] vis;                     // {x0,x1,y0,y1,z0,z1}; null = chế độ ALL
    private boolean following;
    private int visTick, emptyWait;

    // block tạm (cầu/cột) để tới chỗ khó + đập sau khi xong
    private final Scaffolder scaf = new Scaffolder();
    private boolean bridging;
    private BlockPos bridgeGoal;
    private final Map<Long, Integer> bridgeTries = new HashMap<>();

    // đổ nước / lava / waterlogged bằng xô
    private enum Act { PLACE, FLUID, WATERLOG }
    private final Map<Long, Integer> tries = new HashMap<>();
    private BlockPos refillSrc;
    private long refillBlockedUntil;

    // điểm ngắm thật (tia nhìn phải trúng đúng mặt block tựa) - để đặt/đổ giống người chơi thật
    private static final class Aim {
        final BlockPos sup;        // block bị bấm vào
        final Direction click;     // mặt bị bấm
        final Vec3d point;         // điểm cần ngắm
        Aim(BlockPos sup, Direction click, Vec3d point) { this.sup = sup; this.click = click; this.point = point; }
    }
    private Aim aim;
    private BlockPos aimFor;
    private int aimWait, aimMiss;
    private final Random rng = new Random();

    // đặt đúng hướng
    private final Map<Long, Orient.Plan> plans = new HashMap<>();
    private float lastYaw = Float.NaN, lastPitch = Float.NaN;

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
        queue.clear();
        unobtainable.clear();
        supply = null;
        layerChecked = false;
        pillarMode = 0;
        pearl = null;
        pearlBlockedUntil = 0;
        plans.clear();
        aim = null;
        aimFor = null;
        scaf.reset();
        bridging = false;
        bridgeGoal = null;
        bridgeTries.clear();
        tries.clear();
        refillSrc = null;
        refillBlockedUntil = 0;
        emptyWait = 0;
        visTick = 0;
        vis = null;
        following = false;
        now = 0;
        if (!loadBox()) { state = State.ERROR; status = error; return; }
        MinecraftClient mc = MinecraftClient.getInstance();
        Config cfg = Config.get();
        refreshVis();
        if (following) {
            layer = vis[2] - minY;                                  // layer thấp nhất đang hiện
        } else if (cfg.autoLayer && mc.player != null) {
            layer = mc.player.getBlockY() + cfg.layerOffset - minY;   // tự chọn theo độ cao đang đứng
        }
        layer = Math.max(0, Math.min(layer, layers - 1));
        beginAnalysis();
    }

    @Override
    protected void onStop() {
        scaf.reset();
        bridging = false;
        walker.clear();
        pending.clear();
        pearl = null;
        Config.get().protectedItems = new HashSet<>();
    }

    /** Đọc lại layer Litematica đang hiển thị; đổi layer thì quét lại ngay. */
    private void refreshVis() {
        int[] v = Config.get().followLayer ? LitematicaBridge.visibleBox(new int[]{minX, minY, minZ, maxX, maxY, maxZ}) : null;
        if (!Arrays.equals(v, vis)) {
            vis = v;
            pending.clear();
            plans.clear();
            scanCooldown = 0;
            emptyWait = 0;
            layerChecked = false;
            queue.clear();
            idle = 0;
        }
        following = vis != null;
    }

    public boolean loadBox() {
        if (!LitematicaBridge.loaded()) {
            error = t("Chưa cài Litematica", "Litematica is not installed");
            return false;
        }
        LitematicaBridge.ensureRendering();
        int[] b = LitematicaBridge.box();
        if (b == null) {
            error = t("Không đọc được schematic của Litematica (chọn placement + bật Render Schematic + đứng gần)",
                    "Cannot read the Litematica schematic (select a placement, enable Render Schematic, stand near it)")
                    + " [" + LitematicaBridge.lastError + "]";
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
        bad.clear();                 // thử lại các ô từng thất bại (block bị mất/phá đột ngột sẽ được đặt lại)
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
            Set<String> ids = new HashSet<>();          // vật liệu schematic cần: không bao giờ bị vứt
            for (Block rb : required.keySet()) {
                Item ri = rb.asItem();
                if (ri != Items.AIR) ids.add(Tools.itemId(ri));
            }
            ids.add("minecraft:water_bucket");
            ids.add("minecraft:lava_bucket");
            ids.add("minecraft:bucket");
            Config.get().protectedItems = ids;
            analysisCooldown = totalBlocks() == 0 ? 40 : 400;
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

    public void nextLayer() {
        if (following) { LitematicaBridge.moveLayer(1); return; }
        if (layer < layers - 1) { layer++; resetLayer(); }
    }

    public void prevLayer() {
        if (following) { LitematicaBridge.moveLayer(-1); return; }
        if (layer > 0) { layer--; resetLayer(); }
    }

    private void resetLayer() {
        pending.clear();
        layerChecked = false;
        queue.clear();
        pillarMode = 0;
        pearl = null;
        plans.clear();
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
        if (++visTick >= 10) { visTick = 0; refreshVis(); }
        if (analyzing) analyzeStep(mc, analyzedOnce ? 1200 : 6000);
        else if (analyzedOnce && --analysisCooldown <= 0) reanalyze();
        if (!analyzedOnce) {
            status = t("Đang phân tích schematic: ", "Analyzing schematic: ") + (int) (analysisProgress() * 100) + "%";
            return;
        }
        if (totalBlocks() == 0) {
            Motion.release();
            status = t("Schematic rỗng: bật Render Schematic, đứng gần placement (đang thử lại...)",
                    "Schematic is empty: enable Render Schematic and stand near the placement (retrying...)");
            return;
        }
        now++;
        buildStep(mc);
    }

    private void scanLayer(MinecraftClient mc) {
        pending.clear();
        plans.clear();
        scanCooldown = 100;
        ClientWorld w = mc.world;
        // Vùng cần quét: đang follow Litematica -> đúng phần đang hiển thị, ngược lại -> layer hiện tại theo Y
        int xa = minX, xb = maxX, za = minZ, zb = maxZ, ya, yb;
        if (following) { xa = vis[0]; xb = vis[1]; ya = vis[2]; yb = vis[3]; za = vis[4]; zb = vis[5]; }
        else { ya = yb = minY + layer; }
        BlockPos.Mutable m = new BlockPos.Mutable();
        List<BlockPos> tmp = new ArrayList<>();
        Set<Long> inTmp = new HashSet<>();
        for (int y = ya; y <= yb && tmp.isEmpty(); y++) {        // từ thấp lên cao: layer thấp nhất còn thiếu
            for (int z = za; z <= zb; z++) {
                for (int x = xa; x <= xb; x++) {
                    m.set(x, y, z);
                    BlockState want = schem.getBlockState(m);
                    if (want.isAir()) continue;
                    boolean fluidWant = want.getBlock() instanceof FluidBlock;
                    if (fluidWant && (!Config.get().placeFluids || !want.getFluidState().isStill())) continue;
                    if (Orient.isSecondPart(want)) continue;       // nửa trên cửa/giường tự có khi đặt nửa dưới
                    if (bad.contains(m.asLong())) continue;
                    if (!Tools.loaded(w, x, z)) continue;
                    BlockState cs = w.getBlockState(m);
                    boolean missing;
                    if (fluidWant) missing = !fluidOk(w, m, want);
                    else missing = cs.getBlock() != want.getBlock() || (Config.get().placeFluids && wantsWater(want) && !hasWater(cs));
                    if (!missing) continue;
                    if (fluidWant && Config.get().fluidWalls) {
                        // KHÔNG đổ lava/nước khi block chặn xung quanh chưa đặt: đặt hết block chặn trước đã
                        List<BlockPos> walls = new ArrayList<>();
                        if (!walled(w, m, walls)) {
                            for (BlockPos wp : walls) {
                                if (inTmp.add(wp.asLong())) tmp.add(wp);
                            }
                            continue;
                        }
                    }
                    if (inTmp.add(m.asLong())) tmp.add(m.toImmutable());
                }
            }
            if (following && !tmp.isEmpty()) layer = y - minY;
        }
        Vec3d pp = mc.player.getEntityPos();
        tmp.sort(Comparator.comparingDouble(b -> Tools.distSq(b, pp)));
        for (int i = 0; i < tmp.size() && i < 2000; i++) pending.add(tmp.get(i));
        if (!layerChecked && !pending.isEmpty()) { layerChecked = true; queueSupplies(mc); }
    }

    /**
     * Mọi ô kề (6 hướng) mà schematic cần block đặc đều phải có block đúng ngoài thế giới thì mới được đổ chất lỏng.
     * Ô còn thiếu được đưa vào `missingOut` để đặt trước.
     */
    private boolean walled(ClientWorld w, BlockPos pos, List<BlockPos> missingOut) {
        boolean ok = true;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState ws = schem.getBlockState(n);
            if (ws.isAir() || ws.getBlock() instanceof FluidBlock || Orient.isSecondPart(ws)) continue;
            if (!Tools.loaded(w, n.getX(), n.getZ())) { ok = false; continue; }
            if (w.getBlockState(n).getBlock() != ws.getBlock()) {
                ok = false;
                if (!bad.contains(n.asLong())) missingOut.add(n);
            }
        }
        return ok;
    }

    private static boolean hasWater(BlockState s) {
        FluidState f = s.getFluidState();
        return f.isStill() && f.isIn(FluidTags.WATER);
    }

    /** Schematic cần block này có waterlogged = true (lá, slab, stairs, trapdoor...). */
    private static boolean wantsWater(BlockState want) {
        return !(want.getBlock() instanceof FluidBlock) && want.contains(Properties.WATERLOGGED) && want.get(Properties.WATERLOGGED);
    }

    /** Ô nước/lava nguồn trong schematic đã có đúng loại chất lỏng (nguồn) ngoài thế giới chưa. */
    private static boolean fluidOk(ClientWorld w, BlockPos pos, BlockState want) {
        FluidState cf = w.getFluidState(pos);
        if (!cf.isStill()) return false;
        return want.getFluidState().isIn(FluidTags.LAVA) ? cf.isIn(FluidTags.LAVA) : cf.isIn(FluidTags.WATER);
    }

    private boolean inBox(BlockPos b) {
        return b.getX() >= minX && b.getX() <= maxX && b.getY() >= minY && b.getY() <= maxY && b.getZ() >= minZ && b.getZ() <= maxZ;
    }

    private boolean blocked(Item it) {
        Long t0 = unobtainable.get(it);
        return t0 != null && now - t0 < 2400;      // thử lại sau ~2 phút
    }

    private int needOf(Item it) {
        int n = 0;
        for (BlockPos pos : pending) if (schem.getBlockState(pos).getBlock().asItem() == it) n++;
        return n;
    }

    /** "Quét riêng" vật liệu của layer: thiếu món nào thì xếp hàng đi lấy. */
    private void queueSupplies(MinecraftClient mc) {
        Config cfg = Config.get();
        if (!cfg.autoSupply || mc.player.isCreative()) return;
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (BlockPos pos : pending) {
            Item it = schem.getBlockState(pos).getBlock().asItem();
            if (it != Items.AIR) need.merge(it, 1, Integer::sum);
        }
        for (var e : need.entrySet()) {
            if (Tools.count(mc.player, e.getKey()) < e.getValue() && !blocked(e.getKey())) {
                queue.add(new Req(e.getKey(), e.getValue()));
            }
        }
    }

    private boolean startNextSupply(MinecraftClient mc) {
        Config cfg = Config.get();
        while (!queue.isEmpty()) {
            Req r = queue.poll();
            if (blocked(r.item())) continue;
            int have = Tools.count(mc.player, r.item());
            if (have >= r.need()) continue;
            supplyItem = r.item();
            supply = Provision.item(r.item(), Math.max(1, Math.min(cfg.supplyMax, r.need() - have)));
            Motion.release();
            walker.clear();
            pearl = null;
            goalTarget = null;
            return true;
        }
        return false;
    }

    private Item scaffoldItem(ClientPlayerEntity p) {
        for (String id : Config.get().scaffoldItems) {
            Identifier i = Identifier.tryParse(id);
            if (i == null || !Registries.ITEM.containsId(i)) continue;
            Item it = Registries.ITEM.get(i);
            if (Tools.findItem(p, it) >= 0) return it;
        }
        return null;
    }

    /** Cột đứng hợp lệ: schematic không cần block ở cột đó và phía trên còn trống để nhảy lên. */
    private boolean pillarOk(ClientWorld w, BlockPos n, BlockPos g) {
        double dx = n.getX() - g.getX(), dz = n.getZ() - g.getZ();
        if (dx * dx + dz * dz > 9 || (n.getX() == g.getX() && n.getZ() == g.getZ())) return false;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = n.getY(); y <= g.getY(); y++) {
            m.set(n.getX(), y, n.getZ());
            if (!schem.getBlockState(m).isAir()) return false;
            if (y >= n.getY() + 2 && !Path.passable(w, m)) return false;
        }
        return true;
    }

    private boolean startPillar(MinecraftClient mc, BlockPos g) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (scaffoldItem(p) == null) {
            pause();
            status = t("Cần block để dựng cột lên layer cao (đặt cobblestone/dirt vào túi) rồi Resume",
                    "Need scaffold blocks to climb to the next layer (put cobblestone/dirt in inventory) then Resume");
            return true;
        }
        Path pp = Path.find(w, p.getBlockPos(), n -> pillarOk(w, n, g), g, 3000);
        if (pp == null || !pp.complete) return false;
        walker.set(pp);
        pillarMode = 1;
        pillarTicks = 0;
        goalTarget = g;
        return true;
    }

    /** false = thất bại. */
    private boolean pillarStep(MinecraftClient mc, BlockPos g) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (++pillarTicks > 700) return false;
        if (pillarMode == 1) {
            status = t("Đi tới chỗ dựng cột...", "Moving to the pillar spot...");
            Walker.Result r = walker.tick(mc);
            if (r == Walker.Result.FAILED) return false;
            if (r == Walker.Result.DONE) { walker.clear(); Motion.forward(false); pillarMode = 2; }
            return true;
        }
        Item sc = scaffoldItem(p);
        if (sc == null) {
            Motion.release();
            pause();
            status = t("Hết block dựng cột - bổ sung rồi Resume", "Out of scaffold blocks - add more and Resume");
            return true;
        }
        if (p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(g)) <= 3.6 * 3.6) {
            pillarMode = 0;
            Motion.jump(false);
            walker.clear();
            goalTarget = null;
            return true;
        }
        status = t("Đang dựng cột lên layer ", "Pillaring up to layer ") + (layer + 1);
        if (!ensureHeld(mc, p, sc)) { Motion.jump(false); return true; }
        Motion.turn(p, p.getYaw(), 90f, 90f);
        Motion.forward(false);
        Motion.sneak(false);
        BlockPos under = BlockPos.ofFloored(p.getX(), p.getY(), p.getZ()).down();
        BlockPos sup = under.down();
        if (w.getBlockState(under).isReplaceable() && Path.floorOk(w, sup) && p.getPitch() > 80f) {
            Vec3d hv = Vec3d.ofCenter(sup).add(0, 0.5, 0);
            ActionResult res = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hv, Direction.UP, sup, false));
            if (res.isAccepted()) { p.swingHand(Hand.MAIN_HAND); scaf.record(under); }
        }
        Motion.jump(true);
        return true;
    }

    /** Chỗ cần đặt mà đi bộ không tới: đặt block liên tục (cầu/cột) tới đó. */
    private boolean startBridge(MinecraftClient mc, BlockPos g) {
        if (!Config.get().autoBridge) return false;
        if (scaffoldItem(mc.player) == null) {
            pause();
            status = t("Cần block để dựng cầu/cột tới chỗ đặt (đặt cobblestone/dirt vào túi) rồi Resume",
                    "Need scaffold blocks to bridge to the build spot (put cobblestone/dirt in inventory) then Resume");
            return true;
        }
        if (bridgeTries.merge(g.asLong(), 1, Integer::sum) > 2) return false;
        bridging = true;
        bridgeGoal = g;
        scaf.startBridge();
        walker.clear();
        goalTarget = g;
        return true;
    }

    private BlockPos findSource(ClientWorld w, ClientPlayerEntity p, boolean lava) {
        BlockPos c = p.getBlockPos();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) for (int dy = -6; dy <= 6; dy++) {
            m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
            FluidState f = w.getFluidState(m);
            if (!f.isStill() || !f.isIn(lava ? FluidTags.LAVA : FluidTags.WATER)) continue;
            if (inBox(m)) continue;                       // không múc phần nước/lava của chính công trình
            double d = c.getSquaredDistance(m);
            if (d < bd) { bd = d; best = m.toImmutable(); }
        }
        return best;
    }

    /** Có xô rỗng, hết xô đầy: tìm nguồn nước/lava gần đó và múc. true = đang làm việc này. */
    private boolean refill(MinecraftClient mc, boolean lava) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Config cfg = Config.get();
        if (now < refillBlockedUntil) return false;
        if (refillSrc == null || !w.getFluidState(refillSrc).isStill()) refillSrc = findSource(w, p, lava);
        if (refillSrc == null) { refillBlockedUntil = now + 300; return false; }
        final BlockPos src = refillSrc;
        final Vec3d c = Vec3d.ofCenter(src);
        status = t("Múc ", "Filling bucket: ") + (lava ? "lava" : t("nước", "water"));
        if (p.getEyePos().squaredDistanceTo(c) > 4.0 * 4.0) {
            if (!walker.active() || !src.equals(goalTarget)) {
                Path path = Path.find(w, p.getBlockPos(),
                        n -> new Vec3d(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5).squaredDistanceTo(c) <= 3.6 * 3.6,
                        src, 3000);
                if (path == null || !path.complete) { refillSrc = null; refillBlockedUntil = now + 300; walker.clear(); return false; }
                walker.set(path);
                goalTarget = src;
            }
            Walker.Result r = walker.tick(mc);
            if (r != Walker.Result.RUNNING) { walker.clear(); goalTarget = null; if (r == Walker.Result.FAILED) { refillSrc = null; refillBlockedUntil = now + 300; } }
            return true;
        }
        walker.clear();
        goalTarget = null;
        Motion.forward(false);
        Motion.jump(false);
        if (!ensureHeld(mc, p, Items.BUCKET)) return true;
        if (!Motion.lookAt(p, c, cfg.turnSpeed())) return true;
        if (placeCooldown > 0) { placeCooldown--; return true; }
        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        p.swingHand(Hand.MAIN_HAND);
        placeCooldown = cfg.placeWait() + 2;
        return true;
    }

    /** Thử ném ender pearl lên chỗ đứng gần block cần đặt. false = không dùng được -> dựng cột. */
    private boolean startPearl(MinecraftClient mc, BlockPos g) {
        Config cfg = Config.get();
        ClientPlayerEntity p = mc.player;
        if (!cfg.usePearl || p.isCreative() || now < pearlBlockedUntil) return false;
        if (Tools.count(p, Items.ENDER_PEARL) <= 0 || p.getHealth() <= 8f) return false;
        PearlHop h = PearlHop.plan(mc, g, schem);
        if (h == null) { pearlBlockedUntil = now + 100; return false; }
        pearl = h;
        goalTarget = g;
        walker.clear();
        Motion.release();
        return true;
    }

    /** Tia từ mắt tới điểm: trả về block trúng đầu tiên (có tính vật cản - không xuyên tường). */
    private BlockHitResult castTo(MinecraftClient mc, Vec3d point) {
        return mc.world.raycast(new RaycastContext(mc.player.getEyePos(), point,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
    }

    /**
     * Tìm điểm ngắm hợp lệ: nằm trong tầm với `place-reach`, tia nhìn trúng ĐÚNG mặt block tựa (không bị vật cản).
     * Chọn điểm ít phải xoay đầu nhất; điểm bấm lệch ngẫu nhiên nhẹ trên mặt block như người thật.
     * WATERLOG: bấm thẳng vào block cần ngập nước.
     */
    private Aim findAim(MinecraftClient mc, BlockPos tgt, Act act) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Vec3d eye = p.getEyePos();
        double reach = Config.get().placeReach + 0.3;
        double jx = (rng.nextDouble() - 0.5) * 0.4, jy = (rng.nextDouble() - 0.5) * 0.4;
        double[][] offs = {{jx, jy}, {0, 0}, {0.25, 0}, {-0.25, 0}, {0, 0.25}, {0, -0.25}, {0.3, 0.3}, {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}};
        Aim best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos sup = act == Act.WATERLOG ? tgt : tgt.offset(d);
            if (act != Act.WATERLOG) {
                BlockState ss = w.getBlockState(sup);
                if (ss.isAir() || ss.isReplaceable() || !ss.getFluidState().isEmpty()) continue;
            }
            Direction click = act == Act.WATERLOG ? d : d.getOpposite();
            for (double[] o : offs) {
                Vec3d base = Vec3d.ofCenter(sup).add(click.getOffsetX() * 0.5, click.getOffsetY() * 0.5, click.getOffsetZ() * 0.5);
                Vec3d pt;
                if (click.getAxis() == Direction.Axis.Y) pt = base.add(o[0], 0, o[1]);
                else if (click.getAxis() == Direction.Axis.X) pt = base.add(0, o[1], o[0]);
                else pt = base.add(o[0], o[1], 0);
                // lùi vào mặt block 0.02 để tia chắc chắn "chạm" mặt đó
                pt = pt.subtract(click.getOffsetX() * 0.02, click.getOffsetY() * 0.02, click.getOffsetZ() * 0.02);
                double dist = eye.distanceTo(pt);
                if (dist > reach) continue;
                BlockHitResult hr = castTo(mc, pt.add(click.getOffsetX() * -0.2, click.getOffsetY() * -0.2, click.getOffsetZ() * -0.2));
                if (hr.getType() != HitResult.Type.BLOCK) continue;
                if (!hr.getBlockPos().equals(sup) || hr.getSide() != click) continue;
                double dx = pt.x - eye.x, dy = pt.y - eye.y, dz = pt.z - eye.z;
                float yaw = (float) (Math.atan2(dz, dx) * 57.29577951308232) - 90f;
                float pitch = (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 57.29577951308232);
                double turn = Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(yaw - p.getYaw()))
                        + Math.abs(pitch - p.getPitch());
                double score = turn + dist * 4;
                if (score < bestScore) { bestScore = score; best = new Aim(sup, click, pt); }
            }
        }
        return best;
    }

    /** Tia nhìn THẬT hiện tại (theo yaw/pitch đang có) - đúng thứ crosshair đang chỉ vào. */
    private BlockHitResult crosshair(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        Vec3d e = p.getEyePos();
        Vec3d end = e.add(p.getRotationVec(1.0f).multiply(Config.get().placeReach + 1.0));
        return mc.world.raycast(new RaycastContext(e, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, p));
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

    /** Layer đang hiện trong Litematica đã xong. */
    private void layerFinished() {
        emptyWait = 20;
        if (analyzedOnce && totalBlocks() > 0 && builtBlocks() >= totalBlocks()) {
            state = State.DONE;
            status = t("Hoàn thành công trình!", "Build complete!");
            return;
        }
        if (Config.get().advanceLayer && LitematicaBridge.advanceLayer(new int[]{minX, minY, minZ, maxX, maxY, maxZ})) {
            refreshVis();                         // đọc ngay layer mới, tránh nhảy cóc 2 layer
            status = t("Xong layer - chuyển Litematica sang layer kế", "Layer done - advancing the Litematica layer");
            emptyWait = 2;
            layerChecked = false;
            return;
        }
        status = t("Layer đang hiện đã xong - đổi layer trong Litematica để xây tiếp",
                "The shown layer is done - change the layer in Litematica to continue");
    }

    private void buildStep(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Config cfg = Config.get();

        if (supply != null) {
            status = supply.status;
            if (!supply.tick(mc)) {
                if (!supply.success) unobtainable.put(supplyItem, now);
                supply = null;
                scanCooldown = 0;
            }
            return;
        }

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

        if (pending.isEmpty()) {
            if (emptyWait > 0) { emptyWait--; return; }
            scanLayer(mc);
        } else if (--scanCooldown <= 0) {
            scanLayer(mc);
        }
        if (pending.isEmpty()) {
            if (scaf.has() && !bridging && cfg.cleanScaffold) {            // xong phần này: đập block tạm trước
                if (scaf.cleanup(mc) == Scaffolder.R.RUNNING) { status = scaf.status; return; }
            }
            Motion.release();
            walker.clear();
            if (following) { layerFinished(); return; }
            if (mode == Mode.LAYER) {
                awaitingLayer = true;
                state = State.PAUSED;
                status = t("Layer " + (layer + 1) + " đã xong - bấm Next Layer", "Layer " + (layer + 1) + " done - press Next Layer");
                return;
            }
            if (layer < layers - 1) { layer++; layerChecked = false; scanCooldown = 0; return; }
            state = State.DONE;
            status = t("Hoàn thành công trình!", "Build complete!");
            return;
        }

        if (startNextSupply(mc)) return;        // vật liệu của layer còn thiếu -> đi lấy trước

        // Chọn mục tiêu khả thi gần nhất
        BlockPos tgt = null;
        BlockState want = null;
        Direction face = null;
        Item missItem = null;
        Act act = Act.PLACE;
        Item actItem = null;
        BlockPos blockedScaf = null;
        int checked = 0;
        for (int i = 0; i < pending.size() && checked < 150; i++) {
            BlockPos pos = pending.get(i);
            if (bad.contains(pos.asLong())) { pending.remove(i--); continue; }
            BlockState ws = schem.getBlockState(pos);
            BlockState cs = w.getBlockState(pos);
            if (Orient.isSecondPart(ws)) { pending.remove(i--); continue; }
            Act a;
            Item it;
            if (ws.getBlock() instanceof FluidBlock) {                       // ô nước / lava nguồn: đổ bằng xô
                if (!ws.getFluidState().isStill() || fluidOk(w, pos, ws)) { tries.remove(pos.asLong()); pending.remove(i--); continue; }
                if (!cs.isReplaceable()) {
                    if (scaf.owns(pos)) { blockedScaf = pos; continue; }
                    bad.add(pos.asLong()); pending.remove(i--); continue;
                }
                a = Act.FLUID;
                it = ws.getFluidState().isIn(FluidTags.LAVA) ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
            } else if (cs.getBlock() == ws.getBlock()) {                    // đã có block: chỉ còn thiếu nước (waterlogged)?
                if (!cfg.placeFluids || !wantsWater(ws) || hasWater(cs)) { tries.remove(pos.asLong()); pending.remove(i--); continue; }
                a = Act.WATERLOG;
                it = Items.WATER_BUCKET;
            } else {
                if (!cs.isReplaceable()) {
                    if (scaf.owns(pos)) { blockedScaf = pos; continue; }     // block tạm của mình đang chiếm chỗ -> đập sau
                    bad.add(pos.asLong()); pending.remove(i--); continue;
                }
                it = ws.getBlock().asItem();
                if (it == Items.AIR) { bad.add(pos.asLong()); pending.remove(i--); continue; }
                a = Act.PLACE;
            }
            checked++;
            if (Tools.findItem(p, it) < 0) { missItem = it; continue; }
            Direction d = a == Act.WATERLOG ? Direction.UP : supportFace(w, pos);
            if (d == null) continue;
            tgt = pos; want = ws; face = d; act = a; actItem = it;
            break;
        }

        // block tạm của mình đang chiếm chỗ cần đặt: đập hết block tạm
        if (tgt == null && blockedScaf != null && scaf.has() && !bridging && cfg.cleanScaffold) {
            if (scaf.cleanup(mc) == Scaffolder.R.RUNNING) { status = scaf.status; return; }
        }
        // hết xô đầy mà có xô rỗng: múc lại ở nguồn nước/lava gần đó
        if (tgt == null && missItem != null && (missItem == Items.WATER_BUCKET || missItem == Items.LAVA_BUCKET)
                && Tools.findItem(p, Items.BUCKET) >= 0 && refill(mc, missItem == Items.LAVA_BUCKET)) return;

        if (tgt == null && startNextSupply(mc)) return;
        if (tgt == null) {
            Motion.forward(false);
            if (missItem != null) {
                if (cfg.autoSupply && !p.isCreative() && !blocked(missItem)) {
                    queue.add(new Req(missItem, Math.max(1, needOf(missItem))));
                    status = t("Thiếu vật liệu - đi lấy: ", "Missing material - fetching: ") + missItem.getName().getString();
                    return;
                }
                pause();
                status = t("Thiếu vật liệu: ", "Missing material: ") + missItem.getName().getString()
                        + t(" - bổ sung rồi Resume", " - add it and press Resume");
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
        double reach = cfg.placeReach;
        boolean far = p.getEyePos().squaredDistanceTo(c) > (reach + 0.5) * (reach + 0.5);
        if (far || inside) {
            Motion.sneak(false);
            final BlockPos g = tgt;
            // sắp đi chỗ khác: đập block tạm đã đặt trước (không đập khi đang dựng cầu/cột/ném ngọc)
            if (scaf.has() && !bridging && pillarMode == 0 && pearl == null && cfg.cleanScaffold) {
                if (scaf.cleanup(mc) == Scaffolder.R.RUNNING) { status = scaf.status; return; }
            }
            if (bridging) {
                Item sc = scaffoldItem(p);
                if (sc == null) {
                    Motion.release();
                    bridging = false;
                    pause();
                    status = t("Hết block dựng cầu - bổ sung rồi Resume", "Out of scaffold blocks - add more and Resume");
                    return;
                }
                Scaffolder.R br = scaf.bridge(mc, bridgeGoal, sc);
                status = scaf.status;
                if (br != Scaffolder.R.RUNNING) {
                    bridging = false;
                    Motion.forward(false);
                    Motion.jump(false);
                    Motion.sneak(false);
                    walker.clear();
                    goalTarget = null;
                    if (br == Scaffolder.R.FAILED) { bad.add(bridgeGoal.asLong()); pending.remove(bridgeGoal); }
                }
                return;
            }
            if (pearl != null) {                                   // đang ném ngọc lên layer
                PearlHop.R pr = pearl.tick(mc);
                status = pearl.status;
                if (pr != PearlHop.R.RUNNING) {
                    if (pr == PearlHop.R.FAILED) pearlBlockedUntil = now + 600;
                    pearl = null;
                    goalTarget = null;
                    walker.clear();
                }
                return;
            }
            if (pillarMode != 0) {
                if (!pillarStep(mc, g)) {
                    pillarMode = 0;
                    Motion.jump(false);
                    walker.clear();
                    goalTarget = null;
                    bad.add(g.asLong());
                    pending.remove(g);
                }
                return;
            }
            if (!g.equals(goalTarget) || !walker.active()) {
                Path path = Path.find(w, feet, n -> {
                    Vec3d eye = new Vec3d(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5);
                    return eye.squaredDistanceTo(Vec3d.ofCenter(g)) <= reach * reach && !n.equals(g) && !n.up().equals(g);
                }, g, 4000);
                boolean needUp = g.getY() - feet.getY() >= 2;
                if (path == null || (!path.complete && needUp)) {
                    if (needUp && startPearl(mc, g)) return;                      // ưu tiên ném ender pearl
                    if (cfg.autoPillar && needUp && startPillar(mc, g)) return;   // không được thì dựng cột lên cao
                    if (startBridge(mc, g)) return;                               // không đi bộ tới được: đặt block liên tục tới đó
                    if (path == null) { bad.add(g.asLong()); pending.remove(g); return; }
                }
                walker.set(path);
                goalTarget = g;
            }
            Walker.Result r = walker.tick(mc);
            status = t("Đang di chuyển tới vị trí xây...", "Moving to build position...");
            if (r == Walker.Result.FAILED || (r == Walker.Result.DONE && (far || inside))) {
                walker.clear();
                goalTarget = null;
                if (startBridge(mc, g)) return;                  // đi bộ không tới đủ gần: thử đặt block liên tục tới đó
                bad.add(g.asLong());
                pending.remove(g);
            }
            return;
        }

        if (walker.active()) { walker.clear(); Motion.forward(false); Motion.jump(false); }
        if (bridging) { bridging = false; Motion.forward(false); Motion.jump(false); Motion.sneak(false); }
        if (placeCooldown > 0) { placeCooldown--; return; }
        Item item = actItem;
        if (!ensureHeld(mc, p, item)) return;

        // đổ nước / lava / waterlogged bằng xô: chỉ đổ khi tia nhìn THẬT trúng đúng chỗ cần đổ
        if (act != Act.PLACE) {
            if (aim == null || !tgt.equals(aimFor)) { aim = findAim(mc, tgt, act); aimFor = tgt; aimWait = 0; aimMiss = 0; }
            if (aim == null) {
                status = t("Không có góc ngắm rõ để đổ - đứng gần/đổi chỗ...", "No clear line to pour - repositioning...");
                if (++aimWait > 40) { bad.add(tgt.asLong()); pending.remove(tgt); aimFor = null; }
                return;
            }
            status = (act == Act.WATERLOG ? t("Đổ nước vào ", "Waterlogging ") : t("Đổ chất lỏng: ", "Pouring: "))
                    + want.getBlock().getName().getString();
            boolean ok2 = Motion.lookAt(p, aim.point, cfg.turnSpeed());
            boolean same2 = Math.abs(p.getYaw() - lastYaw) < 0.05f && Math.abs(p.getPitch() - lastPitch) < 0.05f;
            lastYaw = p.getYaw();
            lastPitch = p.getPitch();
            if (!ok2 || !same2) return;
            BlockHitResult real = crosshair(mc);
            boolean right = real.getType() == HitResult.Type.BLOCK
                    && (act == Act.WATERLOG ? real.getBlockPos().equals(tgt)
                                            : real.getBlockPos().offset(real.getSide()).equals(tgt));
            if (!right) {                                   // chưa trúng đúng ô -> KHÔNG đổ, tính lại điểm ngắm
                if (++aimMiss > 12) { aim = null; aimFor = null; }
                return;
            }
            Motion.sneak(false);
            long key = tgt.asLong();
            if (tries.merge(key, 1, Integer::sum) > 4) {    // thử mãi không được -> bỏ ô này
                bad.add(key);
                tries.remove(key);
                pending.remove(tgt);
                return;
            }
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            p.swingHand(Hand.MAIN_HAND);
            placeCooldown = cfg.placeWait() + 2;
            pending.remove(tgt);
            aim = null;
            aimFor = null;
            scanCooldown = Math.min(scanCooldown, 8);        // quét lại sớm để kiểm tra đã đổ được chưa
            return;
        }

        // Chọn mặt tựa + điểm bấm + hướng nhìn: block có hướng/thuộc tính thì tính riêng (Orient)
        Orient.Plan plan = null;
        if (cfg.autoOrient && Orient.needs(want)) {
            long key = tgt.asLong();
            if (plans.containsKey(key)) plan = plans.get(key);
            else { plan = Orient.plan(mc, tgt, want, item); plans.put(key, plan); }
        }
        BlockPos sup;
        Direction click;
        Vec3d hitVec;
        if (plan != null) {
            sup = tgt.offset(plan.face);
            click = plan.face.getOpposite();
            hitVec = plan.hit;
        } else {
            if (aim == null || !tgt.equals(aimFor)) { aim = findAim(mc, tgt, Act.PLACE); aimFor = tgt; aimWait = 0; aimMiss = 0; }
            if (aim == null) {
                status = t("Không có góc ngắm rõ để đặt - đứng gần/đổi chỗ...", "No clear line to place - repositioning...");
                if (++aimWait > 40) { bad.add(tgt.asLong()); pending.remove(tgt); aimFor = null; }
                return;
            }
            sup = aim.sup;
            click = aim.click;
            hitVec = aim.point;
        }
        status = t("Đang đặt ", "Placing ") + want.getBlock().getName().getString() + " (layer " + (layer + 1) + "/" + layers + ")"
                + (plan != null ? t(" - chỉnh hướng", " - orienting") : "");
        if (plan != null) {
            boolean ok = Motion.turn(p, plan.yaw, plan.pitch, cfg.turnSpeed());
            boolean same = Math.abs(p.getYaw() - lastYaw) < 0.05f && Math.abs(p.getPitch() - lastPitch) < 0.05f;
            lastYaw = p.getYaw();
            lastPitch = p.getPitch();
            if (!ok || !same) return;                    // đợi server nhận hướng nhìn rồi mới bấm
        } else {
            boolean ok = Motion.lookAt(p, hitVec, cfg.turnSpeed());
            boolean same = Math.abs(p.getYaw() - lastYaw) < 0.05f && Math.abs(p.getPitch() - lastPitch) < 0.05f;
            lastYaw = p.getYaw();
            lastPitch = p.getPitch();
            if (!ok || !same) return;
            // chỉ đặt khi crosshair THẬT đang chỉ đúng mặt block tựa, và bấm đúng điểm crosshair đang chỉ
            BlockHitResult real = crosshair(mc);
            if (real.getType() != HitResult.Type.BLOCK || !real.getBlockPos().equals(sup) || real.getSide() != click) {
                if (++aimMiss > 12) { aim = null; aimFor = null; }
                return;
            }
            hitVec = real.getPos();
        }
        if (!p.isSneaking()) { Motion.sneak(true); return; }   // tránh mở rương/bàn craft khi đặt
        aim = null;
        aimFor = null;
        ActionResult res = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hitVec, click, sup, false));
        if (res.isAccepted()) p.swingHand(Hand.MAIN_HAND);
        lastPlaced = tgt;
        lastDesired = want;
        verifyWait = 0;
        placeCooldown = cfg.placeWait();
        pending.remove(tgt);
        plans.remove(tgt.asLong());
    }

    private static List<Long> toLongs(List<BlockPos> l) {
        List<Long> r = new ArrayList<>();
        for (BlockPos b : l) r.add(b.asLong());
        return r;
    }
}
