package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/** Save-local bounded summaries only. Never stores commands, NBT, credentials, undo cells or executable jobs. */
public final class EditJobJournal {
    private static final int MAX_BYTES = 131072, LIMIT = 16;
    private static final Set<String> STRINGS = Set.of("jobId", "planId", "worldSessionId", "playerId", "status", "reason", "dimension", "lastCommandOutcome");
    private static final Set<String> COUNTS = Set.of("totalCells", "processedCells", "changedCells", "skippedCells", "uncertainCells", "commandCalls", "batchCells", "leaseRemainingMs");
    private static final Set<String> FLAGS = Set.of("undoRecorded", "undoPending", "partial", "verificationRequired");
    private final Path root, file;
    private final String saveId;
    private LinkedHashMap<String, JsonObject> records = new LinkedHashMap<>();
    private byte[] expectedBytes;

    public EditJobJournal(Path worldRoot) throws IOException {
        root = worldRoot.toRealPath();
        file = root.resolve("data/mcagent-edit-jobs.json");
        saveId = UUID.nameUUIDFromBytes(root.toString().getBytes(StandardCharsets.UTF_8)).toString();
        checkPath();
        if (!Files.exists(file)) return;
        byte[] bytes = boundedRead();
        try {
            JsonObject envelope = Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
            if (envelope.size() != 3 || number(envelope, "schemaVersion", 1, 1) != 1 || !saveId.equals(string(envelope, "saveId", 36))
                    || !envelope.has("receipts") || !envelope.get("receipts").isJsonArray()) throw new IllegalArgumentException("invalid envelope");
            JsonArray receipts = envelope.getAsJsonArray("receipts");
            if (receipts.size() > LIMIT) throw new IllegalArgumentException("too many receipts");
            for (var item : receipts) {
                if (!item.isJsonObject()) throw new IllegalArgumentException("receipt must be an object");
                JsonObject receipt = item.getAsJsonObject(); validate(receipt);
                if (records.put(string(receipt, "jobId", 36), receipt.deepCopy()) != null) throw new IllegalArgumentException("duplicate job");
            }
            expectedBytes = bytes;
        } catch (RuntimeException e) { throw new IOException("edit journal corrupt or incompatible; preserve the file", e); }
    }

    public void put(JsonObject snapshot) throws IOException {
        JsonObject receipt = snapshot.deepCopy(); receipt.remove("receiptPersisted"); receipt.remove("lastPersistedProcessedCells");
        receipt.addProperty("recordedAtMillis", System.currentTimeMillis());
        try { validate(receipt); } catch (RuntimeException e) { throw new IOException("invalid edit receipt", e); }
        LinkedHashMap<String, JsonObject> next = new LinkedHashMap<>(records);
        String id = receipt.get("jobId").getAsString(); next.remove(id); next.put(id, receipt);
        while (next.size() > LIMIT) next.remove(next.keySet().iterator().next());
        JsonObject envelope = Json.obj(); envelope.addProperty("schemaVersion", 1); envelope.addProperty("saveId", saveId);
        JsonArray list = Json.arr(); for (JsonObject item : next.values()) list.add(item.deepCopy()); envelope.add("receipts", list);
        byte[] bytes = Json.GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("edit journal exceeds size limit");
        checkPath();
        if (Files.exists(file)) {
            if (expectedBytes == null || !Arrays.equals(expectedBytes, boundedRead())) throw new IOException("edit journal changed outside runtime; preserve and reload");
        } else if (expectedBytes != null) throw new IOException("edit journal removed outside runtime; preserve and reload");
        Files.createDirectories(file.getParent()); checkPath();
        Path temporary = Files.createTempFile(file.getParent(), "mcagent-edit-jobs-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            records = next; expectedBytes = bytes;
        } finally { Files.deleteIfExists(temporary); }
    }

    public JsonArray receipts() {
        JsonArray list = Json.arr(); for (JsonObject record : records.values()) list.add(record.deepCopy()); return list;
    }
    public JsonObject get(String id) { JsonObject record = records.get(id); return record == null ? null : record.deepCopy(); }

    /** Historical observations are never converted back into work or current-session undo rights. */
    public static JsonObject historical(JsonObject record) {
        JsonObject out = record.deepCopy();
        out.addProperty("historicalReceipt", true); out.addProperty("executionResumable", false);
        out.addProperty("undoAvailableInCurrentSession", false); out.addProperty("leaseRemainingMs", 0);
        out.addProperty("verificationRequired", true); out.addProperty("receiptPersisted", true);
        out.addProperty("progressSnapshotOnly", true); out.addProperty("lastRecordedStatus", Json.str(record, "status", "outcome_unknown"));
        if (Json.str(record, "status", "").equals("running")) {
            out.addProperty("status", "outcome_unknown");
            out.addProperty("reason", "previous runtime ended without a terminal receipt; inspect actual world; do not replay");
            out.addProperty("undoPending", false);
        }
        return out;
    }

    private byte[] boundedRead() throws IOException {
        checkPath();
        if (Files.size(file) > MAX_BYTES) throw new IOException("edit journal exceeds size limit");
        try (var stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("edit journal exceeds size limit");
            return bytes;
        }
    }
    private void checkPath() throws IOException {
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent())) throw new IOException("edit journal path must not be a symbolic link");
        if (Files.exists(file.getParent()) && !file.getParent().toRealPath().startsWith(root)) throw new IOException("edit journal directory escapes this save");
    }
    private static void validate(JsonObject record) {
        for (var key : record.keySet()) if (!STRINGS.contains(key) && !COUNTS.contains(key) && !FLAGS.contains(key) && !key.equals("recordedAtMillis"))
            throw new IllegalArgumentException("unknown receipt field");
        for (String key : STRINGS) string(record, key, key.equals("reason") ? 512 : 256);
        for (String key : Set.of("jobId", "planId", "worldSessionId", "playerId")) {
            String text = string(record, key, 36);
            if (!UUID.fromString(text).toString().equals(text)) throw new IllegalArgumentException("invalid UUID");
        }
        if (!Set.of("running", "completed", "cancelled", "conflict", "failed", "outcome_unknown").contains(string(record, "status", 32)))
            throw new IllegalArgumentException("invalid status");
        long total = number(record, "totalCells", 1, 32768);
        for (String key : COUNTS) number(record, key, key.equals("totalCells") || key.equals("batchCells") ? 1 : 0,
                key.equals("batchCells") ? 128 : key.equals("leaseRemainingMs") ? 15000 : total);
        number(record, "recordedAtMillis", 1, Long.MAX_VALUE);
        for (String key : FLAGS) if (!record.has(key) || !record.get(key).isJsonPrimitive() || !record.getAsJsonPrimitive(key).isBoolean())
            throw new IllegalArgumentException("invalid flag");
    }
    private static String string(JsonObject value, String key, int limit) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("invalid string");
        String text = value.get(key).getAsString(); if (text.isBlank() || text.length() > limit) throw new IllegalArgumentException("invalid string length"); return text;
    }
    private static long number(JsonObject value, String key, long min, long max) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("invalid number");
        long n = value.get(key).getAsBigDecimal().longValueExact(); if (n < min || n > max) throw new IllegalArgumentException("number out of range"); return n;
    }
}
