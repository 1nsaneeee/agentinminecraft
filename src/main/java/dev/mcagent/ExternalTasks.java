package dev.mcagent;

import com.google.gson.JsonObject;

/** Server-thread integration for the one current external game task. */
public final class ExternalTasks {
    private static final ExternalTaskLease LEASE = new ExternalTaskLease();
    // MCP clients need time between model turns. Long edits retain their separate 15s job lease.
    private static final long MCP_WRITE_IDLE_MS = 120_000;
    private static McpSessions.Session directOwner;
    private static ExternalTaskLease.Stamp directStamp;
    private static final ThreadLocal<ExternalTaskLease.Stamp> EXECUTING = new ThreadLocal<>();
    private ExternalTasks() {}
    private static long now() { return System.nanoTime() / 1_000_000; }
    public static void attach() { revoke(); LEASE.release(); }
    public static void revoke() { directOwner = null; directStamp = null; LEASE.revoke(); }
    public static JsonObject snapshot() { return LEASE.snapshot(now()); }
    public static void tick() { if (LEASE.expired(now())) AgentControl.boundary("external_task_lease_expired"); }
    public static void validate(ExternalTaskLease.Stamp stamp) {
        if (directOwner != null && !directOwner.valid(AgentControl.epoch(), now()))
            throw new IllegalStateException("MCP session ended");
        LEASE.validate(stamp, now());
    }
    public static void requireCurrent() { validate(EXECUTING.get()); }

    /** Called on the server thread immediately before a direct client's tool is dispatched. */
    static ExternalTaskLease.Stamp forSession(McpSessions.Session session, boolean needsOwner) {
        tick();
        if (!session.valid(AgentControl.epoch(), now())) throw new IllegalStateException("MCP session ended");
        if (directOwner == session) {
            LEASE.renew(session.taskId, directStamp, now());
            return directStamp;
        }
        if (!needsOwner) return null;
        AgentControl.requireActive(session.epoch);
        JsonObject frame = Json.obj();
        frame.addProperty("worldSessionId", GameBridge.worldSessionId());
        frame.addProperty("controlEpoch", session.epoch);
        frame.addProperty("agentSessionId", session.taskId);
        frame.addProperty("taskId", session.taskId);
        JsonObject ack = LEASE.bind(session.taskId, GameBridge.worldSessionId(), session.epoch, frame, now(), MCP_WRITE_IDLE_MS);
        directOwner = session;
        directStamp = ExternalTaskLease.parse(ack.getAsJsonObject("taskContext"));
        AgentControl.cancelActivities("external_task_bound");
        if (!AgentControl.active()) { revoke(); throw new IllegalStateException("previous activities could not be stopped"); }
        return directStamp;
    }

    static void endSession(McpSessions.Session session) {
        if (directOwner == session) AgentControl.boundary("mcp_session_closed");
    }
    public static ExternalTaskLease.Stamp current() { return EXECUTING.get(); }
    public static JsonObject publicContext() {
        if (EXECUTING.get() == null) return null;
        JsonObject state = snapshot(), out = Json.obj();
        for (String key : java.util.List.of("bindingId", "worldSessionId", "agentSessionId", "taskId"))
            if (state.has(key)) out.add(key, state.get(key).deepCopy());
        return out;
    }
    public static <T> T command(ExternalTaskLease.Stamp stamp, java.util.function.Supplier<T> action) {
        validate(stamp); ExternalTaskLease.Stamp previous = EXECUTING.get();
        try { EXECUTING.set(stamp); return action.get(); }
        finally { if (previous == null) EXECUTING.remove(); else EXECUTING.set(previous); }
    }

    public static McpResult execute(ExternalTaskLease.Stamp stamp, McpTool tool, JsonObject args) throws Exception {
        ExternalTaskLease.Stamp previous = EXECUTING.get();
        try { EXECUTING.set(stamp); return tool.call(args); }
        finally { if (previous == null) EXECUTING.remove(); else EXECUTING.set(previous); }
    }
}
