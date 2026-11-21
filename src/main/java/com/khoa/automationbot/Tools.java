package com.khoa.automationbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;

public final class Tools {
    private Tools() {}

    /** Chuyển chỉ số trong PlayerInventory sang slot id của PlayerScreenHandler. */
    public static int slotId(int invIndex) { return invIndex < 9 ? invIndex + 36 : invIndex; }

    public static boolean loaded(World w, int x, int z) {
        return w.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null;
    }

    public static boolean inventoryFull(PlayerEntity p) { return p.getInventory().getEmptySlot() == -1; }


    /** Vứt một stack thuộc danh sách bỏ qua để tạo chỗ trống trong túi đồ. */
    public static boolean dropOneIgnored(MinecraftClient mc, PlayerEntity p, Item keep) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = p.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String id = itemId(stack.getItem());
            if (stack.getItem() != keep && Config.get().ignoreItems.contains(id)) {
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, slotId(i),
                        1, SlotActionType.THROW, (net.minecraft.entity.player.PlayerEntity) p);
                return true;
            }
        }
        return false;
    }

    public static int findItem(PlayerEntity p, Item item) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.getItem() == item) return i;
        }
        return -1;
    }

    public static int count(PlayerEntity p, Item item) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.getItem() == item) n += s.getCount();
        }
        return n;
    }

    public static int totalItems(PlayerEntity p) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) n += inv.getStack(i).getCount();
        return n;
    }

    public static boolean fits(PlayerEntity p, ItemStack st) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) return true;
            if (ItemStack.areItemsAndComponentsEqual(s, st) && s.getCount() < s.getMaxCount()) return true;
        }
        return false;
    }

    public static boolean lowDurability(ItemStack s) {
        return s.isDamageable() && s.getMaxDamage() - s.getDamage() <= 3;
    }

    /**
     * Chọn công cụ trên hotbar phù hợp nhất và kiểm tra độ bền.
     * Trả về false nếu không có công cụ phù hợp / công cụ sắp hỏng.
     */
    public static boolean selectTool(MinecraftClient mc, BlockState st, boolean auto) {
        ClientPlayerEntity p = mc.player;
        if (p.isCreative()) return true;
        PlayerInventory inv = p.getInventory();
        if (auto) {
            int best = -1;
            float bs = 1.0f;
            for (int i = 0; i < 9; i++) {
                ItemStack s = inv.getStack(i);
                if (lowDurability(s)) continue;
                float sp = s.getMiningSpeedMultiplier(st);
                if (sp > bs) { bs = sp; best = i; }
            }
            if (best >= 0) inv.setSelectedSlot(best);
        }
        ItemStack cur = p.getMainHandStack();
        if (lowDurability(cur)) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = inv.getStack(i);
                if (!lowDurability(s)) { inv.setSelectedSlot(i); cur = s; break; }
            }
            if (lowDurability(cur)) return false;
        }
        return !st.isToolRequired() || cur.isSuitableFor(st);
    }

    public static String itemId(Item i) { return Registries.ITEM.getId(i).toString(); }
    public static String blockId(Block b) { return Registries.BLOCK.getId(b).toString(); }

    public static double distSq(BlockPos b, Vec3d v) { return Vec3d.ofCenter(b).squaredDistanceTo(v); }
}
