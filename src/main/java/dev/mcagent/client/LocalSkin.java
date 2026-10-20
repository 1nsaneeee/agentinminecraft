package dev.mcagent.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import dev.mcagent.AgentConfig;
import dev.mcagent.McAgent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Loads a skin from disk and hands it to the client for the local player.
 *
 * <p>Configured with {@code skinFile} in {@code config/mcagent.json}. A relative path is
 * resolved against the game directory. The file is re-read when its modification time
 * changes, so dropping in a new png and rejoining the world is enough - no restart.</p>
 *
 * <p>Everything here is best-effort: a missing file, a wrong size or a broken png logs
 * once and leaves the player with their normal skin. A skin-mod bug must never stop the
 * game from running.</p>
 */
public final class LocalSkin {

    private static final java.util.Set<java.util.UUID> DIAGNOSED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static PlayerSkin cached;
    private static long cachedMtime = Long.MIN_VALUE;
    private static String lastError;

    private LocalSkin() {}

    /** Called from the mixin for every player; returns non-null only for the local one. */
    public static PlayerSkin forProfile(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        try {
            // One-shot diagnostic: show what the client actually holds, and what the mod
            // expects, so a mismatch is visible instead of silent.
            if (DIAGNOSED.size() < 24 && DIAGNOSED.add(profile.getId())) {
                String want = AgentConfig.get().resolvedPlayerName();
                java.util.UUID expect = java.util.UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + want).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                dev.mcagent.McAgent.LOG.info(
                        "[mcagent] skin lookup: profile name={} uuid={} | configured={} expected={} match={}",
                        profile.getName(), profile.getId(), want, expect, expect.equals(profile.getId()));
            }
            String configured = AgentConfig.get().skinFile;
            if (configured == null || configured.isBlank()) {
                return null;
            }
            // The skin belongs to the AGENT's player, not to the human at the keyboard.
            // The server spawns that player with an offline UUID derived from its name,
            // so the client can compute the same UUID and match without any handshake.
            // skinPlayer is a comma-separated list, because the agent may wear more than
            // one body over its life (it renamed itself from Deepseek to Dafeiyu once) and
            // a skin bound to a single name silently stops applying when that happens.
            String configuredPlayers = AgentConfig.get().skinPlayer;
            if (configuredPlayers == null || configuredPlayers.isBlank()) {
                configuredPlayers = AgentConfig.get().resolvedPlayerName();
            }
            for (String raw : configuredPlayers.split(",")) {
                String playerName = raw.trim();
                if (playerName.isEmpty()) {
                    continue;
                }
                UUID target = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (target.equals(profile.getId())) {
                    return current(configured);
                }
            }
            return null;
        } catch (Throwable t) {
            report("lookup failed: " + t);
            return null;
        }
    }

    private static PlayerSkin current(String configured) {
        Path file = Path.of(configured);
        if (!file.isAbsolute()) {
            file = FMLPaths.GAMEDIR.get().resolve(configured);
        }
        if (!Files.isRegularFile(file)) {
            report("no skin at " + file);
            return null;
        }
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            report("cannot stat " + file + ": " + e);
            return null;
        }
        if (cached != null && mtime == cachedMtime) {
            return cached;
        }

        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            if (image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
                int w = image.getWidth();
                int h = image.getHeight();
                image.close();
                report("expected a 64x64 skin, got " + w + "x" + h);
                return null;
            }
            PlayerSkin.Model model = detectModel(image);
            Minecraft minecraft = Minecraft.getInstance();
            ResourceLocation texture = minecraft.getTextureManager()
                    .register("mcagent_local_skin",
                            new net.minecraft.client.renderer.texture.DynamicTexture(image));
            cached = new PlayerSkin(texture, null, null, null, model, false);
            cachedMtime = mtime;
            lastError = null;
            McAgent.LOG.info("[mcagent] local skin loaded from {} ({})", file, model.id());
            return cached;
        } catch (Throwable t) {
            report("cannot read " + file + ": " + t);
            return null;
        }
    }

    /**
     * Decide wide vs slim by asking which model would sample fewer empty pixels.
     *
     * <p>The obvious heuristic - "is the outer arm column transparent?" - fails on skins
     * that paint extra columns and leave gaps elsewhere (this one does: the site labels
     * it Alex, yet the outer column is painted). Counting holes under each hypothesis
     * is robust to that: a wide arm samples 4 columns per face, a slim arm samples 3, so
     * whichever hypothesis leaves fewer transparent pixels is the one the author drew
     * for.</p>
     */
    private static PlayerSkin.Model detectModel(NativeImage image) {
        String forced = AgentConfig.get().skinModel;
        if ("slim".equalsIgnoreCase(forced)) {
            return PlayerSkin.Model.SLIM;
        }
        if ("wide".equalsIgnoreCase(forced)) {
            return PlayerSkin.Model.WIDE;
        }
        if (image.getHeight() < 64) {
            return PlayerSkin.Model.WIDE;   // 64x32 has no second layer and always was wide
        }
        int wideMissing = missingUnder(image, 4);
        int slimMissing = missingUnder(image, 3);
        return slimMissing < wideMissing ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
    }

    /** Transparent pixels the given arm width would sample. Lower is better. */
    private static int missingUnder(NativeImage image, int columns) {
        int missing = 0;
        int[] starts = {40, 44, 48, 52};          // right arm: right, front, left, back
        for (int start : starts) {
            for (int x = start; x < start + columns; x++) {
                for (int y = 20; y < 32; y++) {
                    if (((image.getPixelRGBA(x, y) >>> 24) & 0xFF) == 0) {
                        missing++;
                    }
                }
            }
        }
        for (int y = 16; y < 20; y++) {           // arm top and bottom
            for (int start : new int[]{44, 48}) {
                for (int x = start; x < start + columns; x++) {
                    if (((image.getPixelRGBA(x, y) >>> 24) & 0xFF) == 0) {
                        missing++;
                    }
                }
            }
        }
        return missing;
    }

    private static void report(String message) {
        if (!message.equals(lastError)) {
            lastError = message;
            McAgent.LOG.warn("[mcagent] local skin: {}", message);
        }
    }
}
