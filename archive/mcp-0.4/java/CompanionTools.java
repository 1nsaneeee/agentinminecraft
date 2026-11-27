package dev.mcagent;

import com.google.gson.JsonObject;

public final class CompanionTools {
    private CompanionTools() {}

    public static void controlProperties(JsonObject schema) {
        ToolRegistry.strProp(schema, "worldSessionId", "For a bound companion, copy the worldSessionId from this turn's companion state.");
        ToolRegistry.strProp(schema, "controlEpoch", "For a bound companion, copy this turn's controlEpoch. Never refresh it to replay a rejected old action.");
        ToolRegistry.strProp(schema, "controllerId", "Controller that holds the bound companion's current lease.");
    }

    public static void register(ToolRegistry registry) {
        JsonObject intentSchema = ToolRegistry.schema("operation", "worldSessionId", "controlEpoch", "controllerId");
        controlProperties(intentSchema);
        ToolRegistry.strProp(intentSchema, "operation", "start a new intent or update the current one; cancel/complete the old one before replacing it.");
        ToolRegistry.strProp(intentSchema, "intentId", "Required for update; read from current companion state.");
        ToolRegistry.strProp(intentSchema, "goal", "Concrete goal, required for start, at most 500 characters.");
        ToolRegistry.strProp(intentSchema, "reason", "Why this goal fits the player/context, at most 500 characters.");
        ToolRegistry.strProp(intentSchema, "progress", "Actual progress/evidence or uncertainty, at most 1000 characters. Required for completed status.");
        ToolRegistry.strProp(intentSchema, "stopConditions", "When to stop or ask the player, at most 1000 characters.");
        ToolRegistry.strProp(intentSchema, "status", "planned, active, waiting, completed or cancelled. This is an agent report, not proof of game outcome.");
        registry.add(new McpTool() {
            public String name() { return "mc_companion_intent"; }
            public String description() { return "Save/update the companion's current intent in this save. Keeps a goal across turns/restarts without replaying actions. Use real action receipts for evidence."; }
            public JsonObject inputSchema() { return intentSchema; }
            public McpResult call(JsonObject args) {
                return GameBridge.onServer(() -> McpResult.text(Json.GSON.toJson(CompanionRuntime.updateIntent(args))), 5000);
            }
        });
        JsonObject actionSchema = ToolRegistry.schema("actionId");
        ToolRegistry.strProp(actionSchema, "actionId", "Receipt returned by mc_player_walk; retained in this world session (bounded history).");
        registry.add(new McpTool() {
            public String name() { return "mc_action_status"; }
            public String description() { return "Query real walk outcome: running/succeeded/failed/cancelled/timed_out. Missing/evicted receipts return outcome_unknown, never inferred success."; }
            public JsonObject inputSchema() { return actionSchema; }
            public McpResult call(JsonObject args) {
                return GameBridge.onServer(() -> McpResult.text(Json.GSON.toJson(ActionJournal.get(Json.str(args, "actionId", "")))), 5000);
            }
        });
        registry.add(new McpTool() {
            public String name() { return "mc_companion_state"; }
            public String description() { return "Read the bound Chat companion, target, control generation and current body state. Does not bind, resume or execute actions."; }
            public JsonObject inputSchema() { return ToolRegistry.schema(); }
            public McpResult call(JsonObject args) {
                return GameBridge.onServer(() -> McpResult.text(Json.GSON.toJson(CompanionRuntime.snapshot())), 5000);
            }
        });
        JsonObject schema = ToolRegistry.schema("action", "worldSessionId", "controlEpoch", "controllerId");
        controlProperties(schema);
        ToolRegistry.strProp(schema, "action", "claim or renew a 15-second lease, or pause. Binding/releasing use /agent companion; resume requires explicit human in-game input.");
        registry.add(new McpTool() {
            public String name() { return "mc_companion_control"; }
            public String description() { return "Claim/renew/pause Chat companion control using the current world and epoch. Does not grant world-edit permissions. Use a harness heartbeat to renew; expiry requires human resume."; }
            public JsonObject inputSchema() { return schema; }
            public McpResult call(JsonObject args) {
                return GameBridge.onServer(() -> McpResult.text(Json.GSON.toJson(CompanionRuntime.control(args))), 5000);
            }
        });
    }
}
