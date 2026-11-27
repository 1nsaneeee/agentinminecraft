package dev.mcagent;

import com.google.gson.JsonObject;

/** No game task may be acquired by the transport-only fixture. Lease behavior has its own suite. */
public final class ExternalTasks {
    public static JsonObject bind(String owner, JsonObject frame) {
        throw new IllegalStateException("transport fixture has no game world");
    }
    public static JsonObject renew(String owner, JsonObject frame) {
        throw new IllegalStateException("transport fixture has no game world");
    }
    public static void ownerClosed(String owner) {}
}
