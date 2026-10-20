package dev.mcagent.fakeplayer;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent-owned players: real {@code ServerPlayer} instances with no client attached.
 *
 * <p>They are registered through {@code PlayerList.placeNewPlayer}, so from the server's
 * point of view they are ordinary players - they appear in the tab list, hold an
 * inventory, take damage, break blocks under normal tool rules and keep the chunks
 * around them loaded. The only thing missing is a renderer.</p>
 *
 * <p>The UUID is derived from the name the same way the vanilla server does it for
 * offline players, so the same name always maps to the same player data file.</p>
 */
public final class FakePlayers {

    private static final Map<String, ServerPlayer> LIVE = new LinkedHashMap<>();

    private FakePlayers() {}

    public static GameProfile profileOf(String name) {
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return new GameProfile(id, name);
    }

    /**
     * {@code ClientInformation.createDefault()} sets modelCustomisation to 0, which hides
     * every outer model part - jacket, sleeves, pants and hat. The second layer of a skin
     * lives exactly there, so a fake player built from the default renders only the base
     * layer and looks like a different skin. 0x7F is "all parts visible".
     */
    private static ClientInformation agentClientInformation() {
        return new ClientInformation(
                "en_us",
                2,
                net.minecraft.world.entity.player.ChatVisiblity.FULL,
                true,
                0x7F,
                net.minecraft.world.entity.HumanoidArm.RIGHT,
                false,
                false);
    }

    public static synchronized ServerPlayer spawn(MinecraftServer server, ServerLevel level, String name,
                                                  double x, double y, double z, float yaw, float pitch) throws Exception {
        if (LIVE.containsKey(name)) {
            throw new IllegalStateException("agent player '" + name + "' is already spawned");
        }
        GameProfile profile = profileOf(name);
        if (server.getPlayerList().getPlayer(profile.getId()) != null) {
            throw new IllegalStateException("a player with name '" + name + "' is already online");
        }

        ServerPlayer player = new ServerPlayer(server, level, profile, agentClientInformation());
        FakeConnection connection = new FakeConnection();
        copyNegotiatedChannels(anchorConnection(server), connection);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        // player.connection has to be non-null before placeNewPlayer, which reaches for it.
        player.connection = new ServerGamePacketListenerImpl(server, connection, player, cookie);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        teleport(player, level, x, y, z, yaw, pitch);
        LIVE.put(name, player);
        return player;
    }

    /**
     * Move a fake player and clear the "waiting for the client to accept the teleport"
     * state that {@code ServerPlayer.teleportTo} leaves behind.
     *
     * <p>Without the clear, {@code ServerGamePacketListenerImpl.tick()} calls
     * {@code resetPosition()} every tick and snaps the player back to that pending
     * position, which also cancels any block mining in progress. A real client answers
     * with an accept-teleport packet; a fake one never does, so the state has to be
     * cleared by hand.</p>
     */
    public static void teleport(ServerPlayer player, ServerLevel level,
                                double x, double y, double z, float yaw, float pitch) {
        player.teleportTo(level, x, y, z, yaw, pitch);
        clearAwaitingTeleport(player.connection);
    }

    private static void clearAwaitingTeleport(ServerGamePacketListenerImpl listener) {
        if (listener == null) {
            return;
        }
        try {
            java.lang.reflect.Field pending = ServerGamePacketListenerImpl.class
                    .getDeclaredField("awaitingPositionFromClient");
            pending.setAccessible(true);
            pending.set(listener, null);
        } catch (Throwable t) {
            dev.mcagent.McAgent.LOG.warn("[mcagent] could not clear the pending teleport", t);
        }
    }

    public static synchronized boolean despawn(MinecraftServer server, String name) {
        ServerPlayer player = LIVE.remove(name);
        if (player == null) {
            return false;
        }
        AgentMovement.stop(player);
        AgentActions.cancelBreak(player);
        server.getPlayerList().remove(player);
        return true;
    }

    public static synchronized void despawnAll(MinecraftServer server) {
        for (String name : new ArrayList<>(LIVE.keySet())) {
            despawn(server, name);
        }
    }

    public static synchronized List<String> names() {
        return new ArrayList<>(LIVE.keySet());
    }

    public static synchronized ServerPlayer byName(String name) {
        return LIVE.get(name);
    }

    /** The spawned player closest to a point. */
    public static synchronized ServerPlayer nearest(net.minecraft.world.phys.Vec3 around) {
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer p : LIVE.values()) {
            double d = p.position().distanceToSqr(around);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** The real player's connection, the only one that has actually negotiated channels. */
    private static Connection anchorConnection(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!LIVE.containsKey(p.getGameProfile().getName()) && p.connection != null) {
                return ((ICommonPacketListener) p.connection).getConnection();
            }
        }
        return null;
    }

    /**
     * Copy the negotiated channel state from a real client onto the fake connection.
     *
     * <p>Kept even though {@code checkPacket} is already bypassed for fake connections:
     * other NeoForge paths consult the same data, and mirroring it costs one assignment.</p>
     */
    private static void copyNegotiatedChannels(Connection real, FakeConnection fake) {
        if (real == null) {
            return;
        }
        try {
            net.neoforged.neoforge.network.registration.NetworkPayloadSetup setup =
                    ChannelAttributes.getPayloadSetup(real);
            if (setup != null) {
                ChannelAttributes.setPayloadSetup(fake, setup);
            }
        } catch (Throwable t) {
            dev.mcagent.McAgent.LOG.warn("[mcagent] could not mirror the payload setup", t);
        }
        for (ConnectionProtocol protocol : ConnectionProtocol.values()) {
            try {
                java.util.Set<net.minecraft.resources.ResourceLocation> theirs =
                        ChannelAttributes.getOrCreateCommonChannels(real, protocol);
                if (theirs.isEmpty()) {
                    continue;
                }
                ChannelAttributes.getOrCreateCommonChannels(fake, protocol).addAll(theirs);
            } catch (Throwable t) {
                dev.mcagent.McAgent.LOG.warn("[mcagent] could not mirror channels for {}", protocol, t);
            }
        }
    }
}
