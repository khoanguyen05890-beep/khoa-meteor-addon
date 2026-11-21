package com.khoa.automationbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Quét theo từng đợt nhỏ (budget block/tick) nên không làm treo game.
 * Kết quả được "xuất bản" nguyên tử khi hoàn tất một chu kỳ quét.
 */
public final class BlockScanner {
    public int radius = 16;
    public int budget = 2500;
    public Predicate<Block> tracker;               // block nào cần lưu vị trí
    public Map<Block, Integer> counts = new HashMap<>();
    public Map<Block, List<BlockPos>> positions = new HashMap<>();
    public long scannedTotal;
    public int cycles;
    public boolean cycleDone;
    public BlockPos center = BlockPos.ORIGIN;

    private Map<Block, Integer> wc = new HashMap<>();
    private Map<Block, List<BlockPos>> wp = new HashMap<>();
    private long cursor, total;
    private int size;
    private BlockPos wcenter = BlockPos.ORIGIN;
    private boolean running;
    private int wait;

    public void restart() {
        running = false;
        wait = 0;
        counts = new HashMap<>();
        positions = new HashMap<>();
        cycleDone = false;
        scannedTotal = 0;
    }

    public double cycleProgress() { return running && total > 0 ? (double) cursor / total : (cycleDone ? 1 : 0); }

    public void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        if (!running) {
            if (wait > 0) { wait--; return; }
            int r = Math.max(2, Math.min(64, radius));
            size = 2 * r + 1;
            total = (long) size * size * size;
            wcenter = mc.player.getBlockPos();
            cursor = 0;
            wc = new HashMap<>();
            wp = new HashMap<>();
            running = true;
        }
        int r = (size - 1) / 2;
        BlockPos.Mutable m = new BlockPos.Mutable();
        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        boolean lastLoaded = false;
        long end = Math.min(total, cursor + budget);
        for (; cursor < end; cursor++) {
            int idx = (int) cursor;
            int x = wcenter.getX() + idx % size - r;
            int z = wcenter.getZ() + (idx / size) % size - r;
            int y = wcenter.getY() + idx / (size * size) - r;
            if (mc.world.isOutOfHeightLimit(y)) continue;
            int cx = x >> 4, cz = z >> 4;
            if (cx != lastCx || cz != lastCz) {
                lastCx = cx; lastCz = cz;
                lastLoaded = Tools.loaded(mc.world, x, z);
            }
            if (!lastLoaded) continue;
            m.set(x, y, z);
            BlockState s = mc.world.getBlockState(m);
            if (s.isAir()) continue;
            Block b = s.getBlock();
            wc.merge(b, 1, Integer::sum);
            if (tracker != null && tracker.test(b)) {
                List<BlockPos> l = wp.computeIfAbsent(b, k -> new ArrayList<>());
                if (l.size() < 4000) l.add(m.toImmutable());
            }
        }
        if (cursor >= total) {
            counts = wc;
            positions = wp;
            center = wcenter;
            scannedTotal = total;
            cycles++;
            cycleDone = true;
            running = false;
            wait = 10;
        }
    }
}
