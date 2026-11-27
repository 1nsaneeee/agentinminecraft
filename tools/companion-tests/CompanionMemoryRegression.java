package dev.mcagent;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class CompanionMemoryRegression {
    private static int checks;
    private interface Work { void run() throws Exception; }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }
    private static void rejects(Work action, String name) throws Exception {
        try { action.run(); }
        catch (java.io.IOException | IllegalArgumentException expected) { check(true, name); return; }
        throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "memory-fixture-");
        CompanionMemory store = new CompanionMemory(root);
        check(store.read() == null, "new save has no fabricated memory");
        JsonObject intent = Json.obj();
        intent.addProperty("intentId", UUID.randomUUID().toString());
        intent.addProperty("goal", "陪玩家探索湖边");
        intent.addProperty("reason", "玩家提出散步");
        intent.addProperty("progress", "尚未出发");
        intent.addProperty("stopConditions", "玩家叫停或走不通");
        intent.addProperty("status", "planned");
        JsonObject record = store.record(UUID.randomUUID().toString(), "body", UUID.randomUUID().toString(), true, intent);
        store.write(record);
        CompanionMemory reopened = new CompanionMemory(root);
        check(reopened.read().equals(record), "binding intent and paused flag survive reopening");
        check(reopened.saveId().equals(store.saveId()), "save identity stable in same directory");
        record.getAsJsonObject("intent").addProperty("progress", "到达湖边");
        store.write(record);
        check(store.read().getAsJsonObject("intent").get("progress").getAsString().equals("到达湖边"), "atomic replacement updates progress");
        Path file = root.resolve("data/mcagent-companion.json");
        String preserved = Files.readString(file);
        JsonObject invalid = record.deepCopy();
        invalid.getAsJsonObject("intent").addProperty("goal", "x".repeat(501));
        rejects(() -> store.write(invalid), "oversized goal rejected before replacement");
        check(Files.readString(file).equals(preserved), "invalid update preserves previous file");
        JsonObject completed = intent.deepCopy();
        completed.addProperty("status", "completed"); completed.addProperty("progress", "");
        rejects(() -> CompanionMemory.validateIntent(completed), "completion needs reported evidence");
        Path another = Files.createTempDirectory(Path.of(args[0]), "other-save-");
        Files.createDirectories(another.resolve("data"));
        Files.copy(file, another.resolve("data/mcagent-companion.json"));
        rejects(() -> new CompanionMemory(another).read(), "copied memory cannot silently cross save identity");
        Files.writeString(file, "{broken");
        rejects(store::read, "corrupt JSON fails explicitly");
        check(Files.readString(file).equals("{broken"), "failed read does not overwrite corrupt evidence");
        Files.writeString(file, "x".repeat(65537));
        rejects(store::read, "oversized saved record rejected");
        store.write(store.record(null, null, null, false, null));
        check(!store.read().has("intent"), "explicit release record contains no old intent");
        try (var files = Files.list(root.resolve("data"))) {
            check(files.noneMatch(p -> p.toString().endsWith(".tmp")), "atomic writes leave no temporary files");
        }
        System.out.println("MEMORY_CHECKS=" + checks);
    }
}
