package dev.mcagent;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Small save-local record. A failed/corrupt read is never treated as an empty memory. */
public final class CompanionMemory {
    private static final int MAX_BYTES = 65536;
    private final Path file;
    private final String saveId;

    public CompanionMemory(Path worldRoot) throws IOException {
        Path root = worldRoot.toRealPath();
        file = root.resolve("data").resolve("mcagent-companion.json");
        saveId = UUID.nameUUIDFromBytes(root.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    public String saveId() { return saveId; }

    public JsonObject read() throws IOException {
        if (!Files.exists(file)) return null;
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent()) || Files.size(file) > MAX_BYTES)
            throw new IOException("invalid companion memory file");
        try {
            JsonObject record = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            validate(record);
            return record;
        } catch (RuntimeException e) {
            throw new IOException("companion memory is corrupt or incompatible", e);
        }
    }

    public void write(JsonObject record) throws IOException {
        validate(record);
        byte[] bytes = Json.GSON.toJson(record).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("companion memory exceeds size limit");
        Files.createDirectories(file.getParent());
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent())) throw new IOException("companion memory path must not be a symbolic link");
        Path temporary = Files.createTempFile(file.getParent(), "mcagent-companion-", ".tmp");
        try {
            Files.write(temporary, bytes);
            // Deliberately fail if atomic replacement is unsupported, preserving the previous record.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public JsonObject record(String bodyId, String bodyName, String targetId, boolean paused, JsonObject intent) {
        JsonObject record = Json.obj();
        record.addProperty("schemaVersion", 1);
        record.addProperty("saveId", saveId);
        if (bodyId != null) {
            record.addProperty("bodyId", bodyId);
            record.addProperty("bodyName", bodyName);
            record.addProperty("targetId", targetId);
            record.addProperty("paused", paused);
        }
        if (intent != null) record.add("intent", intent.deepCopy());
        return record;
    }

    private void validate(JsonObject record) {
        if (record == null || Json.i(record, "schemaVersion", -1) != 1 || !saveId.equals(Json.str(record, "saveId", null)))
            throw new IllegalArgumentException("companion memory version/save identity mismatch; preserve and review the file");
        if (record.has("bodyId")) {
            UUID.fromString(record.get("bodyId").getAsString());
            UUID.fromString(record.get("targetId").getAsString());
            bounded(record, "bodyName", 64, true);
            if (!record.has("paused") || !record.get("paused").isJsonPrimitive()
                    || !record.getAsJsonPrimitive("paused").isBoolean()) throw new IllegalArgumentException("invalid paused flag");
        } else if (record.has("intent") || record.has("targetId")) throw new IllegalArgumentException("memory has no binding identity");
        if (record.has("intent")) validateIntent(record.getAsJsonObject("intent"));
    }

    public static void validateIntent(JsonObject intent) {
        UUID.fromString(Json.str(intent, "intentId", ""));
        bounded(intent, "goal", 500, true);
        bounded(intent, "reason", 500, false);
        bounded(intent, "progress", 1000, false);
        bounded(intent, "stopConditions", 1000, false);
        String status = Json.str(intent, "status", "");
        if (!java.util.Set.of("planned", "active", "waiting", "completed", "cancelled").contains(status))
            throw new IllegalArgumentException("invalid intent status");
        if (status.equals("completed") && Json.str(intent, "progress", "").isBlank())
            throw new IllegalArgumentException("completed intent needs reported evidence in progress");
    }

    private static void bounded(JsonObject object, String field, int limit, boolean required) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.getAsJsonPrimitive(field).isString())
            throw new IllegalArgumentException("missing/invalid " + field);
        String text = object.get(field).getAsString();
        if (text.length() > limit || (required && text.isBlank())) throw new IllegalArgumentException("invalid length for " + field);
    }
}
