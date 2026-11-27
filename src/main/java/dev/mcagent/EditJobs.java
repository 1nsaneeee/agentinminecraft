package dev.mcagent;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Server-thread-owned jobs. A job is a sequence of vanilla commands, never a transaction. */
public final class EditJobs {
    private static final long LEASE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
    private static final LinkedHashMap<String, Job> JOBS = new LinkedHashMap<>();
    private static Job current;
    private static EditJobJournal journal;
    private static String persistenceError;
    private EditJobs() {}

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final String planId, worldSession;
        final EditPlans.Plan plan;
        final ExternalTaskLease.Stamp taskStamp = ExternalTasks.current();
        final JsonObject externalTask = ExternalTasks.publicContext();
        final int batchCells;
        final List<EditHistory.Change> undo = new ArrayList<>();
        String status = "running", reason = "accepted; no cells executed yet";
        String lastCommandOutcome = "not_dispatched";
        int cursor, commandCalls, changed, skipped, uncertain, lastEventTick;
        long leaseUntil = System.nanoTime() + LEASE_NANOS;
        boolean undoRecorded;
        int persistedCursor = -1;
        String persistedStatus;
        Job(String planId, EditPlans.Plan plan, int batchCells) {
            this.planId = planId; this.plan = plan; this.batchCells = batchCells;
            worldSession = GameBridge.worldSessionId();
        }
        JsonObject snapshot() {
            JsonObject out = Json.obj();
            out.addProperty("jobId", id); out.addProperty("planId", planId); out.addProperty("worldSessionId", worldSession);
            out.addProperty("status", status); out.addProperty("reason", reason);
            if (externalTask != null) out.add("externalTask", externalTask.deepCopy());
            out.addProperty("dimension", plan.level().dimension().location().toString());
            out.addProperty("playerId", plan.playerId().toString());
            out.addProperty("totalCells", plan.before().size()); out.addProperty("processedCells", cursor);
            out.addProperty("changedCells", changed); out.addProperty("skippedCells", skipped); out.addProperty("uncertainCells", uncertain);
            out.addProperty("commandCalls", commandCalls); out.addProperty("batchCells", batchCells);
            out.addProperty("lastCommandOutcome", lastCommandOutcome);
            out.addProperty("leaseRemainingMs", status.equals("running") ? Math.max(0, (leaseUntil - System.nanoTime()) / 1_000_000) : 0);
            out.addProperty("undoRecorded", undoRecorded); out.addProperty("undoPending", status.equals("running") && !undo.isEmpty());
            out.addProperty("partial", !status.equals("running") && !status.equals("completed") && (!undo.isEmpty() || uncertain > 0));
            out.addProperty("verificationRequired", true);
            out.addProperty("receiptPersisted", persistenceError == null && cursor == persistedCursor && status.equals(persistedStatus));
            out.addProperty("lastPersistedProcessedCells", persistedCursor);
            return out;
        }
    }

    public static boolean active() { return current != null || UndoJobs.active(); }
    public static void clear() { JOBS.clear(); current = null; }

    public static void attach() {
        clear(); journal = null; persistenceError = null;
        try { journal = new EditJobJournal(GameBridge.server().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)); }
        catch (java.io.IOException | RuntimeException e) {
            persistenceError = "edit receipt journal unavailable; preserve the file and repair before starting a job";
            McAgent.LOG.error("[mcagent] edit receipt journal unavailable", e);
        }
    }
    public static void detach() { stopAll("world_detached"); clear(); journal = null; persistenceError = null; }

    private static boolean persist(Job job) {
        if (journal == null || persistenceError != null) return false;
        try {
            JsonObject record = job.snapshot(); record.remove("externalTask");
            journal.put(record); job.persistedCursor = job.cursor; job.persistedStatus = job.status; return true;
        } catch (java.io.IOException e) {
            persistenceError = "edit receipt persistence failed; preserve the file and inspect actual world state";
            McAgent.LOG.error("[mcagent] edit receipt persistence failed for {}", job.id, e); return false;
        }
    }

    static McpResult start(String planId, EditPlans.Plan plan, int batchCells) {
        if (active()) return McpResult.error("another edit job is active");
        if (batchCells < 1 || batchCells > 128) return McpResult.error("batchCells must be 1..128");
        if (journal == null || persistenceError != null) return McpResult.error(persistenceError == null ? "edit receipt journal not attached" : persistenceError);
        while (JOBS.size() >= 16) JOBS.remove(JOBS.keySet().iterator().next());
        Job job = new Job(planId, plan, batchCells);
        if (!persist(job)) return McpResult.error("job not started: " + persistenceError);
        JOBS.put(job.id, job); current = job;
        return McpResult.text(job.snapshot().toString());
    }

    public static void register(ToolRegistry registry) {
        registry.add(new McpTool() {
            public String name() { return "mc_edit_jobs"; }
            public String description() { return "List current jobs, historical save-local receipts and journal errors. Recover a lost start receipt by matching planId; never renews or replays work."; }
            public JsonObject inputSchema() { return ToolRegistry.schema(); }
            public McpResult call(JsonObject args) {
                return GameBridge.onServer(() -> {
                    JsonObject out = Json.obj(); out.addProperty("worldSessionId", GameBridge.worldSessionId());
                    out.addProperty("activeJobId", current == null ? null : current.id);
                    var jobs = Json.arr(); for (Job job : JOBS.values()) jobs.add(job.snapshot());
                    var remembered = Json.arr();
                    if (journal != null) for (var record : journal.receipts()) {
                        JsonObject receipt = record.getAsJsonObject();
                        if (!JOBS.containsKey(Json.str(receipt, "jobId", ""))) remembered.add(EditJobJournal.historical(receipt));
                    }
                    out.add("jobs", jobs); out.add("rememberedJobs", remembered); out.addProperty("persistenceError", persistenceError);
                    return McpResult.text(out.toString());
                }, 10000);
            }
        });
        JsonObject tokens = ToolRegistry.schema("jobId", "worldSessionId");
        ToolRegistry.strProp(tokens, "jobId", "Job id returned by mc_edit_start.");
        ToolRegistry.strProp(tokens, "worldSessionId", "Exact original world session. Old sessions may only query retained historical receipts.");
        for (String action : List.of("job", "keepalive", "cancel")) registry.add(new McpTool() {
            public String name() { return "mc_edit_" + action; }
            public String description() {
                return switch (action) {
                    case "job" -> "Read edit job progress or terminal result. Reading does not renew control. Missing/evicted receipts return outcome_unknown.";
                    case "keepalive" -> "Explicitly renew a running edit job's 15-second control lease. Cannot revive stopped/expired work. Requires current Work/write authorization.";
                    default -> "Cancel future batches immediately, retaining an undo record of changes already made. Works even after write permission is revoked; does not roll back completed writes.";
                };
            }
            public JsonObject inputSchema() { return tokens; }
            public McpResult call(JsonObject args) { return GameBridge.onServer(() -> control(action, args), 10000); }
        });
    }

    private static McpResult control(String action, JsonObject args) {
        Job job = JOBS.get(Json.str(args, "jobId", ""));
        if (action.equals("job") && job == null && journal != null) {
            JsonObject record = journal.get(Json.str(args, "jobId", ""));
            if (record != null && Json.str(record, "worldSessionId", "").equals(Json.str(args, "worldSessionId", "")))
                return McpResult.text(EditJobJournal.historical(record).toString());
        }
        if (!GameBridge.worldSessionId().equals(Json.str(args, "worldSessionId", ""))) return McpResult.error("job world session expired");
        if (job == null) {
            JsonObject missing = Json.obj(); missing.addProperty("status", "outcome_unknown");
            missing.addProperty("reason", "job not retained in this world; inspect actual state before retrying");
            return action.equals("job") ? McpResult.text(missing.toString()) : McpResult.error(missing.toString());
        }
        if (action.equals("job")) return McpResult.text(job.snapshot().toString());
        if (action.equals("cancel")) {
            if (job == current) finish(job, "cancelled", "controller_cancelled");
            return McpResult.text(job.snapshot().toString());
        }
        if (job != current) return McpResult.error("job is terminal and cannot be renewed");
        String unavailable = unavailable(job);
        if (unavailable != null) {
            finish(job, "cancelled", unavailable);
            return McpResult.error("job stopped: " + unavailable);
        }
        job.leaseUntil = System.nanoTime() + LEASE_NANOS;
        return McpResult.text(job.snapshot().toString());
    }

    private static String unavailable(Job job) {
        if (System.nanoTime() >= job.leaseUntil) return "control_lease_expired";
        if (!GameBridge.worldSessionId().equals(job.worldSession)) return "world_changed";
        if (AgentMode.isChat() || AgentMode.revision() != job.plan.modeRevision()) return "mode_changed";
        if (!AgentConfig.get().allowWrite) return "write_permission_revoked";
        if (GameBridge.server().getLevel(job.plan.level().dimension()) != job.plan.level()) return "dimension_unavailable";
        if (GameBridge.server().getPlayerList().getPlayer(job.plan.playerId()) == null) return "player_offline";
        return null;
    }

    public static void tick(MinecraftServer server) {
        Job job = current;
        if (job == null) return;
        try {
            String unavailable = unavailable(job);
            if (unavailable != null) { finish(job, "cancelled", unavailable); return; }
            long deadline = System.nanoTime() + 5_000_000;
            for (int batch = 0; batch < job.batchCells && job.cursor < job.plan.before().size(); batch++) {
                if (batch > 0 && System.nanoTime() >= deadline) break;
                unavailable = unavailable(job);
                if (unavailable != null) { finish(job, "cancelled", unavailable); return; }
                if (!step(job)) return;
            }
            if (job.cursor == job.plan.before().size()) { finish(job, "completed", "all_cells_processed; verify actual world state"); return; }
            if (server.getTickCount() - job.lastEventTick >= 20) {
                if (!persist(job)) { finish(job, "cancelled", "receipt_persistence_failed"); return; }
                job.lastEventTick = server.getTickCount();
            }
        } catch (RuntimeException e) {
            McAgent.LOG.warn("[mcagent] edit job {} stopped; inspect actual outcome", job.id, e);
            finish(job, "outcome_unknown", "job_exception; inspect world and undo history");
        }
    }

    private static boolean step(Job job) {
        EditHistory.Cell cell = job.plan.before().get(job.cursor);
        var level = job.plan.level();
        if (!level.isLoaded(cell.pos())) { finish(job, "cancelled", "target_chunk_unloaded"); return false; }
        if (!EditHistory.matches(level, cell)) { finish(job, "conflict", "target_changed_at_" + cell.pos().toShortString()); return false; }
        String mode = Json.str(job.plan.preview(), "mode", "replace");
        BlockPos min = job.plan.before().get(0).pos();
        BlockPos max = job.plan.before().get(job.plan.before().size() - 1).pos();
        BlockPos pos = cell.pos();
        boolean edge = pos.getX() == min.getX() || pos.getX() == max.getX() || pos.getY() == min.getY() || pos.getY() == max.getY() || pos.getZ() == min.getZ() || pos.getZ() == max.getZ();
        if ((mode.equals("outline") && !edge) || (mode.equals("keep") && !cell.oldState().isAir())) {
            job.skipped++; job.cursor++; return true;
        }
        boolean insideHollow = mode.equals("hollow") && !edge;
        String spec = insideHollow ? "minecraft:air" : Json.str(job.plan.preview(), "block", "");
        var proposed = insideHollow ? Blocks.AIR.defaultBlockState() : job.plan.proposed();
        if (!mode.equals("destroy") && !spec.contains("{") && cell.oldBlockEntity() == null && cell.oldState().equals(proposed)) {
            job.skipped++; job.cursor++; return true;
        }
        String command = "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + spec
                + (mode.equals("destroy") ? " destroy" : mode.equals("keep") ? " keep" : " replace");
        JsonObject receipt;
        job.commandCalls++;
        try {
            var player = GameBridge.server().getPlayerList().getPlayer(job.plan.playerId());
            receipt = ExternalTasks.command(job.taskStamp, () -> GameBridge.runCommandResult(command, player.getGameProfile().getName(), 4, level));
        } catch (RuntimeException e) {
            // The command may have changed the cell before throwing; preserve its original snapshot without inventing success.
            try { job.undo.add(EditHistory.recordedChange(level, cell)); }
            catch (RuntimeException inspectionError) {
                job.undo.add(new EditHistory.Change(cell, null)); e.addSuppressed(inspectionError);
            }
            job.uncertain++;
            throw e;
        }
        try {
            EditHistory.Change change = EditHistory.recordedChange(level, cell);
            if (change.changed()) { job.undo.add(change); job.changed++; }
        } catch (RuntimeException e) { job.undo.add(new EditHistory.Change(cell, null)); job.uncertain++; throw e; }
        job.lastCommandOutcome = Json.str(receipt, "outcome", "outcome_unknown"); job.cursor++;
        if (!Json.b(receipt, "dispatched", false) || receipt.get("failedCallbacks").getAsLong() > 0) {
            finish(job, "failed", "command_rejected_at_" + pos.toShortString()); return false;
        }
        if (!job.lastCommandOutcome.equals("succeeded")) {
            finish(job, "outcome_unknown", "missing_command_completion_at_" + pos.toShortString()); return false;
        }
        return true;
    }

    private static void finish(Job job, String status, String reason) {
        if (!job.status.equals("running")) return;
        job.status = status; job.reason = reason;
        if (!job.undo.isEmpty()) {
            EditHistory.pushRecorded("edit job " + job.id + " (" + status + ")", job.plan.level(), job.undo);
            job.undoRecorded = true;
        }
        if (current == job) current = null;
        persist(job);
    }

    public static void stopAll(String reason) { if (current != null) finish(current, "cancelled", reason); }
}
