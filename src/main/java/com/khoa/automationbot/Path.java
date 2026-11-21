package com.khoa.automationbot;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Predicate;

/** A* trên lưới block: đi bộ, nhảy lên 1 block, rơi xuống tối đa 3 block, tránh lửa/dung nham/nước. */
public final class Path {
    public final List<BlockPos> nodes;
    public final boolean complete;

    private Path(List<BlockPos> n, boolean c) { nodes = n; complete = c; }

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

    private static double h(BlockPos a, BlockPos hint) {
        if (hint == null) return 0;
        double dx = a.getX() - hint.getX(), dy = a.getY() - hint.getY(), dz = a.getZ() - hint.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static Path find(World w, BlockPos start, Predicate<BlockPos> goal, BlockPos hint, int maxNodes) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Node> all = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        Node s = new Node(start, null, 0, h(start, hint));
        open.add(s);
        all.put(start.asLong(), s);
        Node best = s;
        double bestH = h(start, hint);
        int exp = 0;
        while (!open.isEmpty() && exp < maxNodes) {
            Node n = open.poll();
            if (!closed.add(n.pos.asLong())) continue;
            exp++;
            if (goal.test(n.pos)) return build(n, true);
            double hh = h(n.pos, hint);
            if (hh < bestH) { bestH = hh; best = n; }
            int y = n.pos.getY();
            for (Direction d : Direction.Type.HORIZONTAL) {
                int nx = n.pos.getX() + d.getOffsetX(), nz = n.pos.getZ() + d.getOffsetZ();
                BlockPos c0 = new BlockPos(nx, y, nz);
                if (passable(w, c0) && passable(w, c0.up())) {
                    if (floorOk(w, c0.down())) {
                        push(open, all, closed, n, c0, 1.0, hint);
                    } else {
                        for (int k = 1; k <= 3; k++) {
                            BlockPos c = new BlockPos(nx, y - k, nz);
                            if (!passable(w, c)) break;
                            if (floorOk(w, c.down())) { push(open, all, closed, n, c, 1.0 + k, hint); break; }
                        }
                    }
                } else {
                    BlockPos c1 = new BlockPos(nx, y + 1, nz);
                    if (passable(w, n.pos.up(2)) && passable(w, c1) && passable(w, c1.up()) && floorOk(w, c0)) {
                        push(open, all, closed, n, c1, 1.6, hint);
                    }
                }
            }
        }
        if (best != s) return build(best, false);
        return null;
    }

    private static void push(PriorityQueue<Node> open, Map<Long, Node> all, Set<Long> closed,
                             Node from, BlockPos to, double cost, BlockPos hint) {
        long key = to.asLong();
        if (closed.contains(key)) return;
        double g = from.g + cost;
        Node ex = all.get(key);
        if (ex != null && ex.g <= g) return;
        Node nn = new Node(to, from, g, g + h(to, hint));
        all.put(key, nn);
        open.add(nn);
    }

    private static Path build(Node end, boolean complete) {
        LinkedList<BlockPos> list = new LinkedList<>();
        for (Node n = end; n != null && n.parent != null; n = n.parent) list.addFirst(n.pos);
        return new Path(new ArrayList<>(list), complete);
    }
}
