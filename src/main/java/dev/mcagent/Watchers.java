package dev.mcagent;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Watches block entities and pushes a WebSocket event when their NBT changes.
 *
 * <p>This is the difference between "poll the machine every few seconds and hope
 * you catch the error" and "get told the moment the state machine moves". The
 * comparison key is the serialized NBT, so any change - progress, contents,
 * status, error message - trips it.</p>
 */
public final class Watchers {
    private static final int SCAN_INTERVAL_TICKS = 20;

    private static final class Watch {
        final ServerLevel level;
        final BlockPos pos;
        final String label;
        final long expireTick;
        String lastKey;
        long lastChangeTick;

        Watch(ServerLevel level, BlockPos pos, String label, long expireTick, String lastKey, long lastChangeTick) {
            this.level = level;
            this.pos = pos;
            this.label = label;
            this.expireTick = expireTick;
            this.lastKey = lastKey;
            this.lastChangeTick = lastChangeTick;
        }
    }

    private static final List<Watch> ACTIVE = new ArrayList<>();

    private Watchers() {}

    /** Registers a watcher; returns a human readable confirmation. */
    public static synchronized String add(ServerLevel level, BlockPos pos, String label, int seconds) {
        long now = level.getGameTime();
        String key = keyOf(level, pos);
        ACTIVE.removeIf(w -> w.level == level && w.pos.equals(pos) && w.label.equals(label));
        ACTIVE.add(new Watch(level, pos, label, now + seconds * 20L, key, now));
        return "watching " + label + " at " + fmt(pos) + " for " + seconds + "s (" + ACTIVE.size() + " active)";
    }

    public static synchronized List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Watch w : ACTIVE) {
            out.add(w.label + " @ " + fmt(w.pos) + " (" + w.level.dimension().location() + ")");
        }
        return out;
    }

    public static synchronized void clear() {
        ACTIVE.clear();
    }

    /** Called every server tick; does real work only on the scan interval. */
    public static synchronized void tick(net.minecraft.server.MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        long gameTime = server.getTickCount();
        if (gameTime % SCAN_INTERVAL_TICKS != 0) return;
        ACTIVE.removeIf(w -> w.level.getGameTime() > w.expireTick);

        for (Watch w : ACTIVE) {
            if (!w.level.isLoaded(w.pos)) continue;
            String now = keyOf(w.level, w.pos);
            if (now.equals(w.lastKey)) continue;
            String previous = w.lastKey;
            w.lastKey = now;
            long tick = w.level.getGameTime();
            long delta = tick - w.lastChangeTick;
            w.lastChangeTick = tick;

            JsonObject data = Json.obj();
            data.addProperty("label", w.label);
            data.addProperty("pos", fmt(w.pos));
            data.addProperty("dimension", w.level.dimension().location().toString());
            data.addProperty("tick", tick);
            data.addProperty("sinceLastChangeTicks", delta);
            data.addProperty("status", extractStatus(w.level, w.pos));
            data.addProperty("removed", !w.label.isEmpty() && w.level.getBlockEntity(w.pos) == null);
            data.addProperty("before", shorten(previous));
            data.addProperty("after", shorten(now));
            McAgent.WS.pushTopic("blocks", "watcher", data);
        }
    }

    /** Pulls the interesting bits out of common machine NBT for a readable event. */
    private static String extractStatus(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return "no block entity";
        CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
        if (tag.contains("status")) return tag.getString("status");
        return be.getType().toString();
    }

    private static String keyOf(ServerLevel level, BlockPos pos) {
        // Never touch an unloaded chunk from the tick loop: getBlockState() there
        // forces a synchronous chunk load, which blocks the server thread waiting
        // on a CompletableFuture.join() and deadlocked the game once already.
        if (!level.isLoaded(pos)) return "unloaded";
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return "none:" + level.getBlockState(pos);
        }
        String snbt = be.saveWithFullMetadata(level.registryAccess()).toString();
        // Length plus hash is enough to notice a change without holding two full
        // snapshots per watcher in memory.
        return snbt.length() + ":" + snbt.hashCode();
    }

    private static String shorten(String key) {
        return key == null ? "" : (key.length() > 120 ? key.substring(0, 120) + "…" : key);
    }

    private static String fmt(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
