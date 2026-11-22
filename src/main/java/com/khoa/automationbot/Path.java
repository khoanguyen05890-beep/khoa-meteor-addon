package com.khoa.automationbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Predicate;

/**
 * A* trên lưới block: đi bộ, nhảy lên 1 block, rơi xuống tối đa 3 block, tránh lửa/dung nham/nước.
 * Chế độ dig: được phép đào xuyên block (ngang, lên 1 bậc, đào xuống), trừ bedrock và chỗ sát dung nham.
 */
public final class Path {
    /** true = chỉ đi qua ô lộ thiên (thấy trời) - không chui vào hang/hầm. Chỉ áp dụng khi ô xuất phát cũng lộ thiên. */
    public static boolean surfaceOnly;
    private static boolean curSurf;

    public final List<BlockPos> nodes;
    public final boolean complete;
    public final boolean dig;

    private Path(List<BlockPos> n, boolean c, boolean dig) { nodes = n; complete = c; this.dig = dig; }

    private static final class Node {
        final BlockPos pos; final Node parent; final double g, f;
        Node(BlockPos p, Node par, double g, double f) { pos = p; parent = par; this.g = g; this.f = f; }
    }

    public static boolean passable(World w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        if (!s.getFluidState().isEmpty()) return false;
        if (!s.getCollisionShape(w, p).isEmpty()) return false;
        Block b = s.getBlock();
        return b != Blocks.FIRE && b != Blocks.SOUL_FIRE && b != Blocks.COBWEB
                && b != Blocks.SWEET_BERRY_BUSH && b != Blocks.POWDER_SNOW;
    }

    public static boolean floorOk(World w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        if (!s.getFluidState().isEmpty()) return false;
        Block b = s.getBlock();
        if (b == Blocks.MAGMA_BLOCK || b == Blocks.CACTUS) return false;
        return s.isSideSolidFullSquare(w, p, Direction.UP);
    }

    public static boolean standable(World w, BlockPos p) {
        return passable(w, p) && passable(w, p.up()) && floorOk(w, p.down());
    }

    /** Không sát dung nham và không có cát/sỏi rơi xuống đầu. */
    public static boolean safeToDig(World w, BlockPos c) {
        for (Direction d : Direction.values()) {
            if (w.getBlockState(c.offset(d)).getFluidState().isIn(FluidTags.LAVA)) return false;
        }
        return !(w.getBlockState(c.up()).getBlock() instanceof FallingBlock);
    }

    public static boolean diggable(World w, BlockPos c) {
        BlockState s = w.getBlockState(c);
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        if (s.getHardness(w, c) < 0) return false;          // bedrock, barrier...
        return safeToDig(w, c);
    }

    /** Chi phí thêm để đi vào ô c: 0 nếu trống, 4 nếu phải đào, -1 nếu không thể. */
    private static double enter(World w, BlockPos c, boolean dig) {
        if (passable(w, c)) return 0;
        if (dig && diggable(w, c)) return 4.0;
        return -1;
    }

