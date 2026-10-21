package dev.mcagent.fakeplayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * Small things a body does when it has nothing to do.
 *
 * <p>Without this the agent stands perfectly still - same yaw, same pitch, no gesture -
 * which reads as a prop rather than as someone standing there. People fidget: they glance
 * around, they notice you are there and look at you, they shift their weight. None of it
 * is useful, which is the point.</p>
 *
 * <p>Idle work only runs when the body is genuinely idle. A walk, a break or an attack
 * always wins, and an interrupted gesture is released rather than frozen midway.</p>
 */
public final class AgentIdle {

    /** Gap between two gestures, in ticks. Calm, but not statuesque. */
    private static final int MIN_GAP = 100;
    private static final int MAX_GAP = 280;

    /** Degrees the head may turn per tick while drifting toward a new angle. */
    private static final float TURN_PER_TICK = 7.0F;

    /** How close a human has to be before she notices them. */
    private static final double NOTICE_RANGE = 10.0;

    private enum Kind { LOOK_PLAYER, LOOK_AROUND, SNEAK, SWING, HOP }

    private static final class State {
        int nextIn;
        Kind kind = Kind.LOOK_AROUND;
        int ticksLeft;
        float targetYaw;
        float targetPitch;
        boolean started;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private AgentIdle() {}

    /** Called once per server tick, after movement has had its say. */
    public static void tick(MinecraftServer server) {
        for (ServerPlayer player : FakePlayers.all()) {
            if (player.level().getServer() != server) {
                continue;
            }
            if (busy(player)) {
                release(player);
                STATES.remove(player.getUUID());
                continue;
            }
            State state = STATES.computeIfAbsent(player.getUUID(), id -> new State());
            if (state.ticksLeft > 0) {
                step(player, state);
                continue;
            }
            if (--state.nextIn > 0) {
                player.setJumping(false);
                continue;
            }
            begin(server, player, state);
        }
    }

    /** Anything the agent was actually asked to do wins over idling. */
    private static boolean busy(ServerPlayer player) {
        return AgentMovement.isWalking(player)
                || AgentActions.isBreaking(player)
                || player.isUsingItem();
    }

    private static void begin(MinecraftServer server, ServerPlayer player, State state) {
        state.nextIn = MIN_GAP + (int) (Math.random() * (MAX_GAP - MIN_GAP));
        state.started = false;

        ServerPlayer watcher = nearestHuman(server, player);
        if (watcher != null && Math.random() < 0.55) {
            state.kind = Kind.LOOK_PLAYER;
            Vec3 eye = player.getEyePosition();
            Vec3 target = watcher.position().add(0.0, watcher.getEyeHeight() * 0.9, 0.0);
            Vec3 d = target.subtract(eye);
            state.targetYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            state.targetPitch = (float) -Math.toDegrees(
                    Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            state.ticksLeft = 26;
            return;
        }

        double roll = Math.random();
        if (roll < 0.45) {
            state.kind = Kind.LOOK_AROUND;
            state.targetYaw = player.getYRot() + (float) (Math.random() * 120.0 - 60.0);
            state.targetPitch = (float) (Math.random() * 24.0 - 8.0);
            state.ticksLeft = 30;
        } else if (roll < 0.62) {
            state.kind = Kind.SWING;
            state.ticksLeft = 22;
        } else if (roll < 0.80) {
            state.kind = Kind.SNEAK;
            state.ticksLeft = 26;
        } else {
            state.kind = Kind.HOP;
            state.ticksLeft = 12;
        }
    }

    private static void step(ServerPlayer player, State state) {
        state.ticksLeft--;
        switch (state.kind) {
            case LOOK_PLAYER, LOOK_AROUND -> {
                // Turn the whole body, not just the neck: body rotation is what clients
                // actually receive, so this is the part that reads on screen.
                float yaw = approach(player.getYRot(), state.targetYaw, TURN_PER_TICK);
                player.setYRot(yaw);
                player.yBodyRot = yaw;
                player.setYHeadRot(yaw);
                player.setXRot(approach(player.getXRot(), state.targetPitch, TURN_PER_TICK));
                if (state.kind == Kind.LOOK_PLAYER && state.ticksLeft == 12) {
                    state.targetPitch = Math.min(state.targetPitch + 8.0F, 20.0F);
                }
                if (state.ticksLeft == 0) {
                    player.setXRot(0.0F);
                }
            }
            case SNEAK -> player.setShiftKeyDown(true);
            case SWING -> {
                if (!state.started) {
                    state.started = true;
                    player.swing(InteractionHand.MAIN_HAND, true);
                } else if (state.ticksLeft == 11) {
                    player.swing(InteractionHand.OFF_HAND, true);
                }
            }
            case HOP -> {
                if (player.onGround()) {
                    player.setJumping(state.ticksLeft > 6);
                }
            }
        }
        if (state.ticksLeft == 0) {
            release(player);
        }
    }

    private static void release(ServerPlayer player) {
        player.setShiftKeyDown(false);
        player.setJumping(false);
        player.setXRot(0.0F);
    }

    private static float approach(float from, float to, float step) {
        float delta = wrap(to - from);
        if (Math.abs(delta) <= step) {
            return to;
        }
        return from + Math.signum(delta) * step;
    }

    /** Shortest signed angle, so a glance never spins the long way round. */
    private static float wrap(float degrees) {
        float d = degrees;
        while (d > 180.0F) {
            d -= 360.0F;
        }
        while (d < -180.0F) {
            d += 360.0F;
        }
        return d;
    }

    private static ServerPlayer nearestHuman(MinecraftServer server, ServerPlayer self) {
        ServerPlayer best = null;
        double bestDist = NOTICE_RANGE * NOTICE_RANGE;
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other == self || FakePlayers.names().contains(other.getGameProfile().getName())) {
                continue;
            }
            if (other.level() != self.level()) {
                continue;
            }
            double d = other.distanceToSqr(self);
            if (d < bestDist) {
                bestDist = d;
                best = other;
            }
        }
        return best;
    }
}
