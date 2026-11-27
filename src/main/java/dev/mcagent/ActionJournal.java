package dev.mcagent;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded queryable action receipts; all access is on the server thread. */
public final class ActionJournal {
    private static final int LIMIT = 128;
    private static final Map<String, JsonObject> RECORDS = new LinkedHashMap<>();
    private ActionJournal() {}

    public static String start(String bodyId, String kind, int tick) {
        if (RECORDS.size() >= LIMIT) {
            var it = RECORDS.entrySet().iterator();
            while (it.hasNext()) {
                if (!it.next().getValue().get("status").getAsString().equals("running")) { it.remove(); break; }
            }
            if (RECORDS.size() >= LIMIT) throw new IllegalStateException("too many running actions");
        }
        String id = UUID.randomUUID().toString();
        JsonObject record = Json.obj();
        record.addProperty("actionId", id);
        record.addProperty("bodyId", bodyId);
        record.addProperty("kind", kind);
        record.addProperty("startedTick", tick);
        record.addProperty("status", "running");
        RECORDS.put(id, record);
        return id;
    }

    public static boolean finish(String id, String status, String reason) {
        if (!java.util.Set.of("succeeded", "failed", "cancelled", "timed_out").contains(status))
            throw new IllegalArgumentException("invalid terminal action status");
        JsonObject record = RECORDS.get(id);
        if (record == null || !record.get("status").getAsString().equals("running")) return false;
        record.addProperty("status", status);
        record.addProperty("reason", reason);
        return true;
    }

    public static JsonObject get(String id) {
        JsonObject record = RECORDS.get(id);
        if (record != null) return record.deepCopy();
        JsonObject unknown = Json.obj();
        unknown.addProperty("actionId", id);
        unknown.addProperty("status", "outcome_unknown");
        unknown.addProperty("reason", "not retained in this world session; inspect current state before retrying");
        return unknown;
    }

    public static void clear() { RECORDS.clear(); }
}
