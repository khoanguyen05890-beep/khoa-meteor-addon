package com.khoa.automationbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.khoa.automationbot.Lang.t;

public final class ItemCollector extends Task {
    private final Walker walker = new Walker();
    private ItemEntity target;
    private final Set<Integer> bad = new HashSet<>();
    private int retarget, waitNear, dropCooldown;
    public boolean full;
    /** true = nhặt mọi thứ (kể cả đồ nằm ngoài danh sách), vd shulker/ender chest vừa đập. */
    public boolean pickAll;

    @Override public String name() { return "Item Collector"; }

    @Override protected void onStart() { bad.clear(); target = null; walker.clear(); }
    @Override protected void onStop() { walker.clear(); target = null; }

    @Override
    public void tick(MinecraftClient mc) {
        boolean busy = step(mc);
        if (full) {
            pause();
            status = t("Túi đồ đầy - dọn túi rồi bấm Resume", "Inventory full - clear space and press Resume");
        } else if (!busy) {
            status = t("Đang chờ vật phẩm rơi...", "Waiting for dropped items...");
        }
    }

    private boolean wanted(ItemEntity e) {
        if (pickAll) return true;
        Config c = Config.get();
        String id = Tools.itemId(e.getStack().getItem());
        if (c.ignoreItems.contains(id)) return false;
        return !c.collectOnlyListed || c.collectItems.contains(id);
    }

    private boolean eyeCloseEnough(BlockPos n, ItemEntity e) {
        double dx = n.getX() + 0.5 - e.getX(), dz = n.getZ() + 0.5 - e.getZ();
        return dx * dx + dz * dz < 0.8 && Math.abs(n.getY() - e.getY()) < 1.6;
    }

    /** Một bước nhặt đồ. Trả về true nếu vẫn còn vật phẩm đang xử lý. */
    public boolean step(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Config c = Config.get();
        full = false;

        if (Tools.inventoryFull(p) && dropCooldown-- <= 0) {
            if (dropIgnored(mc)) dropCooldown = c.delay();
        }

        List<ItemEntity> list = w.getEntitiesByClass(ItemEntity.class,
                p.getBoundingBox().expand(c.itemRadius), e -> e.isAlive() && wanted(e) && !bad.contains(e.getId()));
        boolean anyUnfit = false;
        ItemEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (ItemEntity e : list) {
            ItemStack st = e.getStack();
            if (!Tools.fits(p, st)) { anyUnfit = true; continue; }
            double d = e.getEntityPos().distanceTo(p.getEntityPos());
            if (c.collectItems.contains(Tools.itemId(st.getItem()))) d *= 0.5; // ưu tiên vật phẩm đã chọn
            if (d < bestScore) { bestScore = d; best = e; }
        }
        if (best == null) {
            target = null;
            if (walker.active()) { walker.clear(); Motion.forward(false); Motion.jump(false); }
            if (anyUnfit) full = true;
            return false;
        }

        if (target == null || !target.isAlive() || bad.contains(target.getId()) || --retarget <= 0) {
            if (target != best) { walker.clear(); waitNear = 0; }
            target = best;
            retarget = 40;
        }

        if (eyeCloseEnough(p.getBlockPos(), target) || p.getEntityPos().distanceTo(target.getEntityPos()) < 0.9) {
            Motion.forward(false); Motion.jump(false);
            walker.clear();
            if (++waitNear > 60) { bad.add(target.getId()); target = null; }
            return true;
        }
        waitNear = 0;
        final ItemEntity tgt = target;
        if (!walker.active()) {
            Path path = Path.find(w, p.getBlockPos(), n -> eyeCloseEnough(n, tgt), tgt.getBlockPos(), 3000);
            if (path == null) { bad.add(tgt.getId()); target = null; return true; }
            walker.set(path);
        }
        Walker.Result r = walker.tick(mc);
        if (r == Walker.Result.FAILED) { bad.add(tgt.getId()); target = null; walker.clear(); }
        else if (r == Walker.Result.DONE) walker.clear();
        status = t("Đang nhặt: ", "Collecting: ") + tgt.getStack().getName().getString();
        return true;
    }

    /** Khi túi đầy: vứt vật phẩm trong danh sách bỏ qua. */
    private boolean dropIgnored(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        Config c = Config.get();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            if (c.ignoreItems.contains(Tools.itemId(s.getItem()))) {
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, Tools.slotId(i), 1, SlotActionType.THROW, p);
                return true;
            }
        }
        return false;
    }
}
