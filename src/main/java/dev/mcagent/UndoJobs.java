package dev.mcagent;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Server-thread-owned undo. Cells are checked and restored in bounded ticks. */
public final class UndoJobs {
    private static final int MAX_RECEIPTS = 16;
    private static final int MAX_CONFLICT_POSITIONS = 16;
    private static final long LEASE_NANOS = 15_000_000_000L;
    private static final long TICK_BUDGET_NANOS = 5_000_000L;
    private static final LinkedHashMap<String, Job> JOBS = new LinkedHashMap<>();
    private static Job current;

    private enum Phase { CHECKING, RESTORING, VERIFYING }

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String worldSessionId = GameBridge.worldSessionId();
        final String controlEpoch = AgentControl.epoch();
        final long modeRevision = AgentMode.revision();
        final String recordId;
        final String dimension;
        final int totalCells;
        final boolean skipConflicts;
        final int batchCells;
        final BitSet conflicts = new BitSet();
        final BitSet restored = new BitSet();
        final BitSet uncertain = new BitSet();
        final com.google.gson.JsonArray conflictPositions = Json.arr();
        final JsonObject externalTask = ExternalTasks.publicContext();
        ExternalTaskLease.Stamp taskStamp = ExternalTasks.current();
        EditHistory.Operation operation;
        Phase phase = Phase.CHECKING;
        String status = "running";
        String reason = "accepted";
        String remainingRecordId;
        String stopReason;
        boolean inStep;
        boolean dispatchedUncertain;
        int cursor;
        int dispatchedIndex = -1;
        int checkedCells;
        int verifiedCells;
        int postRestoreConflicts;
        Integer remainingCells;
        int lastEventTick = GameBridge.server().getTickCount();
        long leaseUntil = System.nanoTime() + LEASE_NANOS;

        Job(EditHistory.Operation operation, boolean skipConflicts, int batchCells) {
            this.operation = operation;
            this.recordId = operation.id;
            this.remainingRecordId = recordId;
            this.dimension = operation.level.dimension().location().toString();
            this.totalCells = operation.size();
            this.remainingCells = totalCells;
            this.skipConflicts = skipConflicts;
            this.batchCells = batchCells;
        }

        Integer projectedRemainingCells() {
            BitSet settled = (BitSet) restored.clone();
            settled.andNot(uncertain);
            return totalCells - settled.cardinality();
        }

