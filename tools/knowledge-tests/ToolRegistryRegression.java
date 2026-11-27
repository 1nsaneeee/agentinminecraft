package dev.mcagent;

import com.google.gson.JsonObject;

public final class ToolRegistryRegression {
    private static int calls;
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (!ToolRegistry.readsOnly("mc_selection") || !ToolRegistry.readsOnly("mc_knowledge")
                || ToolRegistry.readsOnly("mc_command") || ToolRegistry.readsOnly("mc_screenshot")
                || ToolRegistry.readsOnly("mc_observe") || ToolRegistry.readsOnly("unknown"))
            throw new AssertionError("MCP read-only hints must describe every supported argument");
        ToolRegistry registry = new ToolRegistry();
        JsonObject schema = ToolRegistry.schema("amount");
        ToolRegistry.intProp(schema, "amount", "quantity");
        ToolRegistry.boolProp(schema, "enabled", "flag");
        ToolRegistry.numProp(schema, "distance", "range", 0, 10);
        McpTool tool = new McpTool() {
            public String name() { return "test"; }
            public String description() { return "test"; }
            public JsonObject inputSchema() { return schema; }
            public McpResult call(JsonObject arguments) {
                calls++;
                return McpResult.error("permission denied by original tool");
            }
        };
        registry.add(tool);
        for (String input : new String[]{"{}", "{\"amount\":1.5}", "{\"amount\":\"2\"}",
                "{\"amount\":null}", "{\"amount\":2,\"enabled\":\"false\"}",
                "{\"amount\":2,\"extra\":true}", "{\"amount\":2,\"distance\":11}",
                "{\"amount\":2,\"distance\":-1}", "{\"amount\":[]}"}) {
            check(registry.call("test", Json.parseObject(input)).toJson().has("isError"), "reject invalid input");
        }
        check(calls == 0, "invalid inputs have no side effects");
        check(registry.call("missing", Json.obj()).toJson().has("isError"), "unknown tool");
        check(registry.call("test", null).toJson().has("isError"), "null arguments");
        // Returned schema edits must not weaken the registered contract.
        registry.get("test").inputSchema().remove("required");
        schema.remove("required");
        check(registry.call("test", Json.obj()).toJson().has("isError") && calls == 0, "schema isolation");
        var result = registry.call("test", Json.parseObject("{\"amount\":2.0,\"enabled\":false,\"distance\":10}"));
        check(calls == 1 && result.toJson().toString().contains("permission denied"), "original permissions preserved");
        registry.get("test").call(Json.parseObject("{\"amount\":\"3\"}"));
        check(calls == 1, "MCP lookup uses same validation");
        boolean duplicate = false;
        try { registry.add(tool); } catch (IllegalArgumentException e) { duplicate = true; }
        check(duplicate, "duplicate cannot replace registered tool");
        int[] gates = {0};
        ToolRegistry gated = new ToolRegistry((original, arguments, metadata) -> {
            gates[0]++; arguments.addProperty("amount", 9);
            return McpResult.error("control boundary rejected");
        });
        gated.add(tool);
        gated.call("test", Json.parseObject("{\"amount\":\"bad\"}"));
        check(gates[0] == 0, "validation runs before the invocation gate");
        JsonObject input = Json.parseObject("{\"amount\":2}");
        check(gated.call("test", input).toJson().toString().contains("control boundary rejected") && calls == 1,
                "gate can reject before original side effects");
        check(input.get("amount").getAsInt() == 2, "gate cannot modify caller arguments");
        gated.get("test").call(input);
        check(gates[0] == 2 && calls == 1, "direct MCP lookup cannot bypass the gate");
        System.out.println("TOOL_REGISTRY_CHECKS=" + checks);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
