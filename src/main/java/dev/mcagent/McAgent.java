package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;

/** Static facade: owns the HTTP server, the MCP endpoint and its connection generation. */
public final class McAgent {
    public static final String MOD_ID = "agentinminecraft";
    public static final String VERSION = "0.5.0-rc.3";
    public static final Logger LOG = LoggerFactory.getLogger("mcagent");

    public static final ToolRegistry TOOLS = new ToolRegistry(AgentControl::invoke);
    private static volatile long generation;
    public static long transportGeneration() { return generation; }

    private static HttpCore http;
    private static volatile boolean toolsRegistered;

    private McAgent() {}

    public static synchronized void ensureTools() {
        if (!toolsRegistered) {
            Tools.registerAll(TOOLS);
            toolsRegistered = true;
        }
    }

    public static synchronized void startHttp() {
        if (http != null) return;
        AgentConfig cfg = AgentConfig.get();
        if (!cfg.enabled) {
            LOG.info("[mcagent] disabled by config; not starting HTTP bridge");
            return;
        }
        ensureTools();
        try {
            HttpCore core = new HttpCore(cfg.host, cfg.port);
            McpServer mcp = new McpServer(TOOLS);
            long transportGeneration = generation;

            core.route("GET", "/health", (req, res) -> res.json(200, health()));

            core.route("POST", "/mcp", (req, res) -> {
                if (!authorize(req, res)) return;
                AgentControl.inTransport(transportGeneration, () -> { mcp.handlePost(req, res); return null; });
            });
            core.route("GET", "/mcp", (req, res) -> {
                if (!authorize(req, res)) return;
                res.header("Allow", "POST, DELETE").text(405, "standalone SSE stream not offered; POST JSON-RPC to /mcp");
            });
            core.route("DELETE", "/mcp", (req, res) -> {
                if (!authorize(req, res)) return;
                AgentControl.inTransport(transportGeneration, () -> { mcp.handleDelete(req, res); return null; });
            });

            core.start();
            http = core;
            LOG.info("[mcagent] bridge listening on {}:{}  (MCP: /mcp)", cfg.host, core.port());
        } catch (IOException e) {
            LOG.error("[mcagent] could not bind {}:{} - {}", cfg.host, cfg.port, e.toString());
        }
    }

    public static synchronized void stopHttp() {
        generation++;
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
                "unauthorized: send 'Authorization: Bearer <token>' (token is in config/agentinminecraft.json)");
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
        endpoints.add("DELETE /mcp");
        o.add("endpoints", endpoints);
        return o;
    }

    public static boolean reloadConfig() {
        return GameBridge.onServer(() -> {
            // Parsing finishes before changing the live transport. A malformed file
            // cannot replace restrictive settings with defaults or overwrite its token.
            if (!AgentConfig.reload()) return false;
            synchronized (McAgent.class) {
                stopHttp();
                // Close before boundary broadcasts: a non-reading peer must not
                // prevent the main thread from reaching transport revocation.
                AgentControl.boundary("config_reloaded");
                startHttp();
            }
            if (running()) AgentDiscovery.publish();
            else AgentDiscovery.withdraw();
            if (AgentConfig.get().enabled && !running())
                throw new IllegalStateException("configuration applied, but bridge restart failed; inspect the bind error and retry after correcting the configuration");
            return true;
        }, 10000);
    }
}
