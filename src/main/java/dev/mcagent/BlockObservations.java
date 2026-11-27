package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Read-only, world-scoped NBT conditions. Only sampling touches Minecraft, on its server thread. */
public final class BlockObservations {
    private static final int MAX_ACTIVE = 8, MAX_RECENT = 16;
    private static final LinkedHashMap<String, Observation> TASKS = new LinkedHashMap<>();
    private static final Semaphore WAITERS = new Semaphore(16);

    private BlockObservations() {}

    private static final class Observation {
        final String id = UUID.randomUUID().toString();
        final String world = GameBridge.worldSessionId();
        final JsonObject externalTask = ExternalTasks.publicContext();
        final long modeRevision = AgentMode.revision(), started = System.nanoTime(), deadline;
        final String taskId, pathText, comparison;
        final ServerLevel level;
        final BlockPos pos;
        final NbtPathArgument.NbtPath path;
        final Tag expected;
        final BlockEntity target;
        final CompletableFuture<JsonObject> done = new CompletableFuture<>();
        String outcome = "waiting", reason = "condition_not_met";
        JsonObject lastSample;
        long finishedAt;

        Observation(String taskId, ServerLevel level, BlockPos pos, String pathText,
                    NbtPathArgument.NbtPath path, String comparison, Tag expected, int timeoutMs) {
            this.taskId = taskId; this.level = level; this.pos = pos; this.pathText = pathText;
            this.path = path; this.comparison = comparison; this.expected = expected;
            target = level.getBlockEntity(pos);
            deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }

        JsonObject snapshot() {
            JsonObject out = Json.obj();
            out.addProperty("observationId", id); out.addProperty("taskId", taskId);
            out.addProperty("worldSessionId", world); out.addProperty("outcome", outcome);
            if (externalTask != null) out.add("externalTask", externalTask.deepCopy());
            out.addProperty("matched", outcome.equals("matched")); out.addProperty("reason", reason);
            out.addProperty("dimension", level.dimension().location().toString()); out.addProperty("pos", pos.toShortString());
            out.addProperty("path", pathText); out.addProperty("comparison", comparison);
            if (expected != null) out.addProperty("expectedSnbt", expected.toString());
            out.addProperty("elapsedMs", TimeUnit.NANOSECONDS.toMillis((finishedAt == 0 ? System.nanoTime() : finishedAt) - started));
            out.addProperty("remainingMs", done.isDone() ? 0 : Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            out.addProperty("terminal", !outcome.equals("waiting"));
            out.addProperty("executionResumable", false);
            out.add("lastSample", lastSample == null ? null : lastSample.deepCopy());
            return out;
        }
    }

