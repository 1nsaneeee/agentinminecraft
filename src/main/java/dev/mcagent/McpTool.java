package dev.mcagent;

import com.google.gson.JsonObject;

/** One MCP tool exposed to the agent. */
public interface McpTool {
    String name();

    String description();

    /** JSON Schema object describing the arguments. */
    JsonObject inputSchema();

    McpResult call(JsonObject args) throws Exception;
}
