package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;

/** Static facade: owns the HTTP server, the MCP endpoint and the WebSocket hub. */
public final class McAgent {
    public static final String MOD_ID = "mcagent";
    public static final String VERSION = "0.3.0";
    public static final Logger LOG = LoggerFactory.getLogger("mcagent");

    public static final ToolRegistry TOOLS = new ToolRegistry();
    public static final WsHub WS = new WsHub();

    private static HttpCore http;
    private static volatile boolean toolsRegistered;

    private McAgent() {}

    public static synchronized void startHttp() {
        if (http != null) return;
        AgentConfig cfg = AgentConfig.get();
        if (!cfg.enabled) {
            LOG.info("[mcagent] disabled by config; not starting HTTP bridge");
            return;
        }
        if (!toolsRegistered) {
            Tools.registerAll(TOOLS);
            toolsRegistered = true;
        }
        try {
            HttpCore core = new HttpCore(cfg.host, cfg.port);
            McpServer mcp = new McpServer(TOOLS);

            core.route("GET", "/health", (req, res) -> res.json(200, health()));

            core.route("POST", "/mcp", (req, res) -> {
                if (!authorize(req, res)) return;
                mcp.handlePost(req, res);
            });
            core.route("GET", "/mcp", (req, res) -> {
                if (!authorize(req, res)) return;
                res.header("Allow", "POST").text(405, "standalone SSE stream not offered; POST JSON-RPC to /mcp");
            });
            core.route("DELETE", "/mcp", (req, res) -> {
                if (!authorize(req, res)) return;
                res.status(204);
            });

            core.route("GET", "/ws", (req, res) -> {
                if (!authorize(req, res)) return;
                WsHub.upgrade(req, res);
            });

            core.start();
            http = core;
            LOG.info("[mcagent] bridge listening on {}:{}  (mcp: POST /mcp, live channel: GET /ws)", cfg.host, core.port());
        } catch (IOException e) {
            LOG.error("[mcagent] could not bind {}:{} - {}", cfg.host, cfg.port, e.toString());
        }
    }

    public static synchronized void stopHttp() {
        if (http != null) {
            http.close();
            http = null;
            LOG.info("[mcagent] bridge closed");
        }
    }

    public static boolean running() {
        return http != null;
    }

    public static int port() {
        return http == null ? -1 : http.port();
    }

    /** All access is loopback by default; every route except /health requires the token. */
    private static boolean authorize(HttpCore.Request req, HttpCore.Response res) {
        String token = AgentConfig.get().token;
        if (token == null || token.isBlank()) {
            res.text(500, "no token configured");
            return false;
        }
        String provided = null;
        String header = req.header("authorization");
        if (header != null && header.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            provided = header.substring(7).trim();
        }
        if (provided == null) provided = req.param("token", null);
        if (provided == null) provided = req.header("x-mcagent-token");
        if (token.equals(provided)) return true;
        res.header("WWW-Authenticate", "Bearer realm=\"mcagent\"").text(401,
                "unauthorized: send 'Authorization: Bearer <token>' (token is in config/mcagent.json)");
        return false;
    }

    private static JsonObject health() {
        AgentConfig cfg = AgentConfig.get();
        JsonObject o = Json.obj();
        o.addProperty("ok", true);
        o.addProperty("name", "mcagent");
        o.addProperty("version", VERSION);
        o.addProperty("bind", cfg.host + ":" + cfg.port);
        JsonArray endpoints = Json.arr();
        endpoints.add("POST /mcp");
        endpoints.add("GET /ws");
        o.add("endpoints", endpoints);
        o.addProperty("wsClients", WS.size());
        return o;
    }

    public static synchronized void reloadConfig() {
        stopHttp();
        AgentConfig.load(dev.mcagent.util.Dist2.configDir());
        startHttp();
    }
}