    public static void register(ToolRegistry registry) {
        JsonObject schema = ToolRegistry.schema("action", "worldSessionId");
        ToolRegistry.strProp(schema, "action", "start, status, cancel or list. Read-only condition observation, never a repair command.");
        ToolRegistry.strProp(schema, "worldSessionId", "Current mc_status worldSessionId; stale worlds are rejected.");
        ToolRegistry.strProp(schema, "observationId", "Handle returned by start; required for status/cancel.");
        ToolRegistry.strProp(schema, "taskId", "start: explicit caller correlation id, 1-96 ASCII letters/digits/._:-; unique in retained world records. Not authentication.");
        for (String axis : List.of("x", "y", "z")) {
            ToolRegistry.intProp(schema, axis, "start: target block coordinate.");
            bounds(schema, axis, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
        ToolRegistry.strProp(schema, "dimension", "start: dimension id; defaults to the first player's dimension.");
        ToolRegistry.strProp(schema, "path", "start: vanilla NBT path from actual mc_block_entity data, e.g. Items[{Slot:2b,id:\"minecraft:iron_ingot\"}].count.");
        ToolRegistry.strProp(schema, "comparison", "start: exists, equals (typed SNBT equality) or at_least (numeric).");
        ToolRegistry.strProp(schema, "expectedSnbt", "start: one SNBT value for equals/at_least; omit for exists.");
        ToolRegistry.intProp(schema, "timeoutMs", "start: total observation deadline, default 60000, range 100..3600000 ms.");
        bounds(schema, "timeoutMs", 100, 3600000);
        registry.add(new McpTool() {
            public String name() { return "mc_observe"; }
            public String description() { return "Start/query/cancel/list a task-correlated, read-only NBT condition. "
                    + "Samples only loaded block entities once per second; use mc_observe_wait instead of polling the model. "
                    + "A match proves the specified predicate, not that the player's whole task is complete. Re-read real state."; }
            public JsonObject inputSchema() { return schema; }
            public McpResult call(JsonObject args) {
                return GameBridge.onServer(() -> {
                    try { return McpResult.text(control(args).toString()); }
                    catch (IllegalArgumentException | CommandSyntaxException error) { return McpResult.error(error.getMessage()); }
                }, 10000);
            }
        });
        JsonObject wait = ToolRegistry.schema("observationId", "worldSessionId");
        ToolRegistry.strProp(wait, "observationId", "Observation handle from mc_observe(action=start).");
        ToolRegistry.strProp(wait, "worldSessionId", "Original observation's worldSessionId.");
        ToolRegistry.intProp(wait, "timeoutMs", "Maximum time for this tool call, default 10000, range 50..30000 ms. Does not renew or cancel the observation.");
        bounds(wait, "timeoutMs", 50, 30000);
        registry.add(new McpTool() {
            public String name() { return "mc_observe_wait"; }
            public String description() { return "Wait for a registered NBT condition to reach matched/timed_out/cancelled/unavailable/failed. "
                    + "A shorter call timeout returns waiting with waitTimedOut=true; it is not success. No world reads on the HTTP thread. "
                    + "Keep this wait below your MCP client's request timeout. Old worlds cannot be resumed."; }
            public JsonObject inputSchema() { return wait; }
            public McpResult call(JsonObject args) throws Exception {
                int timeout = Json.i(args, "timeoutMs", 10000);
                if (timeout < 50 || timeout > 30000) return McpResult.error("wait timeoutMs must be 50..30000");
                Observation observation;
                try {
                    observation = GameBridge.onServer(() -> {
                        synchronized (BlockObservations.class) { checkWorld(args); return find(args); }
                    }, 10000);
                } catch (IllegalArgumentException error) { return McpResult.error(error.getMessage()); }
                if (observation.level.getServer().isSameThread() && !observation.done.isDone()) return McpResult.error("pending observation waits require an external caller, not the server thread");
                if (!WAITERS.tryAcquire()) return McpResult.error("too many observation waiters; maximum 16");
                try { return McpResult.text(observation.done.get(timeout, TimeUnit.MILLISECONDS).toString()); }
                catch (TimeoutException timeoutError) {
                    synchronized (BlockObservations.class) {
                        expire(observation);
                        trim();
                        JsonObject out = observation.snapshot(); out.addProperty("waitTimedOut", !observation.done.isDone());
                        return McpResult.text(out.toString());
                    }
                } finally { WAITERS.release(); }
            }
        });
    }

    private static void bounds(JsonObject schema, String property, int minimum, int maximum) {
        JsonObject definition = schema.getAsJsonObject("properties").getAsJsonObject(property);
        definition.addProperty("minimum", minimum); definition.addProperty("maximum", maximum);
    }

    private static void checkWorld(JsonObject args) {
        if (!GameBridge.worldSessionId().equals(Json.str(args, "worldSessionId", ""))) throw new IllegalArgumentException("observation world changed; do not replay the old task");
    }

    private static Observation find(JsonObject args) {
        Observation task = TASKS.get(Json.str(args, "observationId", ""));
        if (task == null) throw new IllegalArgumentException("observation missing or no longer retained; do not infer completion");
        expire(task);
        return task;
    }

    private static synchronized JsonObject control(JsonObject args) throws CommandSyntaxException {
        checkWorld(args);
        for (Observation task : TASKS.values()) expire(task);
        trim();
        String action = Json.str(args, "action", "");
        if (action.equals("list")) {
            JsonObject out = Json.obj(); JsonArray tasks = Json.arr();
            for (Observation task : TASKS.values()) tasks.add(task.snapshot());
            out.addProperty("worldSessionId", GameBridge.worldSessionId()); out.add("observations", tasks); return out;
        }
        if (action.equals("status") || action.equals("cancel")) {
            Observation task = find(args);
            if (action.equals("cancel")) { finish(task, "cancelled", "caller_cancelled"); trim(); }
            return task.snapshot();
        }
        if (!action.equals("start")) throw new IllegalArgumentException("action must be start/status/cancel/list");
        String taskId = Json.str(args, "taskId", "");
        if (!taskId.matches("[A-Za-z0-9._:-]{1,96}")) throw new IllegalArgumentException("start requires a bounded taskId");
        for (Observation task : TASKS.values()) if (task.taskId.equals(taskId)) throw new IllegalArgumentException("taskId already retained; query list/status instead of duplicating it");
        if (TASKS.values().stream().filter(task -> !task.done.isDone()).count() >= MAX_ACTIVE) throw new IllegalArgumentException("observation capacity reached (8 active)");
        for (String axis : List.of("x", "y", "z")) if (!args.has(axis)) throw new IllegalArgumentException("start requires x/y/z");
        String pathText = Json.str(args, "path", "").trim(), comparison = Json.str(args, "comparison", "");
        if (pathText.isEmpty() || pathText.length() > 512) throw new IllegalArgumentException("path must contain 1..512 characters");
        if (!List.of("exists", "equals", "at_least").contains(comparison)) throw new IllegalArgumentException("comparison must be exists/equals/at_least");
        StringReader pathReader = new StringReader(pathText);
        NbtPathArgument.NbtPath path;
        try { path = NbtPathArgument.nbtPath().parse(pathReader); }
        catch (IndexOutOfBoundsException error) {
            // Vanilla 1.21.1 peeks beyond the reader for an unfinished bracket, e.g. Items[.
            throw new IllegalArgumentException("incomplete NBT path", error);
        }
        if (pathReader.canRead()) throw new IllegalArgumentException("trailing NBT path data");
        String expectedText = Json.str(args, "expectedSnbt", ""); Tag expected = null;
        if (comparison.equals("exists")) {
            if (!expectedText.isEmpty()) throw new IllegalArgumentException("exists does not accept expectedSnbt");
        } else {
            if (expectedText.isBlank() || expectedText.length() > 8192) throw new IllegalArgumentException("expectedSnbt must contain 1..8192 characters");
            StringReader reader = new StringReader(expectedText);
            try { expected = new TagParser(reader).readValue(); }
            catch (IndexOutOfBoundsException error) { throw new IllegalArgumentException("incomplete expectedSnbt", error); }
            reader.skipWhitespace();
            if (reader.canRead()) throw new IllegalArgumentException("trailing expectedSnbt data");
            if (comparison.equals("at_least") && (!(expected instanceof NumericTag number) || !Double.isFinite(number.getAsDouble()))) throw new IllegalArgumentException("at_least requires finite numeric SNBT");
        }
        int timeout = Json.i(args, "timeoutMs", 60000);
        if (timeout < 100 || timeout > 3600000) throw new IllegalArgumentException("observation timeoutMs must be 100..3600000");
        ServerLevel level;
        if (args.has("dimension")) {
            ResourceLocation dimension = ResourceLocation.tryParse(Json.str(args, "dimension", ""));
            level = dimension == null ? null : GameBridge.server().getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        } else level = GameBridge.player(null).serverLevel();
        if (level == null) throw new IllegalArgumentException("dimension unavailable");
        BlockPos pos = new BlockPos(Json.i(args, "x", 0), Json.i(args, "y", 0), Json.i(args, "z", 0));
        if (!level.isLoaded(pos) || level.getBlockEntity(pos) == null) throw new IllegalArgumentException("start requires a loaded block entity");
        Observation task = new Observation(taskId, level, pos, pathText, path, comparison, expected, timeout);
        TASKS.put(task.id, task); sample(task); trim(); return task.snapshot();
    }

    private static void sample(Observation task) {
        if (task.done.isDone()) return;
        if (AgentMode.revision() != task.modeRevision) { finish(task, "cancelled", "mode_changed"); return; }
        if (GameBridge.server().getLevel(task.level.dimension()) != task.level || !task.level.isLoaded(task.pos)) { finish(task, "unavailable", "dimension_or_chunk_unloaded"); return; }
        var blockEntity = task.level.getBlockEntity(task.pos);
        if (blockEntity == null) { finish(task, "unavailable", "block_entity_removed"); return; }
        if (blockEntity != task.target) { finish(task, "unavailable", "block_entity_replaced"); return; }
        try {
            var nbt = blockEntity.saveWithFullMetadata(task.level.registryAccess());
            if (nbt.toString().length() > 262144) throw new IllegalArgumentException("observation NBT exceeds 256 KiB characters");
            List<Tag> values = task.path.countMatching(nbt) == 0 ? List.of() : task.path.get(nbt);
            if (values.size() > 512) throw new IllegalArgumentException("path selects more than 512 values; narrow the path");
            JsonObject observed = Json.obj(); JsonArray summaries = Json.arr();
            observed.addProperty("sampleTick", task.level.getGameTime()); observed.addProperty("block", Tools.describe(task.level.getBlockState(task.pos)));
            observed.addProperty("blockEntityType", nbt.getString("id")); observed.addProperty("valueCount", values.size());
            boolean truncated = values.size() > 16;
            for (int i = 0; i < Math.min(16, values.size()); i++) {
                String text = values.get(i).toString(); truncated |= text.length() > 512;
                summaries.add(text.length() > 512 ? text.substring(0, 512) : text);
            }
            observed.add("valuesSnbt", summaries); observed.addProperty("valuesTruncated", truncated); task.lastSample = observed;
            boolean matched = false;
            for (Tag value : values) {
                if (task.comparison.equals("exists")) matched = true;
                else if (task.comparison.equals("equals")) matched |= value.equals(task.expected);
                else {
                    if (!(value instanceof NumericTag number) || !Double.isFinite(number.getAsDouble())) throw new IllegalArgumentException("at_least path selected a non-finite or non-numeric value");
                    // Long machine-energy counters must not lose precision through a double conversion.
                    matched |= new java.math.BigDecimal(number.getAsNumber().toString()).compareTo(
                            new java.math.BigDecimal(((NumericTag) task.expected).getAsNumber().toString())) >= 0;
                }
            }
            if (matched) finish(task, "matched", "specified_nbt_condition_met");
        } catch (CommandSyntaxException | RuntimeException error) {
            McAgent.LOG.warn("[mcagent] block observation {} failed", task.id, error);
            finish(task, "failed", "sampling_or_condition_failed; inspect logs and actual NBT");
        }
    }

    private static void expire(Observation task) {
        if (!task.done.isDone() && AgentMode.revision() != task.modeRevision) finish(task, "cancelled", "mode_changed");
        if (!task.done.isDone() && System.nanoTime() >= task.deadline) finish(task, "timed_out", "observation_deadline_elapsed");
    }

    private static void finish(Observation task, String outcome, String reason) {
        if (task.done.isDone()) return;
        task.outcome = outcome; task.reason = reason;
        task.finishedAt = System.nanoTime();
        JsonObject snapshot = task.snapshot(); snapshot.addProperty("remainingMs", 0);
        task.done.complete(snapshot);
    }

    private static void trim() {
        int finished = (int) TASKS.values().stream().filter(task -> task.done.isDone()).count();
        while (finished > MAX_RECENT) {
            Observation oldest = TASKS.values().stream().filter(task -> task.done.isDone())
                    .min(java.util.Comparator.comparingLong(task -> task.finishedAt)).orElseThrow();
            TASKS.remove(oldest.id); finished--;
        }
    }

    public static synchronized void tick(MinecraftServer server) {
        for (Observation task : new ArrayList<>(TASKS.values())) {
            expire(task);
            if (server.getTickCount() % 20 == 0) sample(task);
        }
        trim();
    }

    public static synchronized void stopAll(String reason) {
        for (Observation task : TASKS.values()) finish(task, "cancelled", reason);
        trim();
    }

    public static synchronized void clear() { stopAll("world_detached"); TASKS.clear(); }
}
