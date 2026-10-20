package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Model Context Protocol over "Streamable HTTP" (JSON-RPC 2.0 on POST).
 *
 * <p>Stateless: every POST is answered with {@code application/json}. No
 * {@code Mcp-Session-Id} is issued, which the reference clients accept, and the
 * standalone SSE channel (GET) is refused with 405 so clients fall back to
 * plain request/response.</p>
 */
public final class McpServer {
    private static final String DEFAULT_PROTOCOL = "2025-06-18";
    private static final String[] KNOWN_PROTOCOLS = {"2025-06-18", "2025-03-26", "2024-11-05"};

    private final ToolRegistry tools;

    public McpServer(ToolRegistry tools) {
        this.tools = tools;
    }

    public void handlePost(HttpCore.Request req, HttpCore.Response res) throws Exception {
        String body = req.bodyText().trim();
        if (body.isEmpty()) {
            res.text(400, "empty body");
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (Exception e) {
            res.status(400);
            res.headers.put("Content-Type", "application/json");
            res.body = error(null, -32700, "parse error: " + e.getMessage()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return;
        }

        if (parsed.isJsonArray()) {
            JsonArray out = Json.arr();
            for (JsonElement el : parsed.getAsJsonArray()) {
                JsonObject one = handleOne(el);
                if (one != null) out.add(one);
            }
            if (out.isEmpty()) {
                res.status(202).headers.put("Content-Type", "application/json");
            } else {
                res.status(200);
                res.headers.put("Content-Type", "application/json");
                res.body = out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            return;
        }

        JsonObject response = handleOne(parsed);
        if (response == null) {
            // Notification: acknowledge with an empty 202 per spec.
            res.status(202);
            return;
        }
        res.json(200, response);
    }

    private JsonObject handleOne(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return error(null, -32600, "invalid request");
        }
        JsonObject msg = el.getAsJsonObject();
        String method = Json.str(msg, "method", null);
        JsonElement id = msg.get("id");
        boolean notification = id == null || id.isJsonNull();
        JsonObject params = Json.sub(msg, "params");
        if (method == null) {
            return notification ? null : error(id, -32600, "missing method");
        }

        try {
            switch (method) {
                case "initialize": {
                    JsonObject result = Json.obj();
                    String requested = Json.str(params, "protocolVersion", DEFAULT_PROTOCOL);
                    result.addProperty("protocolVersion", pickProtocol(requested));
                    JsonObject caps = Json.obj();
                    JsonObject toolsCap = Json.obj();
                    toolsCap.addProperty("listChanged", false);
                    caps.add("tools", toolsCap);
                    result.add("capabilities", caps);
                    JsonObject info = Json.obj();
                    info.addProperty("name", "mcagent");
                    info.addProperty("title", "Minecraft Agent Bridge");
                    info.addProperty("version", McAgent.VERSION);
                    result.add("serverInfo", info);
                    result.addProperty("instructions",
                            "Live bridge into a running Minecraft client. Coordinates are block coordinates; "
                                    + "dimension defaults to the executing player's level. Read tools are always "
                                    + "available; mutating tools require allowWrite=true in config/mcagent.json.");
                    return result(id, result);
                }
                case "ping":
                    return result(id, Json.obj());
                case "tools/list":
                    return result(id, toolsList(params));
                case "tools/call":
                    return result(id, toolsCall(params));
                case "notifications/initialized":
                case "notifications/cancelled":
                case "notifications/roots/list_changed":
                case "notifications/progress":
                    return null;
                case "resources/list":
                    return result(id, emptyList("resources"));
                case "prompts/list":
                    return result(id, emptyList("prompts"));
                case "completion/complete":
                    return result(id, Json.obj());
                default:
                    return notification ? null : error(id, -32601, "method not found: " + method);
            }
        } catch (ToolFailure e) {
            return notification ? null : error(id, e.code, e.getMessage());
        } catch (Exception e) {
            McAgent.LOG.warn("[mcagent] mcp {} failed", method, e);
            return notification ? null : error(id, -32603, "internal error: " + e);
        }
    }

    private JsonObject toolsList(JsonObject params) {
        JsonObject result = Json.obj();
        result.add("tools", tools.toJson());
        return result;
    }

    private JsonObject toolsCall(JsonObject params) {
        String name = Json.str(params, "name", null);
        if (name == null || name.isBlank()) throw new ToolFailure(-32602, "missing tool name");
        McpTool tool = tools.get(name);
        if (tool == null) throw new ToolFailure(-32602, "unknown tool: " + name);
        JsonObject args = Json.sub(params, "arguments");
        if (args == null) args = Json.obj();
        McpResult out;
        try {
            out = tool.call(args);
        } catch (ToolFailure e) {
            out = McpResult.error(e.getMessage());
        } catch (Exception e) {
            McAgent.LOG.warn("[mcagent] tool {} failed", name, e);
            out = McpResult.error(name + " failed: " + e);
        }
        if (out == null) out = McpResult.error(name + " returned no result");
        return out.toJson();
    }

    private static JsonObject emptyList(String key) {
        JsonObject o = Json.obj();
        o.add(key, Json.arr());
        return o;
    }

    private static String pickProtocol(String requested) {
        for (String known : KNOWN_PROTOCOLS) {
            if (known.equals(requested)) return requested;
        }
        return DEFAULT_PROTOCOL;
    }

    private static JsonObject result(JsonElement id, JsonObject result) {
        JsonObject o = Json.obj();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        o.add("result", result);
        return o;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject o = Json.obj();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        JsonObject err = Json.obj();
        err.addProperty("code", code);
        err.addProperty("message", message == null ? "error" : message);
        o.add("error", err);
        return o;
    }

    /** Signals a JSON-RPC level failure for a tool call. */
    public static final class ToolFailure extends RuntimeException {
        public final int code;

        public ToolFailure(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
