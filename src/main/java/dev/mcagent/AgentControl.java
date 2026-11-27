package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcagent.fakeplayer.AgentActions;
import dev.mcagent.fakeplayer.AgentFollow;
import dev.mcagent.fakeplayer.AgentMovement;

import java.util.Set;
import java.util.UUID;

/** Server-owned stop latch. A generation also fences requests already queued before a boundary. */
public final class AgentControl {
    private static final Set<String> PASSIVE = Set.of("mc_control_stop", "mc_screenshot",
            "mc_edit_cancel", "mc_edit_discard", "mc_undo_cancel", "mc_player_halt", "mc_player_follow_stop");
    private static volatile String epoch = UUID.randomUUID().toString();
    private static final ThreadLocal<Long> TRANSPORT = new ThreadLocal<>();
    private static final ThreadLocal<McpSessions.Session> MCP_SESSION = new ThreadLocal<>();
    private static boolean stopped;
    private static String reason = "world_attached";
    private static final JsonArray stopErrors = new JsonArray();

    private AgentControl() {}

    /** The server supplies this ticket; it is never accepted from model arguments or MCP metadata. */
    public static <T> T inTransport(long generation, java.util.concurrent.Callable<T> action) throws Exception {
        Long previous = TRANSPORT.get();
        requireTransport(generation);
        TRANSPORT.set(generation);
        try { return action.call(); }
        finally { if (previous == null) TRANSPORT.remove(); else TRANSPORT.set(previous); }
    }

    static Long transportGeneration() { return TRANSPORT.get(); }

    static <T> T inSession(McpSessions.Session session, java.util.concurrent.Callable<T> call) throws Exception {
        McpSessions.Session previous = MCP_SESSION.get();
        MCP_SESSION.set(session);
        try { return call.call(); }
        finally { if (previous == null) MCP_SESSION.remove(); else MCP_SESSION.set(previous); }
    }

    static void requireTransport(Long generation) {
        if (generation != null && generation.longValue() != McAgent.transportGeneration())
            throw new IllegalStateException("bridge connection changed before execution; do not replay the old request");
    }

    public static void attach() {
        epoch = UUID.randomUUID().toString(); stopped = false; reason = "world_attached";
        ExternalTasks.attach();
        while (!stopErrors.isEmpty()) stopErrors.remove(0);
    }

    public static void detach() { ExternalTasks.revoke(); epoch = UUID.randomUUID().toString(); stopped = true; }
    public static String epoch() { return epoch; }
    public static boolean active() { return !stopped; }

    public static void requireActive(String expectedEpoch) {
        if (!epoch.equals(expectedEpoch)) throw new IllegalStateException("agent control changed before execution; do not replay the old request");
        if (stopped) throw new IllegalStateException("agent operations are stopped; only a human /agent resume can reopen them");
    }

    public static JsonObject snapshot() {
        JsonObject out = Json.obj();
        out.addProperty("worldSessionId", GameBridge.worldSessionId());
        out.addProperty("controlEpoch", epoch); out.addProperty("stopped", stopped);
        out.addProperty("reason", reason); out.add("stopErrors", stopErrors.deepCopy());
        out.add("externalTask", ExternalTasks.snapshot());
        out.addProperty("executionResumable", false);
        return out;
    }

    /** These exceptions may inspect state or reduce activity; new/unknown tools are fenced by default. */
    private static boolean passive(String name, JsonObject args) {
        if (ToolRegistry.readsOnly(name) || PASSIVE.contains(name)) return true;
        if (name.equals("mc_observe")) return Set.of("status", "list", "cancel").contains(Json.str(args, "action", ""));
        if (name.equals("mc_observe_wait")) return true;
        return false;
    }

