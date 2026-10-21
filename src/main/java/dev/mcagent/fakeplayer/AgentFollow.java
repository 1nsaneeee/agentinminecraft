package dev.mcagent.fakeplayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Tailing another player around the world.
 *
 * <p>A walk is one-shot: it ends on arrival. Following is the other shape of the same
 * thing - the target keeps moving, so the walk has to be restarted whenever the gap grows.
 * Left as a tool call, the agent would have to poll the target's position and re-issue
 * walks itself; that is a tick loop pretending to be a conversation.</p>
 *
 * <p>Two distances, not one: starting again the moment the target shuffles a step would
 * produce a stutter of one-block walks, so it only resumes once the gap is genuinely wide,
 * and it stops well before bumping into them.</p>
 */
public final class AgentFollow {

    /** Close enough - stop, do not crowd. */
    private static final double STOP_DISTANCE = 2.5;
    /** Far enough to be worth walking again. */
    private static final double START_DISTANCE = 4.0;
    /** Sprint once the gap is this wide. */
    private static final double SPRINT_DISTANCE = 7.0;

    private static final Map<UUID, UUID> FOLLOWING = new HashMap<>();

    private AgentFollow() {}

    public static void start(ServerPlayer self, ServerPlayer target) {
        FOLLOWING.put(self.getUUID(), target.getUUID());
    }

    /** @return true if something was actually following. */
    public static boolean stop(ServerPlayer self) {
        return FOLLOWING.remove(self.getUUID()) != null;
    }

    public static boolean isFollowing(ServerPlayer self) {
        return FOLLOWING.containsKey(self.getUUID());
    }

    public static int count() {
        return FOLLOWING.size();
    }

    public static void clearAll() {
        FOLLOWING.clear();
    }

    /** Called once per server tick, alongside the movement tick. */
    public static void tick(MinecraftServer server) {
        if (FOLLOWING.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        Iterator<Map.Entry<UUID, UUID>> it = FOLLOWING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> entry = it.next();
            ServerPlayer self = server.getPlayerList().getPlayer(entry.getKey());
            ServerPlayer target = server.getPlayerList().getPlayer(entry.getValue());
            if (self == null || target == null
                    || self.isRemoved() || target.isRemoved()
                    || self.level() != target.level()) {
                it.remove();
                if (self != null) {
                    AgentMovement.stop(self);
                }
                continue;
            }
            double gap = self.distanceTo(target);
            if (gap <= STOP_DISTANCE) {
                // Arrived: drop the walk but keep following, so the next gap re-plans.
                AgentMovement.stop(self);
                continue;
            }
            if (gap > START_DISTANCE && !AgentMovement.isWalking(self)) {
                Vec3 goal = target.position();
                AgentMovement.start(self, goal, gap > SPRINT_DISTANCE, now);
            }
        }
    }
}
