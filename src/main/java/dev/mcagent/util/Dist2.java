package dev.mcagent.util;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/** Small environment helpers, kept out of {@link dev.mcagent.GameBridge} so they stay testable. */
public final class Dist2 {
    private Dist2() {}

    public static Dist dist() {
        return FMLEnvironment.dist;
    }

    public static boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    public static String string() {
        return FMLEnvironment.dist == null ? "unknown" : FMLEnvironment.dist.name().toLowerCase();
    }

    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static Path logFile() {
        return gameDir().resolve("logs").resolve("latest.log");
    }
}
