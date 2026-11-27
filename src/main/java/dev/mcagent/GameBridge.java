package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcagent.fakeplayer.FakePlayers;
import dev.mcagent.util.TpsTracker;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
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
    private static String worldSessionId;
    private static final ThreadLocal<Boolean> BRIDGE_COMMAND = new ThreadLocal<>();

    private GameBridge() {}

    public static void attach(MinecraftServer s) {
        server = s;
        worldSessionId = java.util.UUID.randomUUID().toString();
        AgentControl.attach();
        PlayerSelections.clear();
        BlockObservations.clear();
        EditPlans.clear();
        EditJobs.attach();
        UndoJobs.clear();
        ActionJournal.clear();
    }

    public static void detach() {
        AgentControl.detach();
        PlayerSelections.clear();
        BlockObservations.clear();
        UndoJobs.stopAll("world_detached");
        UndoJobs.clear();
        EditJobs.detach();
        ActionJournal.clear();
        EditPlans.clear();
        worldSessionId = null;
        server = null;
    }

    /** Closest player that is not one of the agent's own bodies. */
    public static ServerPlayer nearestHuman(ServerPlayer self) {
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer other : self.serverLevel().getServer().getPlayerList().getPlayers()) {
            if (other == self
                    || dev.mcagent.fakeplayer.FakePlayers.names()
                            .contains(other.getGameProfile().getName())) {
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

    public static MinecraftServer server() {
        MinecraftServer s = server;
        if (s == null) throw new IllegalStateException("no server running (singleplayer world not loaded)");
        return s;
    }

    public static boolean running() {
        return server != null;
    }

    public static String worldSessionId() { server(); return worldSessionId; }
    public static boolean bridgeCommand() { return Boolean.TRUE.equals(BRIDGE_COMMAND.get()); }

    /** Runs {@code task} on the server thread and waits for the result. */
    public static <T> T onServer(java.util.function.Supplier<T> task, long timeoutMs) {
        MinecraftServer s = server();
        Long acceptedTransport = AgentControl.transportGeneration();
        if (s.isSameThread()) {
            AgentControl.requireTransport(acceptedTransport);
            return task.get();
        }
        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger phase = new java.util.concurrent.atomic.AtomicInteger(); // queued=0, started=1, cancelled=2
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        s.execute(() -> {
            try {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("server task expired before execution");
                if (!phase.compareAndSet(0, 1)) return;
                if (server != s) throw new IllegalStateException("the world changed before the task could run");
                AgentControl.requireTransport(acceptedTransport);
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            boolean cancelledBeforeStart = phase.compareAndSet(0, 2);
            if (cancelledBeforeStart) future.cancel(false);
            throw new IllegalStateException(cancelledBeforeStart ? "server task timed out before execution"
                    : "server task already started but timed out; inspect actual outcome before retrying");
        } catch (InterruptedException e) {
            if (phase.compareAndSet(0, 2)) future.cancel(false);
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
        return runCommand(command, playerName, permission, null);
    }

    /** Uses the same level for a world edit and its undo snapshot. */
    public static String runCommand(String command, String playerName, int permission, ServerLevel targetLevel) {
        return dispatchCommand(command, playerName, permission, targetLevel, null);
    }

    /** Command callback evidence for edit tools; preserves the legacy text-only command API. */
    public static JsonObject runCommandResult(String command, String playerName, int permission, ServerLevel targetLevel) {
        CommandReceipt receipt = new CommandReceipt(Math.max(1, AgentConfig.get().maxOutputChars));
        dispatchCommand(command, playerName, permission, targetLevel, receipt);
        return receipt.toJson();
    }

    private static String dispatchCommand(String command, String playerName, int permission, ServerLevel targetLevel,
                                          CommandReceipt receipt) {
        String acceptedEpoch = AgentControl.epoch();
        return onServer(() -> {
            AgentControl.requireActive(acceptedEpoch);
            ExternalTasks.requireCurrent();
            // Recheck on the game thread, before dispatch.
            if (AgentMode.isChat()) throw new IllegalStateException("chat mode does not run commands; use /agent work first");
            if (targetLevel != null && server().getLevel(targetLevel.dimension()) != targetLevel) {
                throw new IllegalStateException("command level belongs to an inactive world");
            }
            String cleaned = command.startsWith("/") ? command.substring(1) : command;
            ServerPlayer player = player(playerName);
            CommandSourceStack base = player.createCommandSourceStack();
            ServerLevel level = targetLevel == null ? base.getLevel() : targetLevel;
            List<String> lines = new ArrayList<>();
            CommandSource sink = new CaptureSource(lines, receipt);
            CommandSourceStack stack = new CommandSourceStack(
                    sink,
                    base.getPosition(),
                    base.getRotation(),
                    level,
                    permission < 0 ? Math.max(server().getProfilePermissions(player.getGameProfile()), 2) : permission,
                    base.getTextName(),
                    base.getDisplayName(),
                    base.getServer(),
                    base.getEntity());
            if (receipt != null) {
                JsonObject syntax = CommandKnowledge.inspect(server().getCommands().getDispatcher(), stack, cleaned);
                if (!Json.b(syntax, "syntaxComplete", false)) {
                    receipt.rejectBeforeDispatch(syntax.toString());
                    return "";
                }
            }
            boolean nestedBridgeCommand = bridgeCommand();
            BRIDGE_COMMAND.set(true);
            try { server().getCommands().performPrefixedCommand(receipt == null ? stack : stack.withCallback(receipt::result), cleaned); }
            finally { if (nestedBridgeCommand) BRIDGE_COMMAND.set(true); else BRIDGE_COMMAND.remove(); }
            if (lines.isEmpty()) return "";
            String joined = String.join("\n", lines);
            int max = AgentConfig.get().maxOutputChars;
            return joined.length() <= max ? joined : joined.substring(0, max) + "\n…[truncated]";
        }, 30000);
    }

    private static final class CaptureSource implements CommandSource {
        private final List<String> lines;
        private final CommandReceipt receipt;

        CaptureSource(List<String> lines, CommandReceipt receipt) {
            this.lines = lines;
            this.receipt = receipt;
        }

        @Override
        public void sendSystemMessage(Component message) {
            if (receipt == null) lines.add(message.getString());
            else receipt.message(message.getString());
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
        o.add("agentControl", AgentControl.snapshot());
        o.addProperty("tick", s.getTickCount());
        o.addProperty("playerCount", s.getPlayerCount());
        o.addProperty("tpsEstimate", TpsTracker.tps());
        o.addProperty("world", String.valueOf(s.overworld().dimension().location()));
        o.addProperty("worldSessionId", worldSessionId);
        o.addProperty("saveFolder", s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize().getFileName().toString());
        JsonArray players = Json.arr();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            JsonObject po = Json.obj();
            po.addProperty("name", p.getGameProfile().getName());
            po.addProperty("uuid", p.getUUID().toString());
            JsonObject selection = PlayerSelections.current(p);
            if (selection != null) po.add("selection", selection);
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
