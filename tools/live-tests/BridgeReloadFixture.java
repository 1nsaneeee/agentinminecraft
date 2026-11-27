package dev.mcagent.qa;

import com.google.gson.JsonObject;
import dev.mcagent.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Native-operator-only transport race fixture. Its only tool side effect is a local counter. */
public final class BridgeReloadFixture {
    private static final String TOOL = "qa_reload_probe_once";
    private static final String REJECTION = "bridge connection changed before execution";
    private static final List<String> RESULTS = new ArrayList<>();
    private static State active;
    private static boolean listening;
    private static boolean passed;
    private static volatile String published = "BRIDGE_RELOAD_NOT_RUN";
    private static volatile Method transportGetter;

    private BridgeReloadFixture() {}

    public static void schedule(ServerPlayer human) throws Exception {
        if (active != null) throw new IllegalStateException("bridge reload fixture is already running");
        MinecraftServer server = GameBridge.server();
        String save = server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
        if (!save.startsWith("mcagent-review-") || human.getServer() != server
                || server.getPlayerList().getPlayer(human.getUUID()) != human
                || dev.mcagent.fakeplayer.FakePlayers.names().contains(human.getGameProfile().getName())
                || !human.hasPermissions(4))
            throw new IllegalStateException("bridge reload QA requires a real operator in a copied mcagent-review-* save");
        if (!McAgent.running() || !AgentControl.active() || EditJobs.active() || UndoJobs.active())
            throw new IllegalStateException("bridge reload QA requires an idle bridge with no active edit/undo jobs");
        JsonObject task = AgentControl.snapshot().getAsJsonObject("externalTask");
        if (task.get("active").getAsBoolean() || task.get("required").getAsBoolean())
            throw new IllegalStateException("release external task binding natively before bridge reload QA");
        JsonObject args = Json.obj(); args.addProperty("action", "list"); args.addProperty("worldSessionId", GameBridge.worldSessionId());
        JsonObject observations = McAgent.TOOLS.call("mc_observe", args).toJson();
        if (observations.has("isError")) throw new IllegalStateException("cannot inspect active observations");
        JsonObject listing = Json.parseObject(observations.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        for (var row : listing.getAsJsonArray("observations"))
            if (row.getAsJsonObject().get("outcome").getAsString().equals("waiting"))
                throw new IllegalStateException("bridge reload QA requires no waiting observations");
        if (McAgent.TOOLS.get(TOOL) != null || currentTransport() != null)
            throw new IllegalStateException("the QA tool name or current transport scope is already occupied");
        if (!listening) {
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BridgeReloadFixture::post);
            NeoForge.EVENT_BUS.addListener(BridgeReloadFixture::stopping);
            listening = true;
        }
        RESULTS.clear(); passed = false;
        active = new State(server, human);
        publish("PENDING: native transport race will run after this command context closes");
    }

    public static String results() { return published; }
    public static int resultCode() { return active == null && passed ? 1 : 0; }

    private static void post(ServerTickEvent.Post event) {
        State state = active;
        if (state == null) return;
        try {
            context(state);
            int tick = event.getServer().getTickCount();
            if (state.phase == 0 && tick > state.scheduledTick) begin(state);
            else if (state.phase == 1 && tick >= state.checkAt && ended(state)) verifyOldAndQueueFresh(state);
            else if (state.phase == 2 && tick >= state.checkAt && ended(state)) verifyFresh(state);
            else if (state.phase != 0 && tick > state.deadline)
                throw new IllegalStateException("owned queue workers did not finish before the fixture deadline");
        } catch (Exception error) {
            check("fixture completed without an unexpected failure (" + error.getClass().getSimpleName() + ")", false);
            finish(state);
        }
    }

