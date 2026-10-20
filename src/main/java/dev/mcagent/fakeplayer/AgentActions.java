package dev.mcagent.fakeplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.UUID;

/**
 * The four things a player does with their hands, routed through {@code ServerPlayerGameMode}
 * so the game's own rules apply: reach, tool speed, block hardness, item behaviour, damage.
 *
 * <p>Nothing here writes a block state directly. That distinction matters: calling
 * {@code level.setBlock} would skip drops, tool checks, neighbour updates and every mod's
 * block-break hook. Going through the game mode means a fake player behaves like a player.</p>
 */
public final class AgentActions {

    /** Sequence numbers the server uses to ack break packets; any increasing value works. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final double REACH = 4.5;

    private AgentActions() {}

    /** One in-flight break: which block, and how far along it is. */
    private record BreakJob(BlockPos pos, Direction face, int totalTicks, int elapsed) {}

    private static final Map<UUID, BreakJob> BREAKS = new ConcurrentHashMap<>();

    /**
     * Start mining: compute the time from vanilla's own formula, then drive the whole
     * thing ourselves.
     *
     * <p>{@code handleBlockBreakAction} cannot be relied on for a clientless player: every
     * guard passes and the call returns, yet the block is never removed - the destruction
     * half of that path is bound up with the client acknowledgement we never send. So the
     * timing is reproduced here (same speed / hardness / harvest divisors vanilla uses)
     * and the actual removal still goes through {@code gameMode.destroyBlock}, which is
     * what applies drops, tool rules and neighbour updates.</p>
     */
    public static String startBreak(ServerPlayer player, BlockPos pos, Direction face) {
        if (!withinReach(player, pos)) {
            return "too far away: " + round(Vec3.atCenterOf(pos).distanceTo(player.position()));
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return "that position is already air";
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return "that block is unbreakable";
        }
        // Ask the block itself rather than re-deriving the divisor. Vanilla's
        // getDestroyProgress(Player, BlockGetter, BlockPos) already folds in the tool
        // speed, the block hardness and the 30/100 correct-tool divisor - and whatever
        // mods do to tools along the way. Hand-rolling that gave a stone sword "correct
        // tool" on a log, which made the break three times too fast.
        float perTick = state.getDestroyProgress(player, level, pos);
        if (perTick <= 0.0F) {
            return "that block cannot be mined by this player";
        }
        int totalTicks = Math.max(1, (int) Math.ceil(1.0F / perTick));

        // Face the block while mining.
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.yBodyRot = yaw;

        BREAKS.put(player.getUUID(), new BreakJob(pos.immutable(), face, totalTicks, 0));
        player.swing(InteractionHand.MAIN_HAND);
        return "mining " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + " (est " + totalTicks + " ticks, hardness " + round(hardness)
                + ", per tick " + String.format(java.util.Locale.ROOT, "%.4f", perTick)
                + ", tool " + (state.requiresCorrectToolForDrops()
                        ? (player.getMainHandItem().isCorrectToolForDrops(state) ? "right" : "wrong")
                        : "not required") + ")";
    }


    public static void cancelBreak(ServerPlayer player) {
        BreakJob job = BREAKS.remove(player.getUUID());
        if (job != null) {
            ((ServerLevel) player.level()).destroyBlockProgress(player.getId(), job.pos(), -1);
        }
    }