        JsonObject snapshot() {
            JsonObject out = Json.obj();
            out.addProperty("jobId", id);
            out.addProperty("recordId", recordId);
            out.addProperty("worldSessionId", worldSessionId);
            out.addProperty("dimension", dimension);
            out.addProperty("policy", skipConflicts ? "skip_conflicts" : "stop");
            out.addProperty("status", status);
            out.addProperty("phase", phase.name().toLowerCase(Locale.ROOT));
            out.addProperty("reason", reason);
            out.addProperty("totalCells", totalCells);
            out.addProperty("checkedCells", checkedCells);
            out.addProperty("restoredCells", restored.cardinality());
            out.addProperty("verifiedCells", verifiedCells);
            out.addProperty("remainingCells", status.equals("running") ? projectedRemainingCells() : remainingCells);
            out.addProperty("remainingRecordId", remainingRecordId);
            out.addProperty("conflictCells", conflicts.cardinality());
            out.addProperty("postRestoreConflictCells", postRestoreConflicts);
            out.add("conflictPositions", conflictPositions.deepCopy());
            out.addProperty("uncertainCells", uncertain.cardinality());
            out.addProperty("partial", !status.equals("running") && !status.equals("completed") && (!restored.isEmpty() || dispatchedUncertain));
            out.addProperty("batchCells", batchCells);
            out.addProperty("leaseRemainingMs", status.equals("running") ? Math.max(0, (leaseUntil - System.nanoTime()) / 1_000_000) : 0);
            out.addProperty("verificationRequired", true);
            out.addProperty("executionResumable", false);
            if (externalTask != null) out.add("externalTask", externalTask.deepCopy());
            return out;
        }
    }

    private UndoJobs() {}

    public static boolean active() { return current != null; }

    public static void clear() {
        stopAll("world_detached");
        JOBS.clear();
    }

    public static void stopAll(String reason) {
        Job job = current;
        if (job == null) return;
        if (job.inStep) {
            // Synchronous block or NBT callbacks can stop this job during a cell.
            // Settle that cell's readback before accounting for the residual.
            if (job.stopReason == null) job.stopReason = reason;
        } else {
            finish(job, "cancelled", reason);
        }
    }

    public static void register(ToolRegistry registry) {
        add(registry, "mc_undo_records", "List current-session undo records newest first and recent batch receipts. Does not renew a lease.",
                ToolRegistry.schema(), args -> records());
        JsonObject start = ToolRegistry.schema("recordId", "worldSessionId");
        ToolRegistry.strProp(start, "recordId", "Exact recordId of the latest record returned by mc_undo_records.");
        ToolRegistry.strProp(start, "worldSessionId", "Exact worldSessionId returned by mc_undo_records.");
        ToolRegistry.strProp(start, "policy", "stop (default) or skip_conflicts. Skips only verified block/NBT mismatches.");
        var policies = Json.arr();
        policies.add("stop"); policies.add("skip_conflicts");
        start.getAsJsonObject("properties").getAsJsonObject("policy").add("enum", policies);
        ToolRegistry.intProp(start, "batchCells", "Cells checked or restored per tick, 1..128, default 32.");
        var batchCells = start.getAsJsonObject("properties").getAsJsonObject("batchCells");
        batchCells.addProperty("minimum", 1);
        batchCells.addProperty("maximum", 128);
        add(registry, "mc_undo_start", "Start a checked, cancellable undo for exactly one current history record. "
                + "Preflights the whole record before writing. Renew with mc_undo_keepalive within 15 seconds. "
                + "Inspect terminal status and actual world state.", start, UndoJobs::start);

        JsonObject job = ToolRegistry.schema("jobId", "worldSessionId");
        ToolRegistry.strProp(job, "jobId", "Job id returned by mc_undo_start.");
        ToolRegistry.strProp(job, "worldSessionId", "Exact original world session of the job.");
        add(registry, "mc_undo_job", "Read an undo job or terminal receipt. Reading does not renew its lease.", job, args -> control("job", args));
        add(registry, "mc_undo_keepalive", "Renew a running undo job's 15-second lease. Does not resume stopped jobs.", job, args -> control("keepalive", args));
        add(registry, "mc_undo_cancel", "Stop future undo cells and retain unresolved changes. Does not reverse cells already restored.", job, args -> control("cancel", args));
    }

    private interface Operation { McpResult call(JsonObject args); }

    private static void add(ToolRegistry registry, String name, String description, JsonObject schema, Operation operation) {
        registry.add(new McpTool() {
            public String name() { return name; }
            public String description() { return description; }
            public JsonObject inputSchema() { return schema; }
            public McpResult call(JsonObject args) { return GameBridge.onServer(() -> operation.call(args), 10000); }
        });
    }

    private static McpResult records() {
        JsonObject out = Json.obj();
        out.addProperty("worldSessionId", GameBridge.worldSessionId());
        out.addProperty("activeJobId", current == null ? null : current.id);
        var records = Json.arr();
        for (EditHistory.Operation operation : EditHistory.records()) {
            JsonObject record = Json.obj();
            record.addProperty("recordId", operation.id);
            record.addProperty("label", operation.label);
            record.addProperty("dimension", operation.level.dimension().location().toString());
            record.addProperty("totalCells", operation.size());
            records.add(record);
        }
        out.add("records", records);
        var jobs = Json.arr();
        for (Job job : JOBS.values()) jobs.add(job.snapshot());
        out.add("jobs", jobs);
        return McpResult.text(out.toString());
    }

    private static McpResult start(JsonObject args) {
        if (EditJobs.active() || active()) return McpResult.error("a managed edit or undo job is active");
        if (!GameBridge.worldSessionId().equals(Json.str(args, "worldSessionId", ""))) return McpResult.error("undo world session expired");
        if (AgentMode.isChat() || !AgentConfig.get().allowWrite) return McpResult.error("undo requires Work and allowWrite=true");
        String policy = Json.str(args, "policy", "stop");
        if (!policy.equals("stop") && !policy.equals("skip_conflicts")) return McpResult.error("invalid undo conflict policy");
        int batch = Json.i(args, "batchCells", 32);
        if (batch < 1 || batch > 128) return McpResult.error("batchCells must be 1..128");
        EditHistory.Operation operation = EditHistory.pinHead(Json.str(args, "recordId", ""));
        if (operation == null) return McpResult.error("undo record is not the current history head");
        Job job = new Job(operation, policy.equals("skip_conflicts"), batch);
        while (JOBS.size() >= MAX_RECEIPTS) JOBS.remove(JOBS.keySet().iterator().next());
        JOBS.put(job.id, job);
        current = job;
        return McpResult.text(job.snapshot().toString());
    }

    private static McpResult control(String action, JsonObject args) {
        if (!GameBridge.worldSessionId().equals(Json.str(args, "worldSessionId", ""))) return McpResult.error("undo world session expired");
        Job job = JOBS.get(Json.str(args, "jobId", ""));
        if (job == null) return McpResult.error("undo job is not retained in this world");
        if (action.equals("job")) return McpResult.text(job.snapshot().toString());
        if (job != current) {
            return action.equals("cancel") ? McpResult.text(job.snapshot().toString())
                    : McpResult.error("undo job is terminal and cannot be renewed");
        }
        if (!Objects.equals(job.taskStamp, ExternalTasks.current())) return McpResult.error("undo job belongs to another external task");
        if (action.equals("cancel")) {
            stopAll("controller_cancelled");
            return McpResult.text(job.snapshot().toString());
        }
        String unavailable = unavailable(job);
        if (unavailable != null) {
            stopAll(unavailable);
            return McpResult.error("undo job stopped: " + unavailable);
        }
        job.leaseUntil = System.nanoTime() + LEASE_NANOS;
        return McpResult.text(job.snapshot().toString());
    }

    private static String unavailable(Job job) {
        if (job.stopReason != null) return job.stopReason;
        if (System.nanoTime() >= job.leaseUntil) return "control_lease_expired";
        if (!GameBridge.worldSessionId().equals(job.worldSessionId)) return "world_changed";
        if (AgentMode.isChat() || AgentMode.revision() != job.modeRevision) return "mode_changed";
        if (!AgentConfig.get().allowWrite) return "write_permission_revoked";
        if (GameBridge.server().getLevel(job.operation.level.dimension()) != job.operation.level) return "dimension_unavailable";
        if (!EditHistory.isHead(job.operation)) return "history_head_changed";
        try { AgentControl.requireActive(job.controlEpoch); }
        catch (IllegalStateException rejected) { return "agent_control_changed"; }
        try { ExternalTasks.validate(job.taskStamp); }
        catch (IllegalStateException rejected) { return "external_task_unavailable"; }
        return null;
    }

    public static void tick(MinecraftServer server) {
        Job job = current;
        if (job == null || job.inStep) return;
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        try {
            for (int i = 0; i < job.batchCells && current == job; i++) {
                if (i > 0 && System.nanoTime() >= deadline) break;
                String unavailable = unavailable(job);
                if (unavailable != null) { finish(job, "cancelled", unavailable); return; }
                job.inStep = true;
                try {
                    switch (job.phase) {
                        case CHECKING -> check(job);
                        case RESTORING -> restore(job);
                        case VERIFYING -> verify(job);
                    }
                } finally { job.inStep = false; }
                if (current == job && job.stopReason != null) { finish(job, "cancelled", job.stopReason); return; }
            }
            if (current == job && server.getTickCount() - job.lastEventTick >= 20) {
                job.lastEventTick = server.getTickCount();
            }
        } catch (RuntimeException error) {
            if (job.dispatchedIndex >= 0) {
                job.uncertain.set(job.dispatchedIndex);
                job.dispatchedUncertain = true;
            }
            McAgent.LOG.warn("[mcagent] undo job {} stopped; inspect actual outcome", job.id, error);
            if (current == job) finish(job, "outcome_unknown", "undo_exception");
        }
    }

    private static void check(Job job) {
        int index = job.cursor++;
        var change = job.operation.cells.get(index);
        job.checkedCells++;
        if (change.after() == null) {
            job.uncertain.set(index);
            finish(job, "outcome_unknown", "missing_after_snapshot");
            return;
        }
        if (!EditHistory.loaded(job.operation.level, change.before().pos())) {
            finish(job, "failed", "target_chunk_unloaded");
            return;
        }
        if (!EditHistory.matches(job.operation.level, change.after())) conflict(job, index);
        if (job.cursor != job.totalCells || job.stopReason != null) return;
        if (!job.skipConflicts && !job.conflicts.isEmpty()) {
            finish(job, "conflict", "preflight_conflict");
            return;
        }
        job.phase = Phase.RESTORING;
        job.cursor = job.totalCells - 1;
    }

    private static void restore(Job job) {
        int index = job.cursor--;
        if (!job.conflicts.get(index)) {
            var level = job.operation.level;
            var change = job.operation.cells.get(index);
            if (!EditHistory.loaded(level, change.before().pos())) {
                finish(job, "failed", "target_chunk_unloaded");
                return;
            }
            if (EditHistory.restoreCell(level, change.before(), () -> beforeWrite(job, index, change))) {
                job.restored.set(index);
                job.dispatchedIndex = -1;
            }
        }
        if (current != job || job.stopReason != null || job.cursor >= 0) return;
        job.phase = Phase.VERIFYING;
        job.cursor = job.restored.nextSetBit(0);
        if (job.cursor < 0) finishVerification(job);
    }

    private static boolean beforeWrite(Job job, int index, EditHistory.Change change) {
        var level = job.operation.level;
        if (!EditHistory.loaded(level, change.before().pos())) {
            finish(job, "failed", "target_chunk_unloaded");
            return false;
        }
        boolean matches = EditHistory.matches(level, change.after());
        // NBT reconstruction and comparison can invoke mod callbacks. Check
        // the boundary again after them, immediately before the world write.
        String unavailable = unavailable(job);
        if (unavailable != null) {
            finish(job, "cancelled", unavailable);
            return false;
        }
        if (!matches) {
            conflict(job, index);
            if (!job.skipConflicts) finish(job, "conflict", "restoration_conflict");
            return false;
        }
        job.dispatchedIndex = index;
        return true;
    }

    private static void verify(Job job) {
        int index = job.cursor;
        var before = job.operation.cells.get(index).before();
        if (!EditHistory.loaded(job.operation.level, before.pos())) {
            retainUnverifiedRestores(job, index);
            finish(job, "outcome_unknown", "verification_chunk_unloaded");
            return;
        }
        boolean matches;
        try { matches = EditHistory.matches(job.operation.level, before); }
        catch (RuntimeException error) {
            retainUnverifiedRestores(job, index);
            throw error;
        }
        if (matches) job.verifiedCells++;
        else {
            job.uncertain.set(index);
            conflict(job, index);
            job.postRestoreConflicts++;
        }
        if (job.stopReason != null) return;
        job.cursor = job.restored.nextSetBit(index + 1);
        if (job.cursor < 0) finishVerification(job);
    }

    private static void retainUnverifiedRestores(Job job, int first) {
        for (int index = job.restored.nextSetBit(first); index >= 0; index = job.restored.nextSetBit(index + 1)) {
            job.uncertain.set(index);
        }
    }

    private static void conflict(Job job, int index) {
        if (job.conflicts.get(index)) return;
        job.conflicts.set(index);
        if (job.conflictPositions.size() < MAX_CONFLICT_POSITIONS)
            job.conflictPositions.add(job.operation.cells.get(index).before().pos().toShortString());
    }

    private static void finishVerification(Job job) {
        if (job.postRestoreConflicts > 0) finish(job, "conflict", "restored_cells_changed_during_verification");
        else if (!job.conflicts.isEmpty()) finish(job, "conflict", "conflicts_retained");
        else finish(job, "completed", "restored_and_verified");
    }

    private static void finish(Job job, String status, String reason) {
        try {
            EditHistory.Operation remaining = EditHistory.finishPinned(job.operation, job.restored, job.uncertain);
            job.remainingRecordId = remaining == null ? null : remaining.id;
            job.remainingCells = remaining == null ? 0 : remaining.size();
        } catch (RuntimeException error) {
            McAgent.LOG.warn("[mcagent] undo job {} could not settle its history", job.id, error);
            status = "outcome_unknown";
            reason = "history_settlement_failed";
            job.remainingRecordId = null;
            job.remainingCells = null;
        }
        job.status = status;
        job.reason = reason;
        job.operation = null;
        job.taskStamp = null;
        if (current == job) current = null;
    }
}