    private static void begin(State state) throws Exception {
        checkScopes(state.oldGeneration);
        McAgent.TOOLS.add(new McpTool() {
            public String name() { return TOOL; }
            public String description() { return "Optional isolated QA counter; never changes Minecraft state."; }
            public JsonObject inputSchema() { return ToolRegistry.schema(); }
            public McpResult call(JsonObject args) {
                if (active != state || !state.accepting) return McpResult.error("QA fixture is no longer active");
                return McpResult.text("qa_tool:" + state.toolCalls.incrementAndGet());
            }
        });
        state.ownedTool = McAgent.TOOLS.get(TOOL);
        queue(state, state.oldGeneration);
        check("both old requests entered the real server queue", true);
        // Recreate the HTTP routes too: each core holds its own accepted transport generation.
        // No config reload or AgentControl boundary participates in this race.
        McAgent.stopHttp();
        McAgent.startHttp();
        state.newGeneration = McAgent.transportGeneration();
        check("transport replacement advanced its own generation", state.newGeneration > state.oldGeneration);
        check("transport replacement restarted the real HTTP core", McAgent.running() && McAgent.port() == state.port);
        check("transport replacement preserved the control epoch and stop latch", unchangedControl(state));
        check("transport replacement preserved the world and config instance", sameContext(state) && AgentConfig.get() == state.config);
        state.phase = 1;
        state.checkAt = state.server.getTickCount() + 2;
        state.deadline = state.server.getTickCount() + 100;
    }

    private static void verifyOldAndQueueFresh(State state) throws Exception {
        check("queued MCP gate request explicitly rejected the stale transport", rejected(state.toolOutcome.get()));
        check("queued passive-style server callback explicitly rejected the stale transport", rejected(state.callbackOutcome.get()));
        check("old callbacks executed no counter side effects", state.toolCalls.get() == 0 && state.callbackCalls.get() == 0);
        check("old worker transport scopes were removed", state.toolOutcome.get().scopeCleared && state.callbackOutcome.get().scopeCleared);
        check("control epoch stayed unchanged while old queue drained", unchangedControl(state));
        AtomicBoolean staleRan = new AtomicBoolean();
        boolean staleRejected = false;
        try { AgentControl.inTransport(state.oldGeneration, () -> { staleRan.set(true); return null; }); }
        catch (IllegalStateException expected) { staleRejected = expected.getMessage().contains(REJECTION); }
        check("stale transport entry was rejected before its action", staleRejected && !staleRan.get() && currentTransport() == null);
        AgentControl.inTransport(state.newGeneration, () -> {
            boolean nestedRejected = false;
            try { AgentControl.inTransport(state.oldGeneration, () -> { staleRan.set(true); return null; }); }
            catch (IllegalStateException expected) {
                nestedRejected = expected.getMessage().contains(REJECTION);
            }
            check("rejected nested entry preserved the current outer transport", nestedRejected && ticket(state.newGeneration));
            return null;
        });
        check("nested stale entry left no thread-local ticket", !staleRan.get() && currentTransport() == null);
        queue(state, state.newGeneration);
        state.phase = 2;
        state.checkAt = state.server.getTickCount() + 2;
        state.deadline = state.server.getTickCount() + 100;
    }

    private static void verifyFresh(State state) {
        Outcome tool = state.toolOutcome.get(), callback = state.callbackOutcome.get();
        check("new transport reached the actual MCP gate", !tool.rejected && tool.message.equals("qa_tool:1"));
        check("new transport reached the actual server queue", !callback.rejected && callback.message.equals("qa_callback:1"));
        check("only the two fresh callbacks ran once", state.toolCalls.get() == 1 && state.callbackCalls.get() == 1);
        check("fresh worker transport scopes were removed", tool.scopeCleared && callback.scopeCleared);
        check("the fresh core and unchanged control context remained usable", McAgent.running() && sameContext(state) && unchangedControl(state));
        finish(state);
    }