    /** Advance every pending break by one tick. Called from the server tick hook. */
    public static void tickBreaks(MinecraftServer server) {
        if (BREAKS.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, BreakJob> entry : new java.util.ArrayList<>(BREAKS.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            BreakJob job = entry.getValue();
            if (player == null || player.isRemoved()) {
                BREAKS.remove(entry.getKey());
                continue;
            }
            ServerLevel level = (ServerLevel) player.level();
            if (!withinReach(player, job.pos())) {
                // Walked away mid-break: stop it, exactly as a client would when it
                // sends ABORT, instead of destroying a block from across the map.
                BREAKS.remove(entry.getKey());
                level.destroyBlockProgress(player.getId(), job.pos(), -1);
                continue;
            }
            BlockState state = level.getBlockState(job.pos());
            if (state.isAir()) {
                BREAKS.remove(entry.getKey());
                level.destroyBlockProgress(player.getId(), job.pos(), -1);
                continue;
            }
            int elapsed = job.elapsed() + 1;
            // Crack overlay, same packet a real client would drive.
            int stage = Math.min(9, elapsed * 10 / Math.max(1, job.totalTicks()));
            level.destroyBlockProgress(player.getId(), job.pos(), stage);

            if (elapsed >= job.totalTicks()) {
                boolean removed = player.gameMode.destroyBlock(job.pos());
                level.destroyBlockProgress(player.getId(), job.pos(), -1);
                BREAKS.remove(entry.getKey());
                if (!removed) {
                    dev.mcagent.McAgent.LOG.warn("[mcagent] destroyBlock refused at {}", job.pos());
                }
            } else {
                BREAKS.put(entry.getKey(), new BreakJob(job.pos(), job.face(), job.totalTicks(), elapsed));
            }
        }
    }


    /** Abort an in-progress break (walking away does this too). */

    /**
     * Right-click a block face with the held item. Placement, doors, chests, levers - all of
     * it goes through the vanilla interaction code, including any mod overrides.
     */
    public static String useOn(ServerPlayer player, BlockPos pos, Direction face) {
        if (!withinReach(player, pos)) {
            return "too far away from " + pos;
        }
        ServerLevel level = (ServerLevel) player.level();
        Vec3 hit = Vec3.atCenterOf(pos).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockHitResult result = new BlockHitResult(hit, face, pos, false);
        ItemStack stack = player.getMainHandItem();
        player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, result);
        player.swing(InteractionHand.MAIN_HAND);
        String what = stack.isEmpty() ? "empty hand" : stack.getItem().toString();
        return "used " + what + " on " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + " (" + face.getName() + ")";
    }

    /** Right-click in the air: eat, throw, cast, whatever the held item does. */
    public static String use(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return "nothing in hand";
        }
        player.gameMode.useItem(player, (ServerLevel) player.level(), stack, InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
        return "used " + stack.getItem();
    }

    /** Hit an entity. {@code id} may be omitted to pick the nearest living thing in range. */
    public static String attack(ServerPlayer player, int entityId, double radius) {
        ServerLevel level = (ServerLevel) player.level();
        Entity target;
        if (entityId >= 0) {
            target = level.getEntity(entityId);
            if (target == null) {
                return "no entity with id " + entityId;
            }
        } else {
            AABB box = player.getBoundingBox().inflate(radius);
            LivingEntity nearest = null;
            double best = Double.MAX_VALUE;
            // Never auto-target a player. Without this the agent swings at whoever
            // happens to stand closer than the intended mob - which is exactly what
            // happened the first time this ran.
            for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, box,
                    e -> e != player && !(e instanceof net.minecraft.world.entity.player.Player))) {
                double d = candidate.distanceToSqr(player);
                if (d < best) {
                    best = d;
                    nearest = candidate;
                }
            }
            if (nearest == null) {
                return "nothing to hit within " + radius + " blocks";
            }
            target = nearest;
        }
        if (target.distanceTo(player) > REACH) {
            return "target out of reach: " + round(target.distanceTo(player));
        }
        player.attack(target);
        player.swing(InteractionHand.MAIN_HAND);
        String detail = target instanceof LivingEntity living
                ? " (health " + round(living.getHealth()) + ")"
                : "";
        return "hit " + target.getName().getString() + detail;
    }

    /** True when the held item can be placed as a block. */

    private static boolean withinReach(ServerPlayer player, BlockPos pos) {
        return Vec3.atCenterOf(pos).distanceTo(player.position()) <= REACH;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
