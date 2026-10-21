package dev.mcagent.fakeplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Pathfinding for the agent, on a grid of standing cells.
 *
 * <p>The first walker only pushed forward and jumped when its body touched a wall. That
 * works on a staircase and fails on everything else: it walks into a pit, and from the
 * bottom of the pit the next pillar is two blocks up, which no jump clears. The fix is not
 * a better reflex but a look ahead - read the blocks around the agent, build a small graph
 * of places it could stand, and let the graph include the moves a player actually has:
 * walk, step up one, drop down, and jump a two or three block gap.</p>
 *
 * <p>The graph is local: a box around the straight line from here to the target, capped at
 * {@link #MAX_NODES}. A path that would need a huge detour is outside the scope of a
 * "walk there" call, and returning null lets the caller fall back to steering.</p>
 */
public final class AgentNav {

    /** One place to stand, and whether reaching it needs a jump. */
    public record Hop(BlockPos stand, boolean jump) {}

    private record Node(BlockPos pos, double g, double f) {}

    /** The four corner-to-corner moves. A staircase built this way is one block apart. */
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    /** Node budget. A 35-block route through village houses ran out at 6000 and gave up. */
    private static final int MAX_NODES = 30000;
    /** Base search margin; long routes widen it, because a detour can leave a 10 block box. */
    private static final int MARGIN = 10;
    private static final int MARGIN_MAX = 40;
    private static final int VERTICAL = 8;

    private AgentNav() {}

    public static List<Hop> plan(ServerPlayer player, Vec3 target) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos start = standCell(level, player.blockPosition());
        BlockPos goal = goalCell(level, BlockPos.containing(target));
        if (start == null || goal == null || start.equals(goal)) {
            return null;
        }

        int detour = Math.max(
                Math.abs(start.getX() - goal.getX()),
                Math.abs(start.getZ() - goal.getZ())) / 3;
        int margin = Math.min(MARGIN + detour, MARGIN_MAX);
        int minX = Math.min(start.getX(), goal.getX()) - margin;
        int maxX = Math.max(start.getX(), goal.getX()) + margin;
        int minZ = Math.min(start.getZ(), goal.getZ()) - margin;
        int maxZ = Math.max(start.getZ(), goal.getZ()) + margin;
        int minY = Math.min(start.getY(), goal.getY()) - VERTICAL;
        int maxY = Math.max(start.getY(), goal.getY()) + 2;

        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Boolean> jumped = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Set<BlockPos> closed = new HashSet<>();

        best.put(start, 0.0);
        open.add(new Node(start, 0.0, heuristic(start, goal)));

        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node node = open.poll();
            BlockPos at = node.pos();
            if (closed.contains(at)) {
                continue;
            }
            closed.add(at);
            expanded++;
            if (at.equals(goal)) {
                return rebuild(cameFrom, jumped, start, goal);
            }
            double g = node.g();
            for (Hop next : neighbours(level, at)) {
                BlockPos to = next.stand();
                if (to.getX() < minX || to.getX() > maxX
                        || to.getZ() < minZ || to.getZ() > maxZ
                        || to.getY() < minY || to.getY() > maxY
                        || closed.contains(to)) {
                    continue;
                }
                double cost = g + moveCost(at, to, next.jump());
                Double known = best.get(to);
                if (known != null && known <= cost) {
                    continue;
                }
                best.put(to, cost);
                cameFrom.put(to, at);
                jumped.put(to, next.jump());
                open.add(new Node(to, cost, cost + heuristic(to, goal)));
            }
        }
        return null;
    }

    /** Every place reachable from {@code at} in one move. */
    private static List<Hop> neighbours(ServerLevel level, BlockPos at) {
        List<Hop> out = new ArrayList<>(16);
        boolean headroom = passable(level, at.above(2));

        for (Direction d : HORIZONTAL) {
            int x = at.getX() + d.getStepX();
            int z = at.getZ() + d.getStepZ();

            // Walk straight on.
            if (standable(level, new BlockPos(x, at.getY(), z))) {
                out.add(new Hop(new BlockPos(x, at.getY(), z), false));
            }
            // Step up one - what the game's own 0.6 step height allows.
            if (headroom && standable(level, new BlockPos(x, at.getY() + 1, z))) {
                out.add(new Hop(new BlockPos(x, at.getY() + 1, z), false));
            }
            // Drop down up to three.
            for (int dy = 1; dy <= 3; dy++) {
                BlockPos down = new BlockPos(x, at.getY() - dy, z);
                if (standable(level, down)) {
                    out.add(new Hop(down, false));
                    break;
                }
            }
            // Jump a gap of two or three, landing level or one up.
            if (headroom) {
                for (int dist = 2; dist <= 3; dist++) {
                    for (int up = 0; up <= 1; up++) {
                        BlockPos landing = new BlockPos(
                                at.getX() + d.getStepX() * dist,
                                at.getY() + up,
                                at.getZ() + d.getStepZ() * dist);
                        if (!standable(level, landing)) {
                            continue;
                        }
                        // The gap itself must be empty, otherwise this is a wall, not a jump.
                        boolean clear = true;
                        for (int step = 1; step < dist; step++) {
                            BlockPos over = new BlockPos(
                                    at.getX() + d.getStepX() * step,
                                    at.getY() + 1,
                                    at.getZ() + d.getStepZ() * step);
                            if (!passable(level, over)) {
                                clear = false;
                                break;
                            }
                        }
                        if (clear) {
                            out.add(new Hop(landing, true));
                        }
                    }
                }
            }
        }

        // Diagonals. Stairs built corner-to-corner are a single block apart in the game's
        // own sense, and without these edges a staircase like that is simply unreachable -
        // the agent used to fall back to steering and shove itself into the first step.
        for (int[] off : DIAGONAL) {
            int x = at.getX() + off[0];
            int z = at.getZ() + off[1];
            // The game refuses to squeeze through a diagonal corner: both side cells have
            // to be open, or the body clips the block it is turning past.
            if (!passable(level, new BlockPos(at.getX() + off[0], at.getY(), at.getZ()))
                    || !passable(level, new BlockPos(at.getX(), at.getY(), at.getZ() + off[1]))) {
                continue;
            }
            if (standable(level, new BlockPos(x, at.getY(), z))) {
                out.add(new Hop(new BlockPos(x, at.getY(), z), false));
            }
            if (headroom && standable(level, new BlockPos(x, at.getY() + 1, z))) {
                out.add(new Hop(new BlockPos(x, at.getY() + 1, z), false));
            }
            for (int dy = 1; dy <= 3; dy++) {
                BlockPos down = new BlockPos(x, at.getY() - dy, z);
                if (standable(level, down)) {
                    out.add(new Hop(down, false));
                    break;
                }
            }
        }
        return out;
    }

    private static double moveCost(BlockPos from, BlockPos to, boolean jump) {
        int dy = to.getY() - from.getY();
        if (jump) {
            return 2.5 + Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
        }
        if (dy > 0) {
            return 1.2;             // step up
        }
        if (dy < 0) {
            return 1.0 + (-dy) * 0.25;   // dropping costs a little
        }
        return 1.0;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ())
                + Math.abs(a.getY() - b.getY()) * 0.5;
    }

    private static List<Hop> rebuild(Map<BlockPos, BlockPos> cameFrom,
                                     Map<BlockPos, Boolean> jumped,
                                     BlockPos start, BlockPos goal) {
        List<Hop> hops = new ArrayList<>();
        BlockPos at = goal;
        while (!at.equals(start)) {
            hops.add(new Hop(at, Boolean.TRUE.equals(jumped.get(at))));
            BlockPos prev = cameFrom.get(at);
            if (prev == null) {
                return null;
            }
            at = prev;
        }
        Collections.reverse(hops);
        return hops;
    }

    /** The standing cell containing (or just under) a position. */
    private static BlockPos standCell(ServerLevel level, BlockPos near) {
        for (int dy = 2; dy >= -4; dy--) {
            BlockPos candidate = near.above(dy);
            if (standable(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The standing cell closest to an arbitrary point. */
    private static BlockPos goalCell(ServerLevel level, BlockPos target) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = 2; dy >= -5; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos candidate = target.offset(dx, dy, dz);
                    if (!standable(level, candidate)) {
                        continue;
                    }
                    double dist = candidate.distSqr(new BlockPos(
                            target.getX(), candidate.getY(), target.getZ()));
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /** A cell the player can occupy: two passable cells above a solid floor. */
    private static boolean standable(ServerLevel level, BlockPos p) {
        return passable(level, p) && passable(level, p.above()) && !passable(level, p.below());
    }

    private static boolean passable(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }
}
