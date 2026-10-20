package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Base64;

/** One MCP {@code tools/call} result: text and/or image content blocks. */
public final class McpResult {
    private final JsonArray content = Json.arr();
    private boolean isError;

    public static McpResult text(String s) {
        return new McpResult().addText(s);
    }

    public static McpResult error(String s) {
        return new McpResult().addText(s).asError();
    }

    public McpResult addText(String s) {
        JsonObject item = Json.obj();
        item.addProperty("type", "text");
        item.addProperty("text", s == null ? "" : s);
        content.add(item);
        return this;
    }

    public McpResult addImage(byte[] data, String mimeType) {
        JsonObject item = Json.obj();
        item.addProperty("type", "image");
        item.addProperty("data", Base64.getEncoder().encodeToString(data));
        item.addProperty("mimeType", mimeType);
        content.add(item);
        return this;
    }

    public McpResult asError() {
        this.isError = true;
        return this;
    }

    public JsonObject toJson() {
        JsonObject o = Json.obj();
        o.add("content", content);
        if (isError) o.addProperty("isError", true);
        return o;
    }
}