    public static McpResult invoke(McpTool tool, JsonObject args, JsonObject metadata) throws Exception {
        Long acceptedTransport = TRANSPORT.get();
        try { requireTransport(acceptedTransport); }
        catch (IllegalStateException rejected) { return McpResult.error(rejected.getMessage()); }
        ExternalTaskLease.Stamp stamp;
        try { stamp = ExternalTaskLease.metadata(metadata); }
        catch (IllegalArgumentException error) { return McpResult.error(error.getMessage()); }
        boolean read = passive(tool.name(), args);
        McpSessions.Session session = MCP_SESSION.get();
        if (session != null && stamp != null) return McpResult.error("task metadata cannot override an MCP session");
        boolean owned = !ToolRegistry.readsOnly(tool.name())
                && !Set.of("mc_control_stop", "mc_screenshot", "mc_observe_wait").contains(tool.name());
        if (tool.name().equals("mc_observe") && Set.of("status", "list").contains(Json.str(args, "action", ""))) owned = false;
        if (read && !owned) {
            if (session != null && GameBridge.running()) {
                try { GameBridge.onServer(() -> { ExternalTasks.forSession(session, false); return null; }, 5000); }
                catch (IllegalStateException error) { return McpResult.error(error.getMessage()); }
            }
            if (stamp != null) {
                try { GameBridge.onServer(() -> { requireTransport(acceptedTransport); ExternalTasks.validate(stamp); return null; }, 5000); }
                catch (IllegalStateException error) { return McpResult.error(error.getMessage()); }
            }
            return tool.call(args);
        }
        String acceptedEpoch = session == null ? epoch : session.epoch;
        boolean needsOwner = owned;
        return GameBridge.onServer(() -> {
            ExternalTaskLease.Stamp authority;
            try {
                requireTransport(acceptedTransport);
                if (!read) requireActive(acceptedEpoch);
                authority = session == null ? stamp : ExternalTasks.forSession(session, needsOwner);
                ExternalTasks.validate(authority);
            }
            catch (IllegalStateException rejected) { return McpResult.error(rejected.getMessage()); }
            try { return ExternalTasks.execute(authority, tool, args); }
            catch (RuntimeException error) { throw error; }
            catch (Exception error) { throw new IllegalStateException("agent operation failed", error); }
        }, 30000);
    }

    public static JsonObject stop(String why) {
        stopped = true;
        boundary(why);
        return snapshot();
    }

    /** Human session/mode changes invalidate accepted work without implicitly clearing a stop latch. */
    public static void boundary(String why) {
        ExternalTasks.revoke();
        epoch = UUID.randomUUID().toString(); reason = why;
        cancelActivities(why);
    }

    static void cancelActivities(String why) {
        while (!stopErrors.isEmpty()) stopErrors.remove(0);
        stopPart("edit_jobs", () -> EditJobs.stopAll(why));
        stopPart("undo_jobs", () -> UndoJobs.stopAll(why));
        stopPart("observations", () -> BlockObservations.stopAll(why));
        stopPart("previews", EditPlans::clear);
        stopPart("following", AgentFollow::clearAll);
        stopPart("walking", () -> AgentMovement.stopAll(GameBridge.server()));
        stopPart("mining", () -> AgentActions.cancelAll(GameBridge.server()));
        for (var body : dev.mcagent.fakeplayer.FakePlayers.all()) {
            stopPart("body_inputs", () -> {
                // Stop using without releasing: releasing a charged bow can fire it.
                body.stopUsingItem(); body.xxa = 0; body.zza = 0;
                body.setJumping(false); body.setSprinting(false);
            });
        }
        stopPart("watchers", Watchers::clear);
        stopPart("highlights", Highlights::clear);
        if (!stopErrors.isEmpty()) stopped = true;
    }

    private static void stopPart(String part, Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) {
            // Keep stopping the remaining subsystems, but expose the incomplete stop to the caller.
            if (stopErrors.size() < 16) stopErrors.add(part);
            McAgent.LOG.error("[mcagent] could not stop {}", part, error);
        }
    }

    public static JsonObject resume() {
        if (stopped) {
            if (!stopErrors.isEmpty()) throw new IllegalStateException("some stop actions failed; retry /agent stop and inspect logs before resuming");
            epoch = UUID.randomUUID().toString(); stopped = false; reason = "human_resumed";
        }
        return snapshot();
    }

    public static void register(ToolRegistry registry) {
        registry.add(new McpTool() {
            public String name() { return "mc_control_status"; }
            public String description() { return "Read the global operation stop latch and generation. Resume only reopens new requests; cancelled work is never replayed. Not an external session identity."; }
            public JsonObject inputSchema() { return ToolRegistry.schema(); }
            public McpResult call(JsonObject args) { return GameBridge.onServer(() -> McpResult.text(snapshot().toString()), 5000); }
        });
        JsonObject schema = ToolRegistry.schema("worldSessionId");
        ToolRegistry.strProp(schema, "worldSessionId", "Current world from mc_status. Stale worlds cannot stop the new world.");
        registry.add(new McpTool() {
            public String name() { return "mc_control_stop"; }
            public String description() { return "Latch all bridge operations stopped, cancel edits/observations and stop all agent bodies. World reads remain available through a fresh session. Works with write/command permissions disabled. Human /agent resume required; no tool can resume."; }
            public JsonObject inputSchema() { return schema; }
            public McpResult call(JsonObject args) {
                var session = MCP_SESSION.get();
                var transport = transportGeneration();
                return GameBridge.onServer(() -> {
                    requireTransport(transport);
                    if (session != null) ExternalTasks.forSession(session, false);
                    if (!GameBridge.worldSessionId().equals(Json.str(args, "worldSessionId", ""))) return McpResult.error("stop world changed; do not replay");
                    return McpResult.text(stop("external_stopped").toString());
                }, 10000);
            }
        });
    }
}