    private static double h(BlockPos a, BlockPos hint) {
        if (hint == null) return 0;
        double dx = a.getX() - hint.getX(), dy = a.getY() - hint.getY(), dz = a.getZ() - hint.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static Path find(World w, BlockPos start, Predicate<BlockPos> goal, BlockPos hint, int maxNodes) {
        return find(w, start, goal, hint, maxNodes, false);
    }

    public static Path find(World w, BlockPos start, Predicate<BlockPos> goal, BlockPos hint, int maxNodes, boolean dig) {
        final double hw = dig ? 2.0 : 1.0;
        final boolean strict = !lavaNear(w, start);
        curSurf = surfaceOnly && w.isSkyVisible(start.up());
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Node> all = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        Node s = new Node(start, null, 0, h(start, hint) * hw);
        open.add(s);
        all.put(start.asLong(), s);
        Node best = s;
        double bestH = h(start, hint);
        int exp = 0;
        while (!open.isEmpty() && exp < maxNodes) {
            Node n = open.poll();
            if (!closed.add(n.pos.asLong())) continue;
            exp++;
            if (goal.test(n.pos)) return build(n, true, dig);
            double hh = h(n.pos, hint);
            if (hh < bestH) { bestH = hh; best = n; }
            int y = n.pos.getY();
            BlockPos up2 = n.pos.up(2);
            for (Direction d : Direction.Type.HORIZONTAL) {
                int nx = n.pos.getX() + d.getOffsetX(), nz = n.pos.getZ() + d.getOffsetZ();
                BlockPos c0 = new BlockPos(nx, y, nz);
                BlockPos c0u = c0.up();
                double e0 = enter(w, c0, dig), e1 = enter(w, c0u, dig);
                if (e0 >= 0 && e1 >= 0) {
                    double extra = e0 + e1;
                    if (floorOk(w, c0.down())) {
                        push(w, strict, open, all, closed, n, c0, 1.0 + extra, hint, hw);
                    } else if (extra == 0) {
                        for (int k = 1; k <= 3; k++) {
                            BlockPos c = new BlockPos(nx, y - k, nz);
                            if (!passable(w, c)) break;
                            if (floorOk(w, c.down())) { push(w, strict, open, all, closed, n, c, 1.0 + k, hint, hw); break; }
                        }
                    }
                }
                // leo lên 1 bậc
                BlockPos c1 = new BlockPos(nx, y + 1, nz);
                if (floorOk(w, c0)) {
                    double u = enter(w, up2, dig), a = enter(w, c1, dig), b = enter(w, c1.up(), dig);
                    if (u >= 0 && a >= 0 && b >= 0) push(w, strict, open, all, closed, n, c1, 1.6 + u + a + b, hint, hw);
                }
            }
            // đào xuống
            if (dig) {
                BlockPos below = n.pos.down();
                double e = enter(w, below, true);
                if (e > 0) {
                    for (int k = 0; k <= 3; k++) {
                        BlockPos land = new BlockPos(n.pos.getX(), y - 1 - k, n.pos.getZ());
                        if (k > 0 && !passable(w, land)) break;
                        if (floorOk(w, land.down())) { push(w, strict, open, all, closed, n, land, 1.0 + e + k, hint, hw); break; }
                    }
                }
            }
        }
        if (best != s) return build(best, false, dig);
        return null;
    }

    /** Có dung nham trong khối 3x3x3 quanh ô p (kể cả ô bên dưới). */
    public static boolean lavaNear(World w, BlockPos p) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -2; dy <= 1; dy++) {
                    m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                    if (w.getBlockState(m).getFluidState().isIn(FluidTags.LAVA)) return true;
                }
        return false;
    }

    /** Phạt các ô sát mép vực (ô bên cạnh rơi sâu > 3 block) để đường đi tránh xa mép. */
    private static double edgePenalty(World w, BlockPos p) {
        double pen = 0;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos n = p.offset(d);
            if (!passable(w, n) && !passable(w, n.up())) continue;       // có tường
            boolean deep = true;
            for (int k = 1; k <= 4; k++) {
                if (!passable(w, n.down(k))) { deep = false; break; }
            }
            if (deep) pen += 4.0;
        }
        return pen;
    }

    private static void push(World w, boolean strict, PriorityQueue<Node> open, Map<Long, Node> all, Set<Long> closed,
                             Node from, BlockPos to, double cost, BlockPos hint, double hw) {
        long key = to.asLong();
        if (closed.contains(key)) return;
        if (curSurf && !w.isSkyVisible(to.up())) return;                // chỉ đi trên mặt đất
        if (strict && lavaNear(w, to)) return;          // không đi sát hố dung nham (trừ khi đang đứng sát sẵn)
        cost += edgePenalty(w, to);
        double g = from.g + cost;
        Node ex = all.get(key);
        if (ex != null && ex.g <= g) return;
        Node nn = new Node(to, from, g, g + h(to, hint) * hw);
        all.put(key, nn);
        open.add(nn);
    }

    private static Path build(Node end, boolean complete, boolean dig) {
        LinkedList<BlockPos> list = new LinkedList<>();
        for (Node n = end; n != null && n.parent != null; n = n.parent) list.addFirst(n.pos);
        return new Path(new ArrayList<>(list), complete, dig);
    }
}
