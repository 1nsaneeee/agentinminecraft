package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Ordered registry of MCP tools. */
public final class ToolRegistry {
    // Classify the complete tool, including optional arguments. For example, mc_screenshot can save a file.
    private static final Set<String> READ_ONLY = Set.of(
            "mc_status", "mc_control_status", "mc_blocks", "mc_block", "mc_block_entity", "mc_entities",
            "mc_inventory", "mc_selection", "mc_logs", "mc_wait", "mc_history", "mc_watch_list",
            "mc_recipe", "mc_tags", "mc_quests", "mc_quest", "mc_knowledge", "mc_command_inspect",
            "mc_item_inspect", "mc_player_list", "mc_action_status", "mc_edit_job", "mc_edit_jobs",
            "mc_undo_records", "mc_undo_job");
    static boolean readsOnly(String name) { return READ_ONLY.contains(name); }

    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    @FunctionalInterface
    public interface InvocationGate { McpResult call(McpTool tool, JsonObject args, JsonObject metadata) throws Exception; }
    private final InvocationGate gate;
    private final ThreadLocal<JsonObject> requestMetadata = new ThreadLocal<>();

    public ToolRegistry() { this((tool, args, metadata) -> tool.call(args)); }
    public ToolRegistry(InvocationGate gate) { this.gate = java.util.Objects.requireNonNull(gate); }

    public void add(McpTool tool) {
        if (tools.containsKey(tool.name())) throw new IllegalArgumentException("duplicate tool: " + tool.name());
        JsonObject schema = tool.inputSchema().deepCopy();
        tools.put(tool.name(), new McpTool() {
            public String name() { return tool.name(); }
            public String description() { return tool.description(); }
            public JsonObject inputSchema() { return schema.deepCopy(); }
            public McpResult call(JsonObject args) throws Exception {
                JsonObject snapshot = args == null ? null : args.deepCopy();
                String error = ToolArguments.error(schema, snapshot);
                if (error != null) return McpResult.error("invalid arguments: " + error);
                // Keep the original tool's server-thread, mode and permission checks.
                return gate.call(tool, snapshot, requestMetadata.get());
            }
        });
    }

    /** Shared entry for native model calls; MCP can keep its protocol-level unknown-tool error. */
    public McpResult call(String name, JsonObject args) throws Exception {
        McpTool tool = get(name);
        if (tool == null) return McpResult.error("unknown tool: " + name);
        return tool.call(args);
    }

    public McpResult call(String name, JsonObject args, JsonObject metadata) throws Exception {
        JsonObject previous = requestMetadata.get();
        try { requestMetadata.set(metadata == null ? null : metadata.deepCopy()); return call(name, args); }
        finally { if (previous == null) requestMetadata.remove(); else requestMetadata.set(previous); }
    }

    public McpTool get(String name) {
        return tools.get(name);
    }

    public Collection<McpTool> all() {
        return tools.values();
    }

    public JsonArray toJson() {
        JsonArray arr = Json.arr();
        for (McpTool t : tools.values()) {
            JsonObject o = Json.obj();
            o.addProperty("name", t.name());
            o.addProperty("description", t.description());
            o.add("inputSchema", t.inputSchema());
            JsonObject annotations = Json.obj();
            annotations.addProperty("readOnlyHint", readsOnly(t.name()));
            o.add("annotations", annotations);
            arr.add(o);
        }
        return arr;
    }

    /** Helper for building a JSON Schema object with named properties. */
    public static JsonObject schema(String... required) {
        JsonObject s = Json.obj();
        s.addProperty("type", "object");
        s.add("properties", Json.obj());
        if (required.length > 0) {
            JsonArray req = Json.arr();
            for (String r : required) req.add(r);
            s.add("required", req);
        }
        s.addProperty("additionalProperties", false);
        return s;
    }

    public static JsonObject strProp(JsonObject schema, String name, String description) {
        JsonObject props = schema.getAsJsonObject("properties");
        JsonObject p = Json.obj();
        p.addProperty("type", "string");
        p.addProperty("description", description);
        props.add(name, p);
        return schema;
    }

    public static JsonObject intProp(JsonObject schema, String name, String description) {
        JsonObject props = schema.getAsJsonObject("properties");
        JsonObject p = Json.obj();
        p.addProperty("type", "integer");
        p.addProperty("description", description);
        props.add(name, p);
        return schema;
    }

    public static JsonObject numProp(JsonObject schema, String name, String description, double min, double max) {
        JsonObject props = schema.getAsJsonObject("properties");
        JsonObject p = Json.obj();
        p.addProperty("type", "number");
        p.addProperty("description", description);
        p.addProperty("minimum", min);
        p.addProperty("maximum", max);
        props.add(name, p);
        return schema;
    }

    public static JsonObject boolProp(JsonObject schema, String name, String description) {
        JsonObject props = schema.getAsJsonObject("properties");
        JsonObject p = Json.obj();
        p.addProperty("type", "boolean");
        p.addProperty("description", description);
        props.add(name, p);
        return schema;
    }
}
