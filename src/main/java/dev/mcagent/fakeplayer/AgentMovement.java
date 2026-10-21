package dev.mcagent.fakeplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Walking, done properly: set the movement input, let the game's physics run, and follow a
 * path instead of pushing at whatever is in front.
 *
 * <p>A server player normally has a client that sends a position packet every tick. The
 * agent's player has no client, so nothing ever drives its input - which is why the first
 * version could only teleport. But {@code ServerPlayer} still runs the full
 * {@code aiStep -> travel} pipeline every tick, and that pipeline reads {@code xxa}
 * (strafe), {@code zza} (forward) and the jumping flag. Feeding those three values makes
 * the player walk with collision, gravity, step-up and fall damage handled by the same
 * code a real client would trigger.</p>
 *
 * <p>What those three values cannot supply is judgement. Reflexes ("jump when the body
 * touches a wall") walk into pits; the way across a two block gap is to leave the ground
 * before the edge. So each walk first asks {@link AgentNav} for a route over standing
 * cells, then follows it hop by hop, and only falls back to steering at the raw target
 * when no route is found.</p>
 */
public final class AgentMovement {

    private static final int TIMEOUT_TICKS = 2400;
    private static final double ARRIVE_DISTANCE = 0.7;
    private static final double HOP_REACHED = 0.85;
    private static final int STUCK_TICKS = 30;
    private static final int MAX_REPATHS = 3;

    /** Mutable walk state: the route, how far along it we are, and the stuck detector. */
    private static final class Walk {
        final Vec3 target;
        final boolean sprint;
        final int startedAt;
        List<AgentNav.Hop> hops;
        int index;
        int repaths;
        int still;
        double lastX;
        double lastZ;
        int jumpTicks;

        Walk(Vec3 target, boolean sprint, int startedAt, List<AgentNav.Hop> hops) {
            this.target = target;
            this.sprint = sprint;
            this.startedAt = startedAt;
            this.hops = hops;
            this.lastX = Double.NaN;
            this.lastZ = Double.NaN;
        }
    }

    private static final Map<UUID, Walk> WALKS = new ConcurrentHashMap<>();
    private static final Set<UUID> TICK_WARNED = new HashSet<>();

    private AgentMovement() {}

    public static void start(ServerPlayer player, Vec3 target, boolean sprint, int now) {
        WALKS.put(player.getUUID(), new Walk(target, sprint, now, plan(player, target)));
    }

    private static List<AgentNav.Hop> plan(ServerPlayer player, Vec3 target) {
        try {
            return AgentNav.plan(player, target);
        } catch (Throwable t) {
            dev.mcagent.McAgent.LOG.warn("[mcagent] path planning failed", t);
            return null;
        }
    }

    public static boolean stop(ServerPlayer player) {
        Walk walk = WALKS.remove(player.getUUID());
        if (walk == null) {
            return false;
        }
        release(player);
        return true;
    }

    public static boolean isWalking(ServerPlayer player) {
        return WALKS.containsKey(player.getUUID());
    }

    /** What the route looks like, for the tool result - evidence that a plan was found. */
    public static String planSummary(ServerPlayer player) {
        Walk walk = WALKS.get(player.getUUID());
        if (walk == null) {
            return "no walk";
        }
        if (walk.hops == null) {
            return "no route found, steering straight";
        }
        long jumps = walk.hops.stream().filter(AgentNav.Hop::jump).count();
        return walk.hops.size() + " hops, " + jumps + " jump(s)";
    }

    public static Vec3 targetOf(ServerPlayer player) {
        Walk walk = WALKS.get(player.getUUID());
        return walk == null ? null : walk.target;
    }

    /**
     * Drive the agent's players from the server tick.
     *
     * The world already calls ServerPlayer.tick(), including gameMode.tick().
     * Only the client connection's doTick() is missing: it drives Player.tick()
     * and therefore physics, movement and gravity.
     */
    private static void drivePlayerTicks(MinecraftServer server) {
        for (String name : FakePlayers.names()) {
            ServerPlayer player = FakePlayers.byName(name);
            if (player == null || player.isRemoved()) {
                continue;
            }
            if (TICK_WARNED.add(player.getUUID())) {
                dev.mcagent.McAgent.LOG.info(
                        "[mcagent] driving doTick for {} from the server tick hook", name);
            }
            player.doTick();
        }
    }

    /** Called once per server tick, after the worlds have ticked. */
    public static void tick(MinecraftServer server) {
        drivePlayerTicks(server);
        AgentActions.tickBreaks(server);
        if (WALKS.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        Iterator<Map.Entry<UUID, Walk>> it = WALKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Walk> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (!drive(player, entry.getValue(), now)) {
                it.remove();
            }
        }
    }

    /** @return false when this walk is finished and should be dropped. */
    private static boolean drive(ServerPlayer player, Walk walk, int now) {
        if (now - walk.startedAt > TIMEOUT_TICKS) {
            release(player);
            return false;
        }
        if (walk.still > STUCK_TICKS) {
            walk.still = 0;
            if (walk.repaths++ < MAX_REPATHS) {
                walk.hops = plan(player, walk.target);
                walk.index = 0;
            } else {
                walk.hops = null;      // give up on the route, steer at the target
            }
        }

        if (walk.hops != null) {
            if (walk.index >= walk.hops.size()) {
                release(player);
                return false;
            }
            return followHop(player, walk);
        }
        return steer(player, walk);
    }

    /** Follow the next standing cell of the route. */
    private static boolean followHop(ServerPlayer player, Walk walk) {
        AgentNav.Hop hop = walk.hops.get(walk.index);
        BlockPos stand = hop.stand();
        double tx = stand.getX() + 0.5;
        double tz = stand.getZ() + 0.5;
        double dx = tx - player.getX();
        double dz = tz - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = stand.getY() - player.getY();

        if (horizontal < HOP_REACHED && Math.abs(dy) < 0.9) {
            walk.index++;
            walk.still = 0;
            return true;
        }

        face(player, dx, dz);
        player.xxa = 0.0F;
        player.zza = 1.0F;
        player.setSprinting(walk.sprint || horizontal > 1.5);

        boolean jump = false;
        if (player.isInWater()) {
            // Swimming: the ascend flag is what lifts the body, and it has to stay on for
            // as long as the target is above the feet. Holding it against a wall on land
            // would do nothing; in water it is the difference between swimming and
            // sinking to the bottom and standing there.
            jump = stand.getY() - player.getY() > -0.6;
            walk.jumpTicks = 0;
        } else if (player.onGround()) {
            if (hop.jump()) {
                // Leave the ground while there is still ledge under the feet: a sprint
                // jump started at the edge has no run-up and falls short.
                jump = horizontal < 3.4;
                walk.jumpTicks = jump ? 3 : 0;
            } else if (player.horizontalCollision
                    || (dy > 0.5 && horizontal < 1.6)) {
                // A step the path called a step is a full block: 0.6 of automatic step
                // height is not enough, so jump - and jump before the body is already
                // pressed against the block, or it loses the run-up and stalls there.
                jump = true;
                walk.jumpTicks = 2;
            }
        } else if (walk.jumpTicks > 0) {
            jump = true;                   // keep the flag through the rise
            walk.jumpTicks--;
        }
        player.setJumping(jump);

        // The jumping flag alone does not lift this body: the probe showed jump=true,
        // onGround=true and vy unchanged, tick after tick. Applying the impulse directly
        // is what actually leaves the ground; the flag stays set for water, where it is
        // the ascend control rather than an impulse.
        if (jump && !player.isInWater() && player.onGround()) {
            player.jumpFromGround();
        }

        // One line every five ticks while a step is being attempted: the whole reason the
        // body could not climb was invisible from the outside, and guessing at it cost a
        // restart each time.
        if (dy > 0.5 && !player.isInWater() && (player.tickCount % 5 == 0)) {
            dev.mcagent.McAgent.LOG.info(
                    "[mcagent] step probe: dy={} horiz={} onGround={} collision={} vy={} jump={}",
                    String.format("%.2f", dy), String.format("%.2f", horizontal),
                    player.onGround(), player.horizontalCollision,
                    String.format("%.3f", player.getDeltaMovement().y), jump);
        }

        trackProgress(player, walk);
        return true;
    }

    /** No route: push straight at the target and jump at walls and gaps. */
    private static boolean steer(ServerPlayer player, Walk walk) {
        Vec3 target = walk.target;
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        if (horizontal < ARRIVE_DISTANCE && Math.abs(target.y - player.getY()) < 1.5) {
            release(player);
            return false;
        }

        face(player, dx, dz);
        player.xxa = 0.0F;
        player.zza = 1.0F;
        player.setSprinting(walk.sprint && horizontal > 1.5);

        boolean jump = false;
        if (player.isInWater()) {
            // Same reason as in drive(): in water the ascend flag is the only way up, and
            // without it the body sinks to the bottom and just stands there.
            jump = target.y > player.getY() - 0.6;
        } else if (player.onGround()) {
            if (player.horizontalCollision) {
                jump = true;
            } else if (horizontal > 1.2) {
                ServerLevel level = (ServerLevel) player.level();
                BlockPos probe = BlockPos.containing(
                        player.getX() + (dx / horizontal) * 0.85,
                        player.getY() - 0.1,
                        player.getZ() + (dz / horizontal) * 0.85);
                jump = level.getBlockState(probe).getCollisionShape(level, probe).isEmpty();
            }
        }
        player.setJumping(jump);
        if (jump && !player.isInWater() && player.onGround()) {
            player.jumpFromGround();
        }
        trackProgress(player, walk);
        return true;
    }

    private static void trackProgress(ServerPlayer player, Walk walk) {
        if (!Double.isNaN(walk.lastX)) {
            double moved = Math.abs(player.getX() - walk.lastX) + Math.abs(player.getZ() - walk.lastZ);
            walk.still = moved < 0.02 ? walk.still + 1 : 0;
        }
        walk.lastX = player.getX();
        walk.lastZ = player.getZ();
    }

    private static void face(ServerPlayer player, double dx, double dz) {
        // Minecraft yaw 0 faces +z, so the atan2 is (-dx, dz).
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.yBodyRot = yaw;
    }

    /** Zero the inputs so the player stops instead of coasting on the last tick. */
    private static void release(ServerPlayer player) {
        player.xxa = 0.0F;
        player.zza = 0.0F;
        player.setJumping(false);
        player.setSprinting(false);
    }

    public static void stopAll(MinecraftServer server) {
        for (UUID id : WALKS.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                release(player);
            }
        }
        WALKS.clear();
        TICK_WARNED.clear();
    }
}
