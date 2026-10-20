package dev.mcagent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent box outlines.
 *
 * <p>A single particle burst dies in about a second, so "highlight for N
 * seconds" only works if the server re-emits the outline on a timer. Entries
 * live on the game thread and are pruned when they expire.</p>
 */
public final class Highlights {
    /** Re-emit this often; comfortably shorter than an END_ROD particle lifetime. */
    private static final long EMIT_INTERVAL_TICKS = 8;

    private static final class Box {
        final ServerLevel level;
        final BlockPos a;
        final BlockPos b;
        final long expireTick;
        long nextEmitTick;

        Box(ServerLevel level, BlockPos a, BlockPos b, long expireTick, long nextEmitTick) {
            this.level = level;
            this.a = a;
            this.b = b;
            this.expireTick = expireTick;
            this.nextEmitTick = nextEmitTick;
        }
    }

    private static final List<Box> ACTIVE = new ArrayList<>();

    private Highlights() {}

    public static synchronized int add(ServerLevel level, BlockPos a, BlockPos b, int seconds) {
        long now = level.getGameTime();
        ACTIVE.add(new Box(level, a, b, now + seconds * 20L, now));
        return draw(level, a, b);
    }

    public static synchronized int active() {
        return ACTIVE.size();
    }

    /** Called from the server tick handler; removes expired outlines. */
    public static synchronized void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        ACTIVE.removeIf(box -> box.level.getGameTime() > box.expireTick);
        for (Box box : ACTIVE) {
            long now = box.level.getGameTime();
            if (now < box.nextEmitTick) continue;
            box.nextEmitTick = now + EMIT_INTERVAL_TICKS;
            draw(box.level, box.a, box.b);
        }
    }

    public static synchronized void clear() {
        ACTIVE.clear();
    }

    /** Particle outline of an inclusive box; returns how many particles were emitted. */
    static int draw(ServerLevel level, BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX()) + 1;
        int maxY = Math.max(a.getY(), b.getY()) + 1;
        int maxZ = Math.max(a.getZ(), b.getZ()) + 1;

        // Smaller boxes get a denser outline so a single block still reads as a
        // crisp cube instead of four dots.
        long volume = (long) (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        double step = volume <= 8 ? 0.25 : 0.5;

        int drawn = 0;
        for (double x = minX; x <= maxX; x += step) {
            drawn += dot(level, x, minY, minZ);
            drawn += dot(level, x, minY, maxZ);
            drawn += dot(level, x, maxY, minZ);
            drawn += dot(level, x, maxY, maxZ);
        }
        for (double y = minY; y <= maxY; y += step) {
            drawn += dot(level, minX, y, minZ);
            drawn += dot(level, minX, y, maxZ);
            drawn += dot(level, maxX, y, minZ);
            drawn += dot(level, maxX, y, maxZ);
        }
        for (double z = minZ; z <= maxZ; z += step) {
            drawn += dot(level, minX, minY, z);
            drawn += dot(level, maxX, minY, z);
            drawn += dot(level, minX, maxY, z);
            drawn += dot(level, maxX, maxY, z);
        }
        return drawn;
    }

    private static int dot(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.END_ROD, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        return 1;
    }
}
