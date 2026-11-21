package com.khoa.automationbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Property;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
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
 * Đối chiếu đầy đủ BlockState; block có hướng FACING/HORIZONTAL_FACING được đặt từ phía hỗ trợ phù hợp.
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
    private record MaterialChoice(Item item, BlockState state) {}
    private final Deque<Req> queue = new ArrayDeque<>();
    private final Map<Item, Long> unobtainable = new HashMap<>();
    private Provision supply;
    private Item supplyItem;
    private boolean layerChecked;
    private long now;

    // dựng cột (pillar) để lên layer cao
    private int pillarMode, pillarTicks;   // 0 = tắt, 1 = đi tới chân cột, 2 = đang nhảy-đặt block

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
        now = 0;
        if (!loadBox()) { state = State.ERROR; status = error; return; }
        // tự chọn layer theo độ cao đang đứng
        MinecraftClient mc = MinecraftClient.getInstance();
        Config cfg = Config.get();
        if (cfg.autoLayer && mc.player != null) {
            Integer visibleY = LitematicaBridge.visibleLayerY();
            int startY = visibleY != null ? visibleY : mc.player.getBlockY();
            layer = startY + cfg.layerOffset - minY;
        }
        layer = Math.max(0, Math.min(layer, layers - 1));
        beginAnalysis();
    }

    @Override protected void onStop() { walker.clear(); pending.clear(); }

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
        if (loadBox()) beginAnalysis();
    }

    private void beginAnalysis() {
        bad.clear();
        pending.clear();
        layerChecked = false;
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
            if (w.getBlockState(m).equals(s)) tBuilt[y]++;
            else tMiss.merge(b, 1, Integer::sum);
        }
        if (cursor >= volume) {
            total = tTot; built = tBuilt; required = tReq; missing = tMiss;
            analyzing = false;
            analyzedOnce = true;
            placedSince = 0;
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

    public void nextLayer() { if (layer < layers - 1) { layer++; resetLayer(); } }
    public void prevLayer() { if (layer > 0) { layer--; resetLayer(); } }

    private void resetLayer() {
        pending.clear();
        layerChecked = false;
        queue.clear();
        pillarMode = 0;
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
                if (!w.getBlockState(m).equals(want)) tmp.add(m.toImmutable());
            }
        }
        Vec3d pp = mc.player.getEntityPos();
        tmp.sort(Comparator.comparingDouble(b -> Tools.distSq(b, pp)));
        for (int i = 0; i < tmp.size() && i < 2000; i++) pending.add(tmp.get(i));
        if (!layerChecked && !pending.isEmpty()) { layerChecked = true; queueSupplies(mc); }
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
            BlockState desired = schem.getBlockState(pos);
            if (chooseMaterial(mc.player, desired) != null) continue;
            Item it = desired.getBlock().asItem();
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
            if (res.isAccepted()) p.swingHand(Hand.MAIN_HAND);
        }
        Motion.jump(true);
        return true;
    }

    /**
     * Chọn mặt tựa theo hướng block trong schematic.
     * Với block có FACING/HORIZONTAL_FACING, chỉ đặt từ phía tạo ra đúng hướng đó.
     */
    private Direction supportFace(ClientWorld w, BlockPos pos, BlockState desired) {
        Direction requiredSupport = null;
        if (desired.contains(Properties.HORIZONTAL_FACING)) {
            requiredSupport = desired.get(Properties.HORIZONTAL_FACING).getOpposite();
        } else if (desired.contains(Properties.FACING)) {
            requiredSupport = desired.get(Properties.FACING).getOpposite();
        }

        if (requiredSupport != null) {
            BlockPos n = pos.offset(requiredSupport);
            BlockState support = w.getBlockState(n);
            if (!support.isAir() && !support.isReplaceable() && support.getFluidState().isEmpty()) {
                return requiredSupport;
            }
            return null;
        }

        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState support = w.getBlockState(n);
            if (!support.isAir() && !support.isReplaceable() && support.getFluidState().isEmpty()) return d;
        }
        return null;
    }

    /** Nhóm vật liệu được phép thay thế: giữ cùng công năng/hình dạng, chỉ đổi loại vật liệu. */
    private String materialGroup(BlockState state) {
        if (state.isIn(BlockTags.PLANKS)) return "planks";
        if (state.isIn(BlockTags.LOGS)) return "logs";
        if (state.isIn(BlockTags.WOOL)) return "wool";
        if (state.isIn(BlockTags.STAIRS)) return "stairs";
        if (state.isIn(BlockTags.SLABS)) return "slabs";
        if (state.isIn(BlockTags.WALLS)) return "walls";
        if (state.isIn(BlockTags.FENCES)) return "fences";
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private BlockState copyCompatibleProperties(BlockState desired, BlockState replacement) {
        BlockState result = replacement;
        for (Property property : desired.getProperties()) {
            if (!result.contains(property)) continue;
            Comparable value = desired.get(property);
            if (property.getValues().contains(value)) result = result.with(property, value);
        }
        return result;
    }

    /** Ưu tiên đúng vật liệu; nếu thiếu thì tìm block cùng nhóm trong túi đồ. */
    private MaterialChoice chooseMaterial(ClientPlayerEntity player, BlockState desired) {
        Item exact = desired.getBlock().asItem();
        if (exact != Items.AIR && Tools.findItem(player, exact) >= 0)
            return new MaterialChoice(exact, desired);

        String group = materialGroup(desired);
        if (group == null) return null;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            Item candidate = inv.getStack(i).getItem();
            if (!(candidate instanceof BlockItem blockItem)) continue;
            BlockState candidateState = blockItem.getBlock().getDefaultState();
            if (!group.equals(materialGroup(candidateState))) continue;
            return new MaterialChoice(candidate, copyCompatibleProperties(desired, candidateState));
        }
        return null;
    }

    private boolean matchesSchematic(BlockState current, BlockState desired) {
        if (current.equals(desired)) return true;
        String wantedGroup = materialGroup(desired);
        return wantedGroup != null && wantedGroup.equals(materialGroup(current));
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
            if (matchesSchematic(w.getBlockState(lastPlaced), lastDesired)) {
                Stats.built++;
                placedSince++;
                lastPlaced = null;
            } else if (++verifyWait > 20) {
                if (!pending.contains(lastPlaced)) pending.add(0, lastPlaced);
                status = t("Block vừa đặt không khớp trạng thái schematic. Tạm dừng để tránh bỏ sót layer.",
                        "Placed block does not match the schematic state. Pausing to avoid skipping this layer.");
                lastPlaced = null;
                pause();
                return;
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
        int checked = 0;
        for (int i = 0; i < pending.size() && checked < 150; i++) {
            BlockPos pos = pending.get(i);
            if (bad.contains(pos.asLong())) { pending.remove(i--); continue; }
            BlockState ws = schem.getBlockState(pos);
            BlockState cs = w.getBlockState(pos);
            if (matchesSchematic(cs, ws)) { pending.remove(i--); continue; }
            if (!cs.isReplaceable()) {
                status = t("Vị trí cần xây đang bị block khác chiếm: ", "Target position is occupied by another block: ")
                        + pos.toShortString();
                pause();
                return;
            }
            Item it = ws.getBlock().asItem();
            if (it == Items.AIR) {
                status = t("Block trong schematic không có vật phẩm tương ứng: ", "Schematic block has no corresponding item: ")
                        + ws.getBlock().getName().getString();
                pause();
                return;
            }
            checked++;
            MaterialChoice choice = chooseMaterial(p, ws);
            if (choice == null) { missItem = it; continue; }
            Direction d = supportFace(w, pos, choice.state());
            if (d == null) continue;
            tgt = pos; want = choice.state(); face = d;
            break;
        }

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
            if (++idle > 600) {
                pause();
                status = t("Không thể đặt một số block của layer này. Kiểm tra điểm tựa/hướng block rồi Resume.",
                        "Some blocks in this layer cannot be placed. Check support/orientation, then Resume.");
                idle = 0;
            }
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
            if (pillarMode != 0) {
                if (!pillarStep(mc, g)) {
                    pillarMode = 0;
                    Motion.jump(false);
                    walker.clear();
                    goalTarget = null;
                    status = t("Không thể lên layer đang xây bằng cột hỗ trợ. Giữ nguyên block để thử lại.",
                            "Could not climb to the active build layer with scaffolding. Keeping the target for retry.");
                    pause();
                }
                return;
            }
            if (!g.equals(goalTarget) || !walker.active()) {
                Path path = Path.find(w, feet, n -> {
                    Vec3d eye = new Vec3d(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5);
                    return eye.squaredDistanceTo(Vec3d.ofCenter(g)) <= 3.8 * 3.8 && !n.equals(g) && !n.up().equals(g);
                }, g, 4000);
                boolean needUp = g.getY() - feet.getY() >= 2;
                if (path == null || (!path.complete && needUp)) {
                    if (cfg.autoPillar && needUp && startPillar(mc, g)) return;   // dựng cột lên đúng cao độ layer
                    if (path == null) {
                        status = t("Không tìm được đường lên layer hiện tại; giữ block để thử lại",
                                "No route to the active layer; keeping the target for retry");
                        if (++idle > 120) { pause(); idle = 0; }
                        return;
                    }
                }
                walker.set(path);
                goalTarget = g;
            }
            Walker.Result r = walker.tick(mc);
            status = t("Đang di chuyển tới vị trí xây...", "Moving to build position...");
            if (r == Walker.Result.FAILED || (r == Walker.Result.DONE && (far || inside))) {
                walker.clear();
                goalTarget = null;
                if (++idle > 20) {
                    status = t("Không tới được vị trí block của layer này. Kiểm tra đường đi rồi Resume.",
                            "Could not reach this layer's target position. Check the route, then Resume.");
                    pause();
                    idle = 0;
                }
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
