package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcagent.fakeplayer.FakePlayers;
import dev.mcagent.util.TpsTracker;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything that touches the live integrated server.
 *
 * <p>All public entry points hop onto the server thread and block, so HTTP
 * worker threads can call them safely. The server reference itself is captured
 * from lifecycle events rather than looked up globally, which keeps this mod
 * honest on dedicated servers where those events never fire.</p>
 */
public final class GameBridge {
    private static volatile MinecraftServer server;

    private GameBridge() {}

    public static void attach(MinecraftServer s) {
        server = s;
    }

    public static void detach() {
        server = null;
    }

    public static MinecraftServer server() {
        MinecraftServer s = server;
        if (s == null) throw new IllegalStateException("no server running (singleplayer world not loaded)");
        return s;
    }

    public static boolean running() {
        return server != null;
    }

    /** Runs {@code task} on the server thread and waits for the result. */
    public static <T> T onServer(java.util.function.Supplier<T> task, long timeoutMs) {
        MinecraftServer s = server();
        if (s.isSameThread()) return task.get();
        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        s.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("server thread did not respond within " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the server thread");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(String.valueOf(cause), cause);
        }
    }

    public static ServerPlayer player(String name) {
        MinecraftServer s = server();
        var players = s.getPlayerList().getPlayers();
        if (players.isEmpty()) throw new IllegalStateException("no player is online");
        if (name == null || name.isBlank()) return players.get(0);
        for (ServerPlayer p : players) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name)) return p;
        }
        throw new IllegalArgumentException("no such player online: " + name);
    }

    // ---------------------------------------------------------------- commands

    /**
     * Executes a command as the given player, capturing everything the command
     * sends back through the source.
     */
    public static String runCommand(String command, String playerName, int permission) {
        return onServer(() -> {
            String cleaned = command.startsWith("/") ? command.substring(1) : command;
            ServerPlayer player = player(playerName);
            CommandSourceStack base = player.createCommandSourceStack();
            List<String> lines = new ArrayList<>();
            CommandSource sink = new CaptureSource(lines, base.getLevel());
            CommandSourceStack stack = new CommandSourceStack(
                    sink,
                    base.getPosition(),
                    base.getRotation(),
                    base.getLevel(),
                    permission < 0 ? Math.max(server().getProfilePermissions(player.getGameProfile()), 2) : permission,
                    base.getTextName(),
                    base.getDisplayName(),
                    base.getServer(),
                    base.getEntity());
            server().getCommands().performPrefixedCommand(stack, cleaned);
            if (lines.isEmpty()) return "";
            String joined = String.join("\n", lines);
            int max = AgentConfig.get().maxOutputChars;
            return joined.length() <= max ? joined : joined.substring(0, max) + "\n…[truncated]";
        }, 30000);
    }

    private static final class CaptureSource implements CommandSource {
        private final List<String> lines;
        private final ServerLevel level;

        CaptureSource(List<String> lines, ServerLevel level) {
            this.lines = lines;
            this.level = level;
        }

        @Override
        public void sendSystemMessage(Component message) {
            lines.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    // ------------------------------------------------------------------- chat

    /**
     * Speaks through the agent's own player body, so vanilla renders the
     * {@code <name> } prefix from the sender's profile instead of this mod
     * faking one.
     *
     * <p>Falls back to the old system line when no agent body is in the world:
     * {@code mc_chat} has to keep working even with nothing spawned.</p>
     */
    public static void say(String text) {
        if (text == null || text.isBlank()) return;
        String speaker = AgentConfig.get().resolvedPlayerName();
        onServer(() -> {
            ServerPlayer self = agentPlayer(speaker);
            if (self == null) {
                Component line = formatAgentLine(text);
                for (ServerPlayer p : server().getPlayerList().getPlayers()) {
                    p.sendSystemMessage(line);
                }
                return null;
            }
            broadcastAsChat(self, text);
            return null;
        }, 10000);
        McAgent.WS.pushChat(speaker, text, true);
    }

    /**
     * Same as {@link #say} but takes a JSON text component, so the harness can send
     * things that are clickable. Used by the session list: each entry is a run_command
     * click, which is how Minecraft expects "pick one of these" to work.
     */
    public static void sayComponent(com.google.gson.JsonObject json) {
        if (json == null) return;
        onServer(() -> {
            net.minecraft.network.chat.Component line;
            try {
                line = net.minecraft.network.chat.Component.Serializer.fromJson(json, server().registryAccess());
            } catch (Exception e) {
                McAgent.LOG.warn("[mcagent] rejected an invalid say_json component", e);
                return null;
            }
            if (line == null) return null;
            for (ServerPlayer p : server().getPlayerList().getPlayers()) {
                p.sendSystemMessage(line);
            }
            return null;
        }, 10000);
    }

    public static void tell(String playerName, String text) {
        Component line = formatAgentLine(text);
        onServer(() -> {
            player(playerName).sendSystemMessage(line);
            return null;
        }, 10000);
        McAgent.WS.pushChat(AgentConfig.get().resolvedPlayerName(), text, true);
    }

    /** The agent's own body: the configured name if spawned, else any body. */
    private static ServerPlayer agentPlayer(String preferred) {
        List<String> names = FakePlayers.names();
        if (names.isEmpty()) return null;
        for (String n : names) {
            if (n.equalsIgnoreCase(preferred)) return FakePlayers.byName(n);
        }
        return FakePlayers.byName(names.get(0));
    }

    /**
     * Broadcasts a genuine player chat message from an agent body.
     *
     * <p>Unsigned on purpose: a real client signs with the chat session it
     * negotiated at login, which a {@link dev.mcagent.fakeplayer.FakeConnection}
     * never has. Clients still render unsigned messages - they only lose the
     * "message verified" hover marker.</p>
     *
     * <p>NeoForge may run {@code ServerChatEvent} for this broadcast. The listener
     * skips agent-owned senders, otherwise the line would land in {@link ChatLog}
     * and {@code mc_wait --untilChat} would match the agent's own sentence.</p>
     */
    private static void broadcastAsChat(ServerPlayer self, String text) {
        PlayerChatMessage message = PlayerChatMessage.unsigned(self.getUUID(), text);
        server().getPlayerList().broadcastChatMessage(
                message, self, ChatType.bind(ChatType.CHAT, self));
    }

    /**
     * Fakes a {@code <name> text} line. Only reached by {@link #tell} (a real
     * player chat message cannot be addressed to one player) and by {@link #say}
     * when no agent body is spawned.
     */
    private static Component formatAgentLine(String text) {
        return Component.literal("<" + AgentConfig.get().speakName() + "> ")
                .withStyle(net.minecraft.ChatFormatting.AQUA)
                .append(Component.literal(text));
    }

    // ------------------------------------------------------------------ state

    public static JsonObject snapshot() {
        MinecraftServer s = server;
        if (s == null) {
            JsonObject offline = Json.obj();
            offline.addProperty("dist", dev.mcagent.util.Dist2.string());
            offline.addProperty("running", false);
            return offline;
        }
        // Callers are HTTP/WebSocket threads; the state below must be read on
        // the server thread like every other game access.
        if (!s.isSameThread()) {
            try {
                return onServer(GameBridge::snapshotNow, 5000);
            } catch (RuntimeException e) {
                JsonObject failed = Json.obj();
                failed.addProperty("dist", dev.mcagent.util.Dist2.string());
                failed.addProperty("running", true);
                failed.addProperty("error", e.toString());
                return failed;
            }
        }
        return snapshotNow();
    }

    private static JsonObject snapshotNow() {
        MinecraftServer s = server();
        JsonObject o = Json.obj();
        o.addProperty("dist", dev.mcagent.util.Dist2.string());
        o.addProperty("running", true);
        o.addProperty("version", s.getServerVersion());
        o.addProperty("tick", s.getTickCount());
        o.addProperty("playerCount", s.getPlayerCount());
        o.addProperty("tpsEstimate", TpsTracker.tps());
        o.addProperty("world", String.valueOf(s.overworld().dimension().location()));
        JsonArray players = Json.arr();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            JsonObject po = Json.obj();
            po.addProperty("name", p.getGameProfile().getName());
            po.addProperty("uuid", p.getUUID().toString());
            po.addProperty("dim", p.level().dimension().location().toString());
            po.addProperty("x", round(p.getX()));
            po.addProperty("y", round(p.getY()));
            po.addProperty("z", round(p.getZ()));
            po.addProperty("yaw", round(p.getYRot()));
            po.addProperty("pitch", round(p.getXRot()));
            po.addProperty("health", round(p.getHealth()));
            po.addProperty("food", p.getFoodData().getFoodLevel());
            po.addProperty("gameMode", p.gameMode.getGameModeForPlayer().getName());
            players.add(po);
        }
        o.add("players", players);
        return o;
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static String dimName(Entity e) {
        return e.level().dimension().location().toString().toLowerCase(Locale.ROOT);
    }

    static Vec3 of(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    static Vec2 rotOf(float yaw, float pitch) {
        return new Vec2(pitch, yaw);
    }
}
