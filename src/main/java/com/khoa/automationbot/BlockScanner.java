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
    public int maxRadius = 64;                     // giới hạn bán kính ngang
    public int maxPerBlock = 4000;                 // số vị trí tối đa lưu cho mỗi loại block
    public int idleWait = 10;                      // số tick nghỉ giữa hai chu kỳ quét
    public int budget = 2500;
    public boolean fullHeight;                     // quét từ minY tới maxY thay vì chỉ ±radius theo Y
    public int minY = -64, maxY = 320;
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
    private int size, y0;
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
            int r = Math.max(2, Math.min(maxRadius, radius));
            size = 2 * r + 1;
            wcenter = mc.player.getBlockPos();
            int height = size;
            y0 = wcenter.getY() - r;
            if (fullHeight) {
                int lo = Math.max(mc.world.getBottomY(), minY);
                int hi = Math.min(mc.world.getBottomY() + mc.world.getHeight() - 1, maxY);
                if (hi < lo) hi = lo;
                y0 = lo;
                height = hi - lo + 1;
            }
            total = (long) size * size * height;
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
            int y = y0 + idx / (size * size);
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
                if (l.size() < maxPerBlock) l.add(m.toImmutable());
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
            wait = idleWait;
        }
    }
}
