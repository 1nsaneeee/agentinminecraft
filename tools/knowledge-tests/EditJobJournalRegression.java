package dev.mcagent;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

public final class EditJobJournalRegression {
    private static int checks;
    private static JsonObject receipt(String status) {
        JsonObject r = Json.obj();
        for (String key : java.util.List.of("jobId", "planId", "worldSessionId", "playerId")) r.addProperty(key, UUID.randomUUID().toString());
        r.addProperty("status", status); r.addProperty("reason", "regression fixture"); r.addProperty("dimension", "minecraft:overworld");
        r.addProperty("lastCommandOutcome", "not_dispatched");
        for (String key : java.util.List.of("processedCells", "changedCells", "skippedCells", "uncertainCells", "commandCalls")) r.addProperty(key, 0);
        r.addProperty("totalCells", 64); r.addProperty("batchCells", 4); r.addProperty("leaseRemainingMs", 15000);
        for (String key : java.util.List.of("undoRecorded", "undoPending", "partial")) r.addProperty(key, false);
        r.addProperty("verificationRequired", true); return r;
    }
    private interface IO { void run() throws Exception; }
    private static void rejects(String label, IO action) throws Exception {
        try { action.run(); } catch (java.io.IOException expected) { check(true, label); return; }
        throw new AssertionError(label);
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "edit-journal-");
        EditJobJournal journal = new EditJobJournal(root);
        Path file = root.resolve("data/mcagent-edit-jobs.json");
        check(journal.receipts().isEmpty() && !Files.exists(file), "absent journal does not manufacture data");
        JsonObject running = receipt("running"); journal.put(running);
        EditJobJournal loaded = new EditJobJournal(root);
        check(loaded.get(running.get("jobId").getAsString()).get("status").getAsString().equals("running"), "running checkpoint really persists");
        JsonObject historical = EditJobJournal.historical(loaded.get(running.get("jobId").getAsString()));
        check(historical.get("status").getAsString().equals("outcome_unknown") && !historical.get("executionResumable").getAsBoolean(), "interrupted checkpoint cannot revive execution");
        check(historical.get("leaseRemainingMs").getAsLong() == 0 && !historical.get("undoAvailableInCurrentSession").getAsBoolean(), "recovery does not restore leases or undo rights");
        loaded.get(running.get("jobId").getAsString()).addProperty("reason", "edited copy");
        check(!loaded.get(running.get("jobId").getAsString()).get("reason").getAsString().equals("edited copy"), "journal reads are isolated copies");
        JsonObject done = running.deepCopy(); done.addProperty("status", "completed"); done.addProperty("undoRecorded", true);
        journal.put(done); loaded = new EditJobJournal(root);
        check(EditJobJournal.historical(loaded.get(done.get("jobId").getAsString())).get("status").getAsString().equals("completed"), "terminal observation survives reload without replay");
        check(loaded.receipts().size() == 1, "updating a job does not duplicate receipt");
        try (var files = Files.list(file.getParent())) { check(files.noneMatch(p -> p.toString().endsWith(".tmp")), "atomic replacement leaves no temporary files"); }
        for (int i = 0; i < 20; i++) journal.put(receipt("cancelled"));
        check(journal.receipts().size() == 16 && journal.get(done.get("jobId").getAsString()) == null, "retention evicts oldest beyond sixteen");
        JsonObject bad = receipt("completed"); bad.addProperty("apiKey", "synthetic-do-not-persist");
        rejects("unknown fields and credential-shaped data rejected", () -> journal.put(bad));
        JsonObject badFlag = receipt("cancelled"); badFlag.addProperty("partial", "false");
        rejects("flags are not coerced", () -> journal.put(badFlag));
        JsonObject fraction = receipt("cancelled"); fraction.addProperty("processedCells", 1.5);
        rejects("fractional counters rejected", () -> journal.put(fraction));
        JsonObject overflow = receipt("cancelled"); overflow.addProperty("changedCells", 65);
        rejects("counter cannot exceed region", () -> journal.put(overflow));
        JsonObject invalidId = receipt("cancelled"); invalidId.addProperty("jobId", "1-1-1-1-1");
        rejects("noncanonical UUID rejected", () -> journal.put(invalidId));
        Path copied = Files.createTempDirectory(Path.of(args[0]), "edit-journal-copy-"); Files.createDirectories(copied.resolve("data"));
        Files.copy(file, copied.resolve("data/mcagent-edit-jobs.json"));
        rejects("copied journal does not silently cross save identity", () -> new EditJobJournal(copied));
        byte[] valid = Files.readAllBytes(file);
        Files.writeString(file, "{broken");
        rejects("corrupt input fails rather than becoming empty", () -> new EditJobJournal(root));
        byte[] corrupted = Files.readAllBytes(file);
        rejects("external changes cannot be silently overwritten", () -> journal.put(receipt("cancelled")));
        check(Arrays.equals(corrupted, Files.readAllBytes(file)), "failed write preserves corrupt evidence");
        Files.write(file, valid); Files.delete(file);
        rejects("removed journal is not silently recreated", () -> journal.put(receipt("cancelled")));
        Files.write(file, new byte[131073]);
        rejects("oversized journal is bounded before parsing", () -> new EditJobJournal(root));
        Path blocked = Files.createTempDirectory(Path.of(args[0]), "edit-journal-blocked-");
        EditJobJournal blockedJournal = new EditJobJournal(blocked); Files.writeString(blocked.resolve("data"), "blocker");
        rejects("unwritable data path fails explicitly", () -> blockedJournal.put(receipt("running")));
        check(blockedJournal.receipts().isEmpty() && Files.readString(blocked.resolve("data")).equals("blocker"), "failed persistence does not claim an in-memory receipt");
        Path appeared = Files.createTempDirectory(Path.of(args[0]), "edit-journal-appeared-");
        EditJobJournal emptyJournal = new EditJobJournal(appeared); Files.createDirectories(appeared.resolve("data"));
        Files.writeString(appeared.resolve("data/mcagent-edit-jobs.json"), "external evidence");
        rejects("unexpected file appearance is preserved", () -> emptyJournal.put(receipt("running")));
        System.out.println("EDIT_JOB_JOURNAL_CHECKS=" + checks);
    }
}
