package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ordered registry of MCP tools. */
public final class ToolRegistry {
    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public void add(McpTool tool) {
        tools.put(tool.name(), tool);
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