    private static void checkScopes(long generation) throws Exception {
        AgentControl.inTransport(generation, () -> {
            check("transport scope is set on entry", ticket(generation));
            AgentControl.inTransport(generation, () -> { check("nested transport scope is set", ticket(generation)); return null; });
            check("normal nested return restored the outer scope", ticket(generation));
            IllegalStateException sentinel = new IllegalStateException("QA scope sentinel");
            boolean nestedThrown = false;
            try { AgentControl.inTransport(generation, () -> { throw sentinel; }); }
            catch (IllegalStateException expected) { nestedThrown = expected == sentinel; }
            check("exceptional nested return restored the outer scope", nestedThrown && ticket(generation));
            return null;
        });
        check("normal outer return removed its thread-local ticket", currentTransport() == null);
        IllegalStateException sentinel = new IllegalStateException("QA outer scope sentinel");
        boolean outerThrown = false;
        try { AgentControl.inTransport(generation, () -> { throw sentinel; }); }
        catch (IllegalStateException expected) { outerThrown = expected == sentinel; }
        check("exceptional outer return removed its thread-local ticket", outerThrown && currentTransport() == null);
        if (currentTransport() != null) throw new IllegalStateException("QA transport scope leaked on the server thread");
    }

    // Keep QA in its own JPMS package; the production getter remains package-private.
    private static Long currentTransport() {
        try {
            Method getter = transportGetter;
            if (getter == null) {
                getter = AgentControl.class.getDeclaredMethod("transportGeneration");
                getter.setAccessible(true);
                transportGetter = getter;
            }
            return (Long) getter.invoke(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("QA cannot inspect the current transport scope", error);
        }
    }

    private static boolean ticket(long generation) { return Objects.equals(currentTransport(), generation); }

    private static void queue(State state, long generation) throws InterruptedException {
        state.toolOutcome.set(null); state.callbackOutcome.set(null);
        state.toolWorker = worker("qa-bridge-mcp-queue", state.toolOutcome, () -> {
            JsonObject result = AgentControl.inTransport(generation, () -> McAgent.TOOLS.call(TOOL, Json.obj())).toJson();
            return new Outcome(result.has("isError") && result.get("isError").getAsBoolean(),
                    result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString(), false);
        });
        state.callbackWorker = worker("qa-bridge-passive-queue", state.callbackOutcome, () -> {
            String result = AgentControl.inTransport(generation, () -> GameBridge.onServer(
                    () -> "qa_callback:" + state.callbackCalls.incrementAndGet(), 5000));
            return new Outcome(false, result, false);
        });
        state.toolWorker.start(); state.callbackWorker.start();
        waitForServerQueue(state.toolWorker);
        waitForServerQueue(state.callbackWorker);
    }

    private static Thread worker(String name, AtomicReference<Outcome> result, java.util.concurrent.Callable<Outcome> action) {
        Thread thread = new Thread(() -> {
            Outcome outcome;
            try { outcome = action.call(); }
            catch (Exception error) { outcome = new Outcome(true, String.valueOf(error.getMessage()), false); }
            result.set(new Outcome(outcome.rejected, outcome.message, currentTransport() == null));
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void waitForServerQueue(Thread request) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (request.isAlive() && System.nanoTime() < deadline) {
            boolean serverCall = false, futureWait = false;
            for (StackTraceElement frame : request.getStackTrace()) {
                serverCall |= frame.getClassName().equals(GameBridge.class.getName()) && frame.getMethodName().equals("onServer");
                futureWait |= frame.getClassName().equals("java.util.concurrent.CompletableFuture")
                        && (frame.getMethodName().equals("get") || frame.getMethodName().equals("timedGet"));
            }
            if (serverCall && futureWait && (request.getState() == Thread.State.WAITING || request.getState() == Thread.State.TIMED_WAITING)) return;
            request.join(1);
        }
        throw new IllegalStateException("bridge request did not wait in the actual server queue");
    }

    private static boolean ended(State state) {
        return !state.toolWorker.isAlive() && !state.callbackWorker.isAlive()
                && state.toolOutcome.get() != null && state.callbackOutcome.get() != null;
    }

    private static boolean rejected(Outcome outcome) { return outcome.rejected && outcome.message.contains(REJECTION); }
    private static boolean unchangedControl(State state) { return AgentControl.epoch().equals(state.epoch) && AgentControl.active() == state.wasActive; }
    private static boolean sameContext(State state) {
        return GameBridge.running() && GameBridge.server() == state.server && GameBridge.worldSessionId().equals(state.worldSession)
                && state.server.getPlayerList().getPlayer(state.humanId) == state.human;
    }
    private static void context(State state) {
        if (!sameContext(state)) throw new IllegalStateException("world or real operator changed during bridge reload QA");
    }

    private static void stopping(ServerStoppingEvent event) {
        State state = active;
        if (state != null && event.getServer() == state.server) {
            state.worldStopping = true;
            check("world remained attached until fixture completion", false);
            finish(state);
        }
    }

    private static void finish(State state) {
        state.accepting = false;
        for (Thread thread : new Thread[] { state.toolWorker, state.callbackWorker }) {
            if (thread == null || !thread.isAlive()) continue;
            thread.interrupt();
            try { thread.join(100); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        boolean workersClosed = (state.toolWorker == null || !state.toolWorker.isAlive()) && (state.callbackWorker == null || !state.callbackWorker.isAlive());
        check("fixture closed only its own queue workers", workersClosed);
        boolean removed = state.ownedTool == null;
        if (state.ownedTool != null && McAgent.TOOLS.get(TOOL) == state.ownedTool)
            removed = McAgent.TOOLS.all().remove(state.ownedTool);
        check("fixture removed its exact temporary MCP tool", removed && McAgent.TOOLS.get(TOOL) == null);
        check("fixture preserved the original production tool registry", new ArrayList<>(McAgent.TOOLS.all()).equals(state.originalTools));
        if (!state.worldStopping) {
            if (!McAgent.running() && sameContext(state) && AgentConfig.get() == state.config) McAgent.startHttp();
            check("fixture leaves the same-config HTTP core running", McAgent.running() && AgentConfig.get() == state.config);
        }
        active = null;
        passed = RESULTS.stream().noneMatch(line -> line.startsWith("FAIL:"));
        long checks = RESULTS.stream().filter(line -> line.startsWith("PASS:") || line.startsWith("FAIL:")).count();
        String summary = "BRIDGE_RELOAD_DONE=" + (passed ? "PASS" : "FAIL") + " checks=" + checks;
        publish(summary);
        McAgent.LOG.info("[mcagentqa bridge-reload] {}", summary);
        if (state.server.getPlayerList().getPlayer(state.humanId) == state.human)
            state.human.sendSystemMessage(Component.literal(summary + "; /mcagentqa bridge-reload results"));
    }

    private static void check(String label, boolean success) {
        String line = (success ? "PASS: " : "FAIL: ") + label;
        RESULTS.add(line);
        published = String.join("\n", RESULTS);
        McAgent.LOG.info("[mcagentqa bridge-reload] {}", line);
    }
    private static void publish(String line) { RESULTS.add(line); published = String.join("\n", RESULTS); }

    private record Outcome(boolean rejected, String message, boolean scopeCleared) {}
    private static final class State {
        final MinecraftServer server;
        final ServerPlayer human;
        final UUID humanId;
        final String worldSession, epoch;
        final AgentConfig config;
        final List<McpTool> originalTools;
        final int scheduledTick, port;
        final long oldGeneration;
        final boolean wasActive;
        final AtomicInteger toolCalls = new AtomicInteger(), callbackCalls = new AtomicInteger();
        final AtomicReference<Outcome> toolOutcome = new AtomicReference<>(), callbackOutcome = new AtomicReference<>();
        volatile boolean accepting = true;
        boolean worldStopping;
        McpTool ownedTool;
        Thread toolWorker, callbackWorker;
        long newGeneration;
        int phase, checkAt, deadline;
        State(MinecraftServer server, ServerPlayer human) {
            this.server = server; this.human = human; humanId = human.getUUID();
            worldSession = GameBridge.worldSessionId(); epoch = AgentControl.epoch(); config = AgentConfig.get();
            originalTools = new ArrayList<>(McAgent.TOOLS.all());
            scheduledTick = server.getTickCount(); port = McAgent.port(); oldGeneration = McAgent.transportGeneration();
            wasActive = AgentControl.active();
        }
    }
}
