package dev.mcagent.qa;

import com.google.gson.JsonObject;
import dev.mcagent.*;
import dev.mcagent.fakeplayer.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Optional test mod: exercises real game objects through an operator command. */
@Mod("mcagent_qa")
public final class LiveRegression {
    private final List<String> results = new ArrayList<>();
    private ServerPlayer body;
    private BlockPos deniedClick;
    private boolean cancelClick;
    private int beforeTick;
    private int tickSamples;
    private int badTickSamples;
    private int runs;
    private boolean sampleTick;
    private int restoreModeAt;
    private AgentMode.Mode previousMode;
    private ServerPlayer pendingRun;
    private final java.util.concurrent.atomic.AtomicBoolean expiredTaskRan = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String expiredTaskOutcome;
    private int expiredTaskCheckAt;
    private int controlQueueCheckAt;
    private ServerLevel controlQueueLevel;
    private BlockPos controlQueuePos;
    private final java.util.concurrent.atomic.AtomicReference<String> queuedControlTool = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<String> queuedControlCommand = new java.util.concurrent.atomic.AtomicReference<>();

    public LiveRegression() {
        NeoForge.EVENT_BUS.addListener(this::register);
        NeoForge.EVENT_BUS.addListener(this::click);
        NeoForge.EVENT_BUS.addListener(this::pre);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::post);
    }

    private void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mcagentqa").requires(src -> src.hasPermission(4))
                .then(Commands.literal("run").executes(ctx -> {
                    requireCopy();
                    pendingRun = ctx.getSource().getPlayerOrException();
                    results.clear();
                    results.add("PENDING: suite will run after the command context closes");
                    ctx.getSource().sendSuccess(() -> Component.literal("QA scheduled; use /mcagentqa results"), false);
                    return 1;
                }))
                .then(Commands.literal("results").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", results)), false);
                    return results.stream().anyMatch(s -> s.startsWith("FAIL")) ? 0 : 1;
                }))
                .then(Commands.literal("ticks").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "TICK_SAMPLES=" + tickSamples + " BAD_TICK_SAMPLES=" + badTickSamples), false);
                    return tickSamples > 0 && badTickSamples == 0 ? 1 : 0;
                }))
                .then(Commands.literal("bridge-reload").executes(ctx -> {
                    requireCopy();
                    try { BridgeReloadFixture.schedule(ctx.getSource().getPlayerOrException()); }
                    catch (Exception error) { throw new IllegalStateException(error.getMessage(), error); }
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Bridge reload QA scheduled; use /mcagentqa bridge-reload results"), false);
                    return 1;
                }).then(Commands.literal("results").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(BridgeReloadFixture.results()), false);
                    return BridgeReloadFixture.resultCode();
                })))
                .then(Commands.literal("chat").executes(ctx -> {
                    requireCopy();
                    previousMode = AgentMode.get();
                    restoreModeAt = ctx.getSource().getServer().getTickCount() + 100;
                    AgentMode.set(AgentMode.Mode.CHAT);
                    ctx.getSource().sendSuccess(() -> Component.literal("chat mode for 100 ticks"), false);
                    return 1;
                })));
    }

    private static void requireCopy() {
        String folder = GameBridge.server().getWorldPath(LevelResource.ROOT).normalize().getFileName().toString();
        if (!folder.startsWith("mcagent-review-")) throw new IllegalStateException("QA only runs in a copied mcagent-review-* save");
    }

    private void run(ServerPlayer human) {
        requireCopy();
        results.clear();
        AgentMode.Mode mode = AgentMode.get();
        boolean write = AgentConfig.get().allowWrite;
        try {
            check("new session has no fake players", FakePlayers.names().isEmpty());
            check("new session has no edit history", EditHistory.size() == 0);
            check("new session has no watchers", Watchers.describe().isEmpty());
            check("new session has no highlights", Highlights.active() == 0);
            var agentCommand = human.getServer().getCommands().getDispatcher().getRoot().getChild("agent");
            check("external ask command is available", agentCommand != null && agentCommand.getChild("ask") != null);
            check("native LLM command is retired", agentCommand != null && agentCommand.getChild("native") == null);
            checkKnowledge(human);
            checkQueuedDeadline(human);
            AgentMode.set(AgentMode.Mode.WORK);
            AgentConfig.get().allowWrite = true;
            ServerLevel level = human.serverLevel();
            BlockPos p = findFixture(human, level);
            level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
            body = FakePlayers.spawn(human.getServer(), level, "McAgentQA", p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
            check("engineering body remains available", !isError(call("mc_player_list", Json.obj())));
            for (String retired : java.util.List.of("mc_chat", "mc_chat_read", "mc_companion_control", "mc_companion_intent"))
                check("retired tool is absent: " + retired, McAgent.TOOLS.get(retired) == null);
            checkUndoableEdits(level, p.above(5));
            checkUndoConflicts(human, level, p.above(6));
            checkBatchedUndo(human, level, p.above(6));
            checkEditPreviews(level, p.above(6));
            checkEditJobs(human, level, p.above(6));
            checkPlayerSelections(human, level, p.above(6));
            checkBlockObservations(level, p.above(6));
            checkGlobalStop(human, level, p.above(6));
            tickSamples = badTickSamples = 0;

            ServerLevel target = human.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
            BlockPos edit = new BlockPos(p.getX(), 200, p.getZ());
            boolean ticketAdded = target.setChunkForced(edit.getX() >> 4, edit.getZ() >> 4, true);
            target.getChunkAt(edit);
            level.getChunkAt(edit);
            var originalTarget = target.getBlockState(edit);
            var originalSource = level.getBlockState(edit);
            try {
                check("cross-dimension fill succeeds", !isError(fill(edit, target)));
                check("fill modifies requested dimension", target.getBlockState(edit).is(Blocks.GOLD_BLOCK));
                check("fill leaves source dimension alone", level.getBlockState(edit).equals(originalSource));
                AgentMode.set(AgentMode.Mode.CHAT);
                check("chat rejects undo", isError(call("mc_undo", Json.obj())));
                check("rejected undo preserves edit", target.getBlockState(edit).is(Blocks.GOLD_BLOCK));
                AgentMode.set(AgentMode.Mode.WORK);
                AgentConfig.get().allowWrite = false;
                check("write-disabled rejects undo", isError(call("mc_undo", Json.obj())));
                AgentConfig.get().allowWrite = true;
                check("enabled undo succeeds", !isError(call("mc_undo", Json.obj())));
                check("undo restores requested dimension", target.getBlockState(edit).equals(originalTarget));
            } finally {
                target.setBlock(edit, originalTarget, 3);
                level.setBlock(edit, originalSource, 3);
                EditHistory.clear();
                if (ticketAdded) target.setChunkForced(edit.getX() >> 4, edit.getZ() >> 4, false);
            }

            BlockPos block = p.east();
            var original = level.getBlockState(block);
            try {
                level.setBlock(block, Blocks.DIRT.defaultBlockState(), 3);
                deniedClick = block;
                cancelClick = true;
                AgentActions.startBreak(body, block, Direction.UP);
                for (int i = 0; i < 100; i++) AgentActions.tickBreaks(human.getServer());
                check("LeftClickBlock cancellation preserves block", level.getBlockState(block).is(Blocks.DIRT));
                cancelClick = false;
                AgentActions.cancelBreak(body);
                WorldBorder border = level.getWorldBorder();
                var settings = border.createSettings();
                try {
                    border.setCenter(block.getX() - 20, block.getZ());
                    border.setSize(16);
                    check("border fixture denies mayInteract", !level.mayInteract(body, block));
                    AgentActions.startBreak(body, block, Direction.UP);
                    for (int i = 0; i < 100; i++) AgentActions.tickBreaks(human.getServer());
                    check("border protection preserves block", level.getBlockState(block).is(Blocks.DIRT));
                } finally {
                    border.applySettings(settings);
                    AgentActions.cancelBreak(body);
                }
                AgentActions.startBreak(body, block, Direction.UP);
                for (int i = 0; i < 100; i++) AgentActions.tickBreaks(human.getServer());
                check("unprotected mining still breaks block", level.getBlockState(block).isAir());
            } finally {
                cancelClick = false;
                AgentActions.cancelBreak(body);
                level.setBlock(block, original, 3);
            }

            // Leave live state for the next exit/re-entry to prove lifecycle cleanup.
            BlockPos chest = p.above(3);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            Watchers.add(level, chest, "qa-lifecycle", 3600);
            Highlights.add(level, chest, chest, 120);
            List<EditHistory.Cell> cells = new ArrayList<>();
            EditHistory.capture(level, List.of(chest), "qa-lifecycle", cells);
            EditHistory.push("qa-lifecycle", level, cells);
            results.add("LIFECYCLE_FIXTURE_READY: exit and re-enter the copied world, then run again");
        } catch (Exception e) {
            results.add("FAIL: suite aborted: " + e);
        } finally {
            AgentConfig.get().allowWrite = write;
            AgentMode.set(mode);
        }
    }

    private static boolean rejectsTool(String tool, JsonObject args) throws Exception {
        try { return isError(call(tool, args)); }
        catch (IllegalStateException expected) { return true; }
    }

    private void checkGlobalStop(ServerPlayer human, ServerLevel level, BlockPos pos) throws Exception {
        boolean write = AgentConfig.get().allowWrite, commands = AgentConfig.get().allowCommands;
        var previousHeld = body.getMainHandItem().copy();
        BlockPos observedPos = pos.east(5), queuePos = pos.above(5);
        if (!level.getBlockState(observedPos).isAir() || !level.getBlockState(queuePos).isAir())
            throw new IllegalStateException("global stop fixture must be empty");
        try {
            String nativeEpoch = AgentControl.epoch();
            String bridgeRelease = GameBridge.runCommand("agent task release", human.getGameProfile().getName(), 4);
            check("bridge command cannot impersonate human task release", bridgeRelease.contains("native human input")
                    && AgentControl.epoch().equals(nativeEpoch) && !GameBridge.bridgeCommand());
            String bridgeResume = GameBridge.runCommand("agent resume", human.getGameProfile().getName(), 4);
            check("bridge command cannot impersonate human control resume", bridgeResume.contains("native human input") && !GameBridge.bridgeCommand());
            JsonObject wrongWorld = Json.obj(); wrongWorld.addProperty("worldSessionId", "stale");
            check("stale world cannot stop the current world", isError(call("mc_control_stop", wrongWorld)) && AgentControl.active());
            JsonObject request = previewRequest(pos, pos.offset(3, 3, 3), "minecraft:glass", level);
            JsonObject plan = previewTokens(textObject(call("mc_edit_preview", request))); plan.addProperty("batchCells", 1);
            JsonObject started = textObject(call("mc_edit_start", plan)); JsonObject job = jobTokens(started);
            EditJobs.tick(human.getServer());
            check("global stop fixture has a real partial edit", textObject(call("mc_edit_job", job)).get("processedCells").getAsInt() == 1);
            level.setBlock(observedPos, Blocks.BARREL.defaultBlockState(), 3);
            JsonObject observation = Json.obj(); observation.addProperty("action", "start");
            observation.addProperty("worldSessionId", GameBridge.worldSessionId()); observation.addProperty("taskId", "qa-global-stop");
            observation.addProperty("x", observedPos.getX()); observation.addProperty("y", observedPos.getY()); observation.addProperty("z", observedPos.getZ());
            observation.addProperty("dimension", level.dimension().location().toString());
            observation.addProperty("path", "Items[0].count"); observation.addProperty("comparison", "exists");
            String observationId = textObject(call("mc_observe", observation)).get("observationId").getAsString();
            body.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD));
            body.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
            check("global stop fixture has a genuinely using body", body.isUsingItem());
            dev.mcagent.fakeplayer.AgentFollow.start(body, human);
            Watchers.add(level, observedPos, "qa-global-stop", 3600); Highlights.add(level, observedPos, observedPos, 120);
            check("global stop fixture has active following and observation", dev.mcagent.fakeplayer.AgentFollow.isFollowing(body)
                    && !textObject(call("mc_observe", observationStatus(observationId))).get("terminal").getAsBoolean());
            JsonObject writeArgs = Json.obj(); writeArgs.addProperty("x", queuePos.getX()); writeArgs.addProperty("y", queuePos.getY()); writeArgs.addProperty("z", queuePos.getZ());
            writeArgs.addProperty("dimension", level.dimension().location().toString()); writeArgs.addProperty("block", "minecraft:diamond_block");
            queuedControlTool.set(null); queuedControlCommand.set(null);
            Thread tool = new Thread(() -> {
                try { queuedControlTool.set(call("mc_set_block", writeArgs).toString()); }
                catch (Exception error) { queuedControlTool.set("exception: " + error.getMessage()); }
            }, "qa-control-queued-tool");
            Thread raw = new Thread(() -> {
                try { queuedControlCommand.set(GameBridge.runCommand("setblock " + queuePos.getX() + " " + queuePos.getY() + " " + queuePos.getZ()
                        + " minecraft:gold_block", human.getGameProfile().getName(), 4, level)); }
                catch (Exception error) { queuedControlCommand.set("exception: " + error.getMessage()); }
            }, "qa-control-queued-command");
            tool.setDaemon(true); raw.setDaemon(true); tool.start(); raw.start();
            waitForServerQueue(tool); waitForServerQueue(raw);
            controlQueueLevel = level; controlQueuePos = queuePos; controlQueueCheckAt = human.getServer().getTickCount() + 3;
            AgentConfig.get().allowWrite = AgentConfig.get().allowCommands = false;
            human.getServer().getCommands().performPrefixedCommand(human.createCommandSourceStack().withPermission(4), "agent stop");
            check("human global stop works without command or write permission", !AgentControl.active() && AgentControl.snapshot().getAsJsonArray("stopErrors").isEmpty());
            check("global stop cancels the real partial edit", textObject(call("mc_edit_job", job)).get("status").getAsString().equals("cancelled") && !EditJobs.active());
            check("global stop finishes condition observations", textObject(call("mc_observe", observationStatus(observationId))).get("outcome").getAsString().equals("cancelled"));
            check("global stop stops Work body following", !dev.mcagent.fakeplayer.AgentFollow.isFollowing(body) && !dev.mcagent.fakeplayer.AgentMovement.isWalking(body));
            check("global stop clears held use and movement inputs", !body.isUsingItem() && body.xxa == 0 && body.zza == 0 && !body.isSprinting());
            check("global stop clears broadcasts and highlights", Watchers.describe().isEmpty() && Highlights.active() == 0);
            check("stopped control preserves read tools", !isError(call("mc_status", Json.obj())) && !isError(call("mc_knowledge", Json.obj())));
            check("stopped control preserves read-only selection query", !isError(call("mc_selection", selectionOwner(human))));
            AgentConfig.get().allowWrite = AgentConfig.get().allowCommands = true;
            check("stop latch rejects fresh direct edits", isError(call("mc_set_block", writeArgs)) && level.getBlockState(queuePos).isAir());
            check("stop latch rejects new previews", isError(call("mc_edit_preview", request)));
            check("command discovery reports stopped execution accurately", !textObject(call("mc_command_inspect", Json.obj())).get("executionAllowedByBridge").getAsBoolean());
            observation.addProperty("taskId", "qa-global-stop-after");
            check("stop latch rejects new autonomous observations", isError(call("mc_observe", observation)));
            JsonObject resumeCommand = Json.obj(); resumeCommand.addProperty("command", "agent resume"); resumeCommand.addProperty("permission", 4);
            check("external command cannot reopen the stop latch", isError(call("mc_command", resumeCommand)) && !AgentControl.active());
            boolean rawDenied = false;
            try { GameBridge.runCommand("agent resume", human.getGameProfile().getName(), 4); }
            catch (IllegalStateException expected) { rawDenied = expected.getMessage().contains("stopped"); }
            check("raw WebSocket command path cannot reopen the latch", rawDenied && !AgentControl.active());
            JsonObject stop = Json.obj(); stop.addProperty("worldSessionId", GameBridge.worldSessionId());
            AgentConfig.get().allowWrite = AgentConfig.get().allowCommands = false;
            check("MCP global stop works with permissions disabled", !isError(call("mc_control_stop", stop)));
            human.getServer().getCommands().performPrefixedCommand(body.createCommandSourceStack().withPermission(4), "agent resume");
            check("agent-owned body cannot resume global control", !AgentControl.active());
            human.getServer().getCommands().performPrefixedCommand(human.createCommandSourceStack().withPermission(4), "agent resume");
            check("human resume opens only new operations", AgentControl.active() && !EditJobs.active()
                    && !dev.mcagent.fakeplayer.AgentFollow.isFollowing(body));
            AgentConfig.get().allowWrite = AgentConfig.get().allowCommands = true;
            check("resume does not revive the old edit lease", isError(call("mc_edit_keepalive", job)));
            check("cancelled partial edits remain explicitly undoable after resume", !isError(call("mc_undo", Json.obj())) && level.getBlockState(pos).isAir());
        } finally {
            AgentControl.stop("qa_control_cleanup"); AgentControl.resume();
            AgentConfig.get().allowWrite = write; AgentConfig.get().allowCommands = commands;
            level.setBlock(observedPos, Blocks.AIR.defaultBlockState(), 3);
            body.stopUsingItem(); body.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, previousHeld);
            EditHistory.clear(); EditJobs.clear(); BlockObservations.clear();
        }
    }

    private static JsonObject observationStatus(String id) {
        JsonObject args = Json.obj(); args.addProperty("action", "status"); args.addProperty("observationId", id);
        args.addProperty("worldSessionId", GameBridge.worldSessionId()); return args;
    }

    private static void waitForServerQueue(Thread request) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        while (request.isAlive() && System.nanoTime() < deadline) {
            for (StackTraceElement frame : request.getStackTrace())
                if (frame.getClassName().equals(GameBridge.class.getName()) && frame.getMethodName().equals("onServer")) return;
            request.join(1);
        }
        throw new IllegalStateException("control race fixture did not enter the real server queue");
    }

    private void checkQueuedDeadline(ServerPlayer human) throws InterruptedException {
        expiredTaskRan.set(false); expiredTaskOutcome = null;
        Thread request = new Thread(() -> {
            try {
                GameBridge.onServer(() -> { expiredTaskRan.set(true); return null; }, 50);
                expiredTaskOutcome = "unexpected execution";
            } catch (IllegalStateException expected) { expiredTaskOutcome = expected.getMessage(); }
        }, "mcagent-qa-expiring-request");
        request.setDaemon(true); request.start();
        // Hold this server tick briefly so the background request expires while still queued.
        request.join(1000);
        check("queued request reports timeout before execution", !request.isAlive() && expiredTaskOutcome != null
                && expiredTaskOutcome.contains("before execution"));
        expiredTaskCheckAt = human.getServer().getTickCount() + 3;
    }

    private BlockPos findFixture(ServerPlayer human, ServerLevel level) {
        for (int attempt = 0; attempt < 64; attempt++) {
            int candidate = runs++;
            BlockPos pos = human.blockPosition().offset(8 + 8 * (candidate % 8), 8, 8 + 8 * (candidate / 8));
            level.getChunkAt(pos);
            boolean empty = true;
            for (BlockPos cell : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(7, 9, 3))) {
                if (!level.getBlockState(cell).isAir()) { empty = false; break; }
            }
            if (empty) return pos;
        }
        throw new IllegalStateException("no free QA fixture among 64 bounded candidates; preserve the occupied areas");
    }

    private void checkEditPreviews(ServerLevel level, BlockPos pos) throws Exception {
        AgentMode.Mode mode = AgentMode.get();
        boolean write = AgentConfig.get().allowWrite;
        try {
            AgentMode.set(AgentMode.Mode.WORK);
            JsonObject status = GameBridge.snapshot();
            check("status exposes current world session and copied save folder", status.get("worldSessionId").getAsString().equals(GameBridge.worldSessionId())
                    && status.get("saveFolder").getAsString().startsWith("mcagent-review-"));
            JsonObject request = previewRequest(pos, pos, "minecraft:stone", level);
            JsonObject preview = textObject(call("mc_edit_preview", request));
            check("preview does not mutate blocks or undo history", level.getBlockState(pos).isAir() && EditHistory.size() == 0);
            check("preview reports exact scope and estimated change", preview.get("cellCount").getAsInt() == 1
                    && preview.get("estimatedChangedCells").getAsInt() == 1 && preview.get("dimension").getAsString().equals(level.dimension().location().toString()));
            JsonObject tokens = previewTokens(preview);
            JsonObject bad = tokens.deepCopy(); bad.addProperty("worldSessionId", "old-world");
            check("preview rejects mismatched world without writing", isError(call("mc_edit_apply", bad)) && level.getBlockState(pos).isAir());
            bad = tokens.deepCopy(); bad.addProperty("planRevision", 2);
            check("preview rejects mismatched revision", isError(call("mc_edit_apply", bad)));
            JsonObject receipt = textObject(call("mc_edit_apply", tokens));
            check("preview apply changes actual block with actual receipt", level.getBlockState(pos).is(Blocks.STONE)
                    && receipt.get("changedCells").getAsInt() == 1 && receipt.get("outcome").getAsString().equals("succeeded"));
            check("applied preview cannot be replayed", isError(call("mc_edit_apply", tokens)) && EditHistory.size() == 1);
            call("mc_undo", Json.obj());
            check("preview edit participates in existing undo", level.getBlockState(pos).isAir() && EditHistory.size() == 0);
            tokens = previewTokens(textObject(call("mc_edit_preview", request)));
            level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            JsonObject conflict = call("mc_edit_apply", tokens);
            check("block conflict preserves player's intervening edit", isError(conflict) && level.getBlockState(pos).is(Blocks.GOLD_BLOCK) && EditHistory.size() == 0);
            check("conflict reports affected positions", textObjectIgnoringError(conflict).get("conflictingCells").getAsInt() == 1);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            check("conflicted preview stays consumed after state is restored", isError(call("mc_edit_apply", tokens)));
            level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
            var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(pos);
            tokens = previewTokens(textObject(call("mc_edit_preview", request)));
            chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 2)); chest.setChanged();
            check("NBT-only change conflicts even when block state is unchanged", isError(call("mc_edit_apply", tokens))
                    && chest.getItem(0).getCount() == 2 && EditHistory.size() == 0);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            tokens = previewTokens(textObject(call("mc_edit_preview", request)));
            AgentConfig.get().allowWrite = false;
            check("write permission is rechecked when applying preview", isError(call("mc_edit_apply", tokens)) && level.getBlockState(pos).isAir());
            call("mc_edit_discard", tokens); AgentConfig.get().allowWrite = write;
            tokens = previewTokens(textObject(call("mc_edit_preview", request)));
            AgentMode.set(AgentMode.Mode.CHAT); AgentMode.set(AgentMode.Mode.WORK);
            check("mode round trip invalidates an old preview", isError(call("mc_edit_apply", tokens)));
            AgentMode.set(AgentMode.Mode.CHAT);
            preview = textObject(call("mc_edit_preview", request)); tokens = previewTokens(preview);
            check("restricted mode may preview but cannot execute", !preview.get("executionAllowed").getAsBoolean() && isError(call("mc_edit_apply", tokens)));
            check("preview discard works without world edit permissions", !isError(call("mc_edit_discard", tokens)));
            AgentMode.set(AgentMode.Mode.WORK);
            JsonObject huge = request.deepCopy(); huge.addProperty("y1", Integer.MIN_VALUE); huge.addProperty("y2", Integer.MAX_VALUE);
            check("extreme coordinates fail before allocation or scanning", isError(call("mc_edit_preview", huge)));
            JsonObject invalid = request.deepCopy(); invalid.addProperty("block", "qa_missing:block");
            check("preview uses actual block parser without execution", isError(call("mc_edit_preview", invalid)) && level.getBlockState(pos).isAir());
            var retained = new ArrayList<JsonObject>();
            for (int i = 0; i < 8; i++) retained.add(previewTokens(textObject(call("mc_edit_preview", request))));
            check("preview cache is bounded at eight plans", isError(call("mc_edit_preview", request)));
            for (JsonObject token : retained) call("mc_edit_discard", token);
            check("discarding previews releases cache capacity", !isError(call("mc_edit_discard", previewTokens(textObject(call("mc_edit_preview", request))))));
            for (BlockPos cell : BlockPos.betweenClosed(pos, pos.offset(2, 2, 2))) level.setBlock(cell, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            JsonObject cube = previewRequest(pos, pos.offset(2, 2, 2), "minecraft:stone", level);
            for (String fillMode : List.of("replace", "keep", "outline", "hollow", "destroy")) {
                cube.addProperty("mode", fillMode);
                preview = textObject(call("mc_edit_preview", cube)); tokens = previewTokens(preview);
                int expected = fillMode.equals("keep") ? 0 : fillMode.equals("outline") ? 26 : 27;
                check("preview estimates " + fillMode + " without changing region", preview.get("estimatedChangedCells").getAsInt() == expected
                        && level.getBlockState(pos.offset(1, 1, 1)).is(Blocks.GOLD_BLOCK));
                if (fillMode.equals("hollow")) {
                    receipt = textObject(call("mc_edit_apply", tokens));
                    check("hollow preview applies faces and air interior", level.getBlockState(pos).is(Blocks.STONE)
                            && level.getBlockState(pos.offset(1, 1, 1)).isAir() && receipt.get("changedCells").getAsInt() == 27);
                    call("mc_undo", Json.obj());
                    check("hollow preview undo restores interior and shell", level.getBlockState(pos).is(Blocks.GOLD_BLOCK)
                            && level.getBlockState(pos.offset(1, 1, 1)).is(Blocks.GOLD_BLOCK));
                } else call("mc_edit_discard", tokens);
            }
        } finally {
            for (BlockPos cell : BlockPos.betweenClosed(pos, pos.offset(2, 2, 2))) level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
            EditPlans.clear(); EditHistory.clear(); AgentMode.set(mode); AgentConfig.get().allowWrite = write;
        }
    }

    private void checkEditJobs(ServerPlayer human, ServerLevel level, BlockPos pos) throws Exception {
        AgentMode.Mode mode = AgentMode.get();
        boolean write = AgentConfig.get().allowWrite, commands = AgentConfig.get().allowCommands;
        try {
            AgentMode.set(AgentMode.Mode.WORK);
            JsonObject request = previewRequest(pos, pos.offset(3, 3, 3), "minecraft:stone", level);
            JsonObject plan = previewTokens(textObject(call("mc_edit_preview", request)));
            JsonObject invalid = plan.deepCopy(); invalid.addProperty("batchCells", 129);
            check("invalid batch budget is rejected without starting work", isError(call("mc_edit_start", invalid)) && !EditJobs.active());
            plan.addProperty("batchCells", 2);
            JsonObject started = textObject(call("mc_edit_start", plan));
            JsonObject job = jobTokens(started);
            JsonObject listing = textObject(call("mc_edit_jobs", Json.obj()));
            boolean foundPlan = false;
            for (var entry : listing.getAsJsonArray("jobs")) {
                if (entry.getAsJsonObject().get("planId").equals(plan.get("planId"))) foundPlan = true;
            }
            check("lost start receipt can be recovered by matching plan id", listing.get("activeJobId").equals(started.get("jobId"))
                    && foundPlan);
            check("job acceptance does not execute cells immediately", started.get("processedCells").getAsInt() == 0 && level.getBlockState(pos).isAir());
            check("job consumes preview exactly once", isError(call("mc_edit_start", plan)));
            check("active job blocks interleaved managed undo", isError(call("mc_undo", Json.obj())));
            check("active job blocks interleaved fill", isError(fill(pos, level)));
            JsonObject wrong = job.deepCopy(); wrong.addProperty("worldSessionId", "old-world");
            check("job status rejects wrong world identity", isError(call("mc_edit_job", wrong)));
            EditJobs.tick(human.getServer());
            JsonObject progress = textObject(call("mc_edit_job", job));
            int processed = progress.get("processedCells").getAsInt();
            check("job runner respects its cell budget and changes real blocks", processed >= 1 && processed <= 2 && level.getBlockState(pos).is(Blocks.STONE));
            check("active job exposes pending undo without interleaving stack", progress.get("undoPending").getAsBoolean() && EditHistory.size() == 0);
            check("explicit keepalive renews authorized job", textObject(call("mc_edit_keepalive", job)).get("leaseRemainingMs").getAsLong() > 14000);
            JsonObject stopped = textObject(call("mc_edit_cancel", job));
            check("cancel preserves actual partial progress and undo", stopped.get("status").getAsString().equals("cancelled")
                    && stopped.get("partial").getAsBoolean() && stopped.get("undoRecorded").getAsBoolean() && EditHistory.size() == 1);
            EditJobs.tick(human.getServer());
            check("cancel prevents further cells and cannot be renewed", textObject(call("mc_edit_job", job)).get("processedCells").getAsInt() == processed
                    && isError(call("mc_edit_keepalive", job)));
            call("mc_undo", Json.obj());
            check("cancelled job undo restores only completed writes", level.getBlockState(pos).isAir() && EditHistory.size() == 0);

            request = previewRequest(pos, pos.east(7), "minecraft:stone", level);
            plan = previewTokens(textObject(call("mc_edit_preview", request))); plan.addProperty("batchCells", 1);
            job = jobTokens(textObject(call("mc_edit_start", plan)));
            EditJobs.tick(human.getServer()); level.setBlock(pos.east(), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            EditJobs.tick(human.getServer());
            stopped = textObject(call("mc_edit_job", job));
            check("future-cell conflict stops without overwriting player edit", stopped.get("status").getAsString().equals("conflict")
                    && level.getBlockState(pos.east()).is(Blocks.GOLD_BLOCK) && level.getBlockState(pos.east(2)).isAir());
            call("mc_undo", Json.obj());
            check("conflict undo excludes untouched player edit", level.getBlockState(pos).isAir() && level.getBlockState(pos.east()).is(Blocks.GOLD_BLOCK));
            level.setBlock(pos.east(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(pos.east(), Blocks.CHEST.defaultBlockState(), 3);
            plan = previewTokens(textObject(call("mc_edit_preview", request))); plan.addProperty("batchCells", 1);
            job = jobTokens(textObject(call("mc_edit_start", plan))); EditJobs.tick(human.getServer());
            var changedChest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(pos.east());
            changedChest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 2)); changedChest.setChanged();
            EditJobs.tick(human.getServer());
            check("future NBT-only conflict stops the job", textObject(call("mc_edit_job", job)).get("status").getAsString().equals("conflict")
                    && changedChest.getItem(0).getCount() == 2);
            call("mc_undo", Json.obj());
            check("partial undo preserves conflicted container contents", level.getBlockState(pos).isAir()
                    && level.getBlockEntity(pos.east()) == changedChest && changedChest.getItem(0).getCount() == 2);
            level.setBlock(pos.east(), Blocks.AIR.defaultBlockState(), 3);
            for (String stop : List.of("permission", "mode", "human")) {
                plan = previewTokens(textObject(call("mc_edit_preview", request))); plan.addProperty("batchCells", 1);
                job = jobTokens(textObject(call("mc_edit_start", plan)));
                if (stop.equals("permission")) AgentConfig.get().allowWrite = false;
                else if (stop.equals("mode")) { AgentMode.set(AgentMode.Mode.CHAT); AgentMode.set(AgentMode.Mode.WORK); }
                else { AgentConfig.get().allowCommands = false; GameBridge.runCommand("agent edit stop", human.getGameProfile().getName(), 4); }
                EditJobs.tick(human.getServer());
                stopped = textObject(call("mc_edit_job", job));
                check("job stops on " + stop + " without new writes", stopped.get("status").getAsString().equals("cancelled")
                        && stopped.get("processedCells").getAsInt() == 0 && level.getBlockState(pos).isAir());
                AgentConfig.get().allowWrite = write; AgentConfig.get().allowCommands = commands;
            }
            JsonObject large = previewRequest(pos, pos.offset(16, 16, 16), "minecraft:stone", level);
            plan = previewTokens(textObject(call("mc_edit_preview", large)));
            check("large preview requires batched execution", isError(call("mc_edit_apply", plan)));
            started = textObject(call("mc_edit_start", plan)); job = jobTokens(started);
            check("job accepts more than synchronous cell limit", started.get("totalCells").getAsInt() == 4913 && started.get("processedCells").getAsInt() == 0);
            call("mc_edit_cancel", job);
            for (String fillMode : List.of("replace", "keep", "outline", "hollow", "destroy")) {
                for (BlockPos cell : BlockPos.betweenClosed(pos, pos.offset(2, 2, 2))) level.setBlock(cell, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                JsonObject cube = previewRequest(pos, pos.offset(2, 2, 2), "minecraft:stone", level); cube.addProperty("mode", fillMode);
                plan = previewTokens(textObject(call("mc_edit_preview", cube))); plan.addProperty("batchCells", 4);
                job = jobTokens(textObject(call("mc_edit_start", plan)));
                for (int i = 0; i < 64 && EditJobs.active(); i++) EditJobs.tick(human.getServer());
                progress = textObject(call("mc_edit_job", job));
                int expected = fillMode.equals("keep") ? 0 : fillMode.equals("outline") ? 26 : 27;
                check("batched " + fillMode + " preserves whole-region semantics", progress.get("status").getAsString().equals("completed")
                        && progress.get("changedCells").getAsInt() == expected);
                if (fillMode.equals("outline")) check("outline job preserves whole-region interior", level.getBlockState(pos.offset(1, 1, 1)).is(Blocks.GOLD_BLOCK));
                if (fillMode.equals("hollow")) check("hollow job clears whole-region interior", level.getBlockState(pos.offset(1, 1, 1)).isAir());
                if (expected > 0) {
                    check("whole job creates one undo operation", EditHistory.size() == 1); call("mc_undo", Json.obj());
                    check("completed job undo restores actual region", level.getBlockState(pos).is(Blocks.GOLD_BLOCK)
                            && level.getBlockState(pos.offset(1, 1, 1)).is(Blocks.GOLD_BLOCK));
                } else check("no-op job does not create empty undo record", EditHistory.size() == 0);
            }
            JsonObject missing = jobTokens(started); missing.addProperty("jobId", "missing-job");
            check("missing job is explicitly unknown", textObject(call("mc_edit_job", missing)).get("status").getAsString().equals("outcome_unknown"));
        } finally {
            EditJobs.stopAll("qa_cleanup");
            for (BlockPos cell : BlockPos.betweenClosed(pos, pos.offset(7, 3, 3))) level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
            EditJobs.clear(); EditPlans.clear(); EditHistory.clear(); AgentMode.set(mode);
            AgentConfig.get().allowWrite = write; AgentConfig.get().allowCommands = commands;
        }
    }

    private void checkPlayerSelections(ServerPlayer human, ServerLevel level, BlockPos origin) throws Exception {
        JsonObject owner = selectionOwner(human);
        if (!"missing".equals(Json.str(textObject(call("mc_selection", owner)), "status", "")))
            throw new IllegalStateException("selection QA requires an empty selection for this player");
        if (EditJobs.active() || EditHistory.size() != 0)
            throw new IllegalStateException("selection QA requires idle editing and an empty undo history");

        BlockPos a = origin.east(2), b = origin.east(3).south(), occluder = origin.east().south(2);
        var original = new LinkedHashMap<BlockPos, BlockState>();
        for (BlockPos cell : BlockPos.betweenClosed(a, b)) {
            if (!level.isLoaded(cell) || !level.getBlockState(cell).isAir())
                throw new IllegalStateException("selection QA region must be loaded and empty");
            original.put(cell.immutable(), level.getBlockState(cell));
        }
        if (!level.isLoaded(occluder) || !level.getBlockState(occluder).isAir())
            throw new IllegalStateException("selection QA occlusion fixture must be loaded and empty");
        original.put(occluder, level.getBlockState(occluder));

        Vec3 humanPosition = human.position(), bodyPosition = body.position();
        float humanYaw = human.getYRot(), humanPitch = human.getXRot();
        float bodyYaw = body.getYRot(), bodyPitch = body.getXRot();
        boolean write = AgentConfig.get().allowWrite;
        ArmorStand stand = new ArmorStand(level, origin.getX() + 2.5, origin.getY(), origin.getZ() + 2.5);
        var previews = new ArrayList<JsonObject>();
        try {
            level.setBlock(a, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(b, Blocks.STONE.defaultBlockState(), 3);
            stand.setNoGravity(true);
            if (!level.addFreshEntity(stand)) throw new IllegalStateException("selection QA armor stand spawn failed");

            aimEast(human, origin);
            check("native player can select the looked-at block", select(human, "block"));
            JsonObject selected = textObject(call("mc_selection", owner));
            JsonObject target = Json.sub(selected, "target");
            check("block selection reports actual position and identity", "block".equals(Json.str(target, "kind", ""))
                    && "minecraft:stone".equals(Json.str(target, "block", "")) && atBlock(target, a));
            check("selection belongs to the native player and current world", "active".equals(Json.str(selected, "status", ""))
                    && human.getUUID().toString().equals(Json.str(selected, "playerId", ""))
                    && GameBridge.worldSessionId().equals(Json.str(selected, "worldSessionId", ""))
                    && level.dimension().location().toString().equals(Json.str(selected, "dimension", "")));

            aimEast(body, origin);
            check("agent body cannot select even with permission and a valid ray", !select(body, "block"));
            JsonObject bridged = GameBridge.runCommandResult("agent select clear", human.getGameProfile().getName(), 4, level);
            check("bridge command cannot clear a real player's selection", Json.l(bridged, "resultSum", -1) == 0
                    && selected.equals(textObject(call("mc_selection", owner))));
            JsonObject malformed = Json.obj();
            malformed.addProperty("playerId", "not-a-uuid");
            check("selection read rejects malformed player identity", isError(call("mc_selection", malformed)));

            aimEast(human, origin.south(2));
            level.setBlock(occluder, Blocks.STONE.defaultBlockState(), 3);
            check("entity selection respects a block occluding the ray", !select(human, "entity")
                    && selected.equals(textObject(call("mc_selection", owner))));
            level.setBlock(occluder, original.get(occluder), 3);
            check("native player can select the visible entity", select(human, "entity"));
            selected = textObject(call("mc_selection", owner));
            target = Json.sub(selected, "target");
            check("entity selection reports the actual target UUID", "entity".equals(Json.str(target, "kind", ""))
                    && stand.getUUID().toString().equals(Json.str(target, "entityId", "")));
            stand.discard();
            target = Json.sub(textObject(call("mc_selection", owner)), "target");
            check("removed entity is reported unavailable", "unavailable".equals(Json.str(target, "status", "")));

            aimEast(human, origin);
            check("native player can capture the first region corner", select(human, "corner a"));
            JsonObject first = textObject(call("mc_selection", owner));
            JsonObject region = Json.sub(first, "region");
            check("one corner reports its exact position and remains incomplete", atBlock(Json.sub(region, "a"), a)
                    && !Json.b(region, "ready", true));
            check("incomplete selection cannot create an edit plan", isError(call("mc_edit_preview_selection", selectedPreviewRequest(first))));

            aimEast(human, origin.south());
            check("native player can capture the second region corner", select(human, "corner b"));
            selected = textObject(call("mc_selection", owner));
            region = Json.sub(selected, "region");
            check("two corners report a ready region with a new revision", Json.b(region, "ready", false)
                    && atBlock(Json.sub(region, "a"), a) && atBlock(Json.sub(region, "b"), b)
                    && !Json.str(first, "selectionId", "").equals(Json.str(selected, "selectionId", "")));
            check("viewing the selection preserves the revision", select(human, "show")
                    && selected.equals(textObject(call("mc_selection", owner))));

            JsonObject request = selectedPreviewRequest(selected);
            check("stale selection cannot create a preview", isError(call("mc_edit_preview_selection", selectedPreviewRequest(first))));
            JsonObject bad = request.deepCopy();
            bad.addProperty("worldSessionId", UUID.randomUUID().toString());
            check("selection preview rejects a different world session", isError(call("mc_edit_preview_selection", bad)));
            bad = request.deepCopy();
            bad.addProperty("playerId", body.getUUID().toString());
            check("selection preview rejects another player identity", isError(call("mc_edit_preview_selection", bad)));

            AgentConfig.get().allowWrite = false;
            JsonObject preview = textObject(call("mc_edit_preview_selection", request));
            JsonObject tokens = previewTokens(preview);
            previews.add(tokens);
            check("selected preview reports exact selected geometry and identity", Json.i(preview, "cellCount", -1) == 4
                    && a.toShortString().replace(" ", "").equals(Json.str(preview, "min", ""))
                    && b.toShortString().replace(" ", "").equals(Json.str(preview, "max", ""))
                    && Json.str(selected, "selectionId", "").equals(Json.str(preview, "selectionId", ""))
                    && human.getUUID().toString().equals(Json.str(preview, "playerId", "")));
            check("selecting and previewing leave blocks and undo history untouched", selectionBlocksIntact(level, a, b)
                    && EditHistory.size() == 0);
            check("selection does not bypass write permission", !Json.b(preview, "executionAllowed", true)
                    && isError(call("mc_edit_apply", tokens)) && selectionBlocksIntact(level, a, b));

            AgentConfig.get().allowWrite = true;
            JsonObject applied = textObject(call("mc_edit_apply", tokens));
            check("selected preview applies only the four selected cells", Json.i(applied, "changedCells", -1) == 4
                    && level.getBlockState(a).is(Blocks.GOLD_BLOCK) && level.getBlockState(b).is(Blocks.GOLD_BLOCK)
                    && level.getBlockState(a.south()).is(Blocks.GOLD_BLOCK) && level.getBlockState(a.east()).is(Blocks.GOLD_BLOCK)
                    && level.getBlockState(a.west()).isAir());
            check("undo restores the selected edit's actual block states", !isError(call("mc_undo", Json.obj()))
                    && selectionBlocksIntact(level, a, b) && EditHistory.size() == 0);

            tokens = previewTokens(textObject(call("mc_edit_preview_selection", request)));
            previews.add(tokens);
            level.setBlock(a.south(), Blocks.DIRT.defaultBlockState(), 3);
            JsonObject conflict = call("mc_edit_apply", tokens);
            check("selected preview retains intervening block conflict protection", isError(conflict)
                    && "conflict".equals(Json.str(textObjectIgnoringError(conflict), "outcome", ""))
                    && level.getBlockState(a.south()).is(Blocks.DIRT) && EditHistory.size() == 0);
            level.setBlock(a.south(), original.get(a.south()), 3);

            JsonObject staleApply = previewTokens(textObject(call("mc_edit_preview_selection", request)));
            JsonObject staleStart = previewTokens(textObject(call("mc_edit_preview_selection", request)));
            previews.add(staleApply); previews.add(staleStart);
            aimEast(human, origin);
            check("reselecting a corner changes the current region", select(human, "corner b"));
            check("reselecting rejects a retained apply before any write", isError(call("mc_edit_apply", staleApply))
                    && selectionBlocksIntact(level, a, b));
            check("reselecting rejects a retained start before any batch", isError(call("mc_edit_start", staleStart))
                    && !EditJobs.active() && selectionBlocksIntact(level, a, b));

            JsonObject legacy = textObject(call("mc_edit_preview", previewRequest(a, b, "minecraft:gold_block", level)));
            JsonObject legacyTokens = previewTokens(legacy);
            previews.add(legacyTokens);
            check("explicit coordinate preview ignores active selection geometry", Json.i(legacy, "cellCount", -1) == 4
                    && !legacy.has("selectionId") && !isError(call("mc_edit_discard", legacyTokens)));
            check("native clear removes the selection", select(human, "clear")
                    && "missing".equals(Json.str(textObject(call("mc_selection", owner)), "status", "")));
        } finally {
            try {
                if (EditJobs.active()) EditJobs.stopAll("qa_selection_cleanup");
                for (JsonObject tokens : previews) call("mc_edit_discard", tokens);
                if (EditHistory.size() != 0) EditHistory.clear();
            } finally {
                stand.discard();
                original.forEach((cell, state) -> level.setBlock(cell, state, 3));
                human.absMoveTo(humanPosition.x, humanPosition.y, humanPosition.z, humanYaw, humanPitch);
                body.absMoveTo(bodyPosition.x, bodyPosition.y, bodyPosition.z, bodyYaw, bodyPitch);
                AgentConfig.get().allowWrite = write;
                select(human, "clear");
            }
        }
    }

    private static boolean selectionBlocksIntact(ServerLevel level, BlockPos a, BlockPos b) {
        return level.getBlockState(a).is(Blocks.STONE) && level.getBlockState(b).is(Blocks.STONE)
                && level.getBlockState(a.south()).isAir() && level.getBlockState(a.east()).isAir();
    }

    private static void aimEast(ServerPlayer player, BlockPos eyeCell) {
        double eyeHeight = player.getEyeY() - player.getY();
        player.absMoveTo(eyeCell.getX() + 0.5, eyeCell.getY() + 0.5 - eyeHeight, eyeCell.getZ() + 0.5, -90, 0);
    }

    private static boolean select(ServerPlayer player, String operation) {
        return nativeCommand(player, "agent select " + operation);
    }

    private static boolean nativeCommand(ServerPlayer player, String command) {
        CommandReceipt receipt = new CommandReceipt(1024);
        var source = player.createCommandSourceStack().withPermission(4).withSuppressedOutput().withCallback(receipt::result);
        player.getServer().getCommands().performPrefixedCommand(source, command);
        return Json.l(receipt.toJson(), "resultSum", 0) > 0;
    }

    private static boolean atBlock(JsonObject value, BlockPos pos) {
        return Json.i(value, "x", Integer.MIN_VALUE) == pos.getX() && Json.i(value, "y", Integer.MIN_VALUE) == pos.getY()
                && Json.i(value, "z", Integer.MIN_VALUE) == pos.getZ();
    }

    private static JsonObject selectionOwner(ServerPlayer player) {
        JsonObject args = Json.obj();
        args.addProperty("playerId", player.getUUID().toString());
        return args;
    }

    private static JsonObject selectedPreviewRequest(JsonObject selection) {
        JsonObject args = Json.obj();
        for (String key : List.of("playerId", "worldSessionId", "selectionId")) args.add(key, selection.get(key).deepCopy());
        args.addProperty("block", "minecraft:gold_block");
        return args;
    }

    private static JsonObject jobTokens(JsonObject job) {
        JsonObject args = Json.obj(); args.add("jobId", job.get("jobId").deepCopy()); args.add("worldSessionId", job.get("worldSessionId").deepCopy()); return args;
    }

    private static JsonObject previewRequest(BlockPos a, BlockPos b, String block, ServerLevel level) {
        JsonObject args = Json.obj();
        args.addProperty("x1", a.getX()); args.addProperty("y1", a.getY()); args.addProperty("z1", a.getZ());
        args.addProperty("x2", b.getX()); args.addProperty("y2", b.getY()); args.addProperty("z2", b.getZ());
        args.addProperty("block", block); args.addProperty("dimension", level.dimension().location().toString()); return args;
    }

    private static JsonObject previewTokens(JsonObject preview) {
        JsonObject args = Json.obj();
        for (String key : List.of("planId", "worldSessionId", "planRevision")) args.add(key, preview.get(key).deepCopy());
        return args;
    }

    private static JsonObject textObjectIgnoringError(JsonObject result) {
        return Json.parseObject(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
    }

    private void checkUndoableEdits(ServerLevel level, BlockPos pos) throws Exception {
        if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("edit QA fixture is occupied");
        AgentMode.Mode mode = AgentMode.get();
        boolean write = AgentConfig.get().allowWrite;
        try {
            AgentMode.set(AgentMode.Mode.WORK);
            level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
            var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(pos);
            chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 3));
            chest.setChanged();
            var saved = chest.saveWithFullMetadata(level.registryAccess());
            JsonObject set = Json.obj(); set.addProperty("x", pos.getX()); set.addProperty("y", pos.getY()); set.addProperty("z", pos.getZ());
            set.addProperty("dimension", level.dimension().location().toString()); set.addProperty("block", "minecraft:stone");
            int before = EditHistory.size();
            AgentConfig.get().allowWrite = false;
            check("single edit rejects disabled writes without history", isError(call("mc_set_block", set)) && EditHistory.size() == before);
            AgentConfig.get().allowWrite = write;
            AgentMode.set(AgentMode.Mode.CHAT);
            check("single edit rejects restricted mode without history", isError(call("mc_set_block", set)) && EditHistory.size() == before);
            AgentMode.set(AgentMode.Mode.WORK);
            check("single edit actually replaces chest", !isError(call("mc_set_block", set)) && level.getBlockState(pos).is(Blocks.STONE));
            check("single edit adds exactly one undo operation", EditHistory.size() == before + 1);
            call("mc_undo", Json.obj());
            check("single edit undo restores chest", level.getBlockState(pos).is(Blocks.CHEST));
            check("single edit undo restores exact container NBT", saved.equals(level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess())));
            level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            set.addProperty("block", "minecraft:gold_block");
            check("no-op single edit reports command failure", isError(call("mc_set_block", set)));
            check("no-op single edit preserves undo history", EditHistory.size() == before);
            set.addProperty("block", "missing_qa:block");
            check("invalid block edit reports failure without history", isError(call("mc_set_block", set)) && EditHistory.size() == before);
            check("invalid block leaves original state intact", level.getBlockState(pos).is(Blocks.GOLD_BLOCK));
            check("no-op fill reports failure without history", isError(fill(pos, level)) && EditHistory.size() == before);
        } finally {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            EditHistory.clear(); AgentMode.set(mode); AgentConfig.get().allowWrite = write;
        }
    }

    private void checkUndoConflicts(ServerPlayer human, ServerLevel level, BlockPos pos) throws Exception {
        BlockPos other = pos.east();
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(other).isAir()) throw new IllegalStateException("undo fixture occupied");
        try {
            fill(pos, level);
            level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            JsonObject rejected = call("mc_undo", Json.obj());
            check("undo rejects a later player block change", isError(rejected) && level.getBlockState(pos).is(Blocks.DIAMOND_BLOCK));
            check("undo conflict retains its history entry", EditHistory.size() == 1);
            level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            check("undo succeeds when original after state is explicitly restored", !isError(call("mc_undo", Json.obj()))
                    && level.getBlockState(pos).isAir() && EditHistory.size() == 0);

            JsonObject plan = previewTokens(textObject(call("mc_edit_preview", previewRequest(pos, other, "minecraft:gold_block", level))));
            call("mc_edit_apply", plan);
            level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            check("undo preflights the whole operation before restoring any cell", isError(call("mc_undo", Json.obj()))
                    && level.getBlockState(other).is(Blocks.GOLD_BLOCK) && level.getBlockState(pos).is(Blocks.DIAMOND_BLOCK));
            EditHistory.clear(); level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3); level.setBlock(other, Blocks.AIR.defaultBlockState(), 3);

            fill(pos, level);
            plan = previewTokens(textObject(call("mc_edit_preview", previewRequest(pos, pos, "minecraft:stone", level))));
            call("mc_edit_apply", plan);
            JsonObject steps = Json.obj(); steps.addProperty("steps", 2);
            check("overlapping multi-step undo follows each operation after state", !isError(call("mc_undo", steps))
                    && level.getBlockState(pos).isAir() && EditHistory.size() == 0);

            fill(pos, level); level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3); fill(other, level);
            rejected = call("mc_undo", steps);
            check("multi-step conflict stops at the first protected operation", isError(rejected)
                    && level.getBlockState(pos).is(Blocks.DIAMOND_BLOCK) && level.getBlockState(other).isAir() && EditHistory.size() == 1);
            check("multi-step error reports completed earlier operations", rejected.getAsJsonArray("content").get(0).getAsJsonObject()
                    .get("text").getAsString().contains("1 earlier operations completed"));
            EditHistory.clear(); level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

            JsonObject set = Json.obj(); set.addProperty("x", pos.getX()); set.addProperty("y", pos.getY()); set.addProperty("z", pos.getZ());
            set.addProperty("dimension", level.dimension().location().toString()); set.addProperty("block", "minecraft:chest");
            call("mc_set_block", set);
            var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(pos);
            chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5)); chest.setChanged();
            check("NBT-only player changes block undo and preserve container contents", isError(call("mc_undo", Json.obj()))
                    && level.getBlockEntity(pos) == chest && chest.getItem(0).getCount() == 5 && EditHistory.size() == 1);
            EditHistory.clear(); level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

            List<EditHistory.Cell> before = new ArrayList<>(); EditHistory.capture(level, List.of(pos), "unknown", before);
            level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            EditHistory.pushRecorded("unknown after fixture", level, List.of(new EditHistory.Change(before.get(0), null)));
            check("missing after snapshot refuses blind undo", isError(call("mc_undo", Json.obj()))
                    && level.getBlockState(pos).is(Blocks.GOLD_BLOCK) && EditHistory.size() == 1);
            EditHistory.clear(); level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

            BlockPos unloaded = pos.offset(16000, 0, 16000);
            if (level.isLoaded(unloaded)) throw new IllegalStateException("unloaded fixture unexpectedly loaded");
            EditHistory.pushRecorded("unloaded target fixture", level, List.of(new EditHistory.Change(
                    new EditHistory.Cell(unloaded, Blocks.AIR.defaultBlockState(), null),
                    new EditHistory.Cell(unloaded, Blocks.STONE.defaultBlockState(), null))));
            check("undo refuses unloaded targets without loading their chunks", isError(call("mc_undo", Json.obj()))
                    && !level.isLoaded(unloaded) && EditHistory.size() == 1);
            EditHistory.clear();

            // The first cell changes again while a multi-tick job is still running.
            plan = previewTokens(textObject(call("mc_edit_preview", previewRequest(pos, other, "minecraft:stone", level))));
            plan.addProperty("batchCells", 1);
            JsonObject job = jobTokens(textObject(call("mc_edit_start", plan)));
            EditJobs.tick(human.getServer());
            level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            EditJobs.tick(human.getServer());
            check("job completion does not adopt a later edit to an already written cell", textObject(call("mc_edit_job", job))
                    .get("status").getAsString().equals("completed") && isError(call("mc_undo", Json.obj()))
                    && level.getBlockState(pos).is(Blocks.DIAMOND_BLOCK) && level.getBlockState(other).is(Blocks.STONE));
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            check("batched undo retains its immediate post-write snapshots", !isError(call("mc_undo", Json.obj()))
                    && level.getBlockState(pos).isAir() && level.getBlockState(other).isAir());

            plan = previewTokens(textObject(call("mc_edit_preview", previewRequest(pos, other, "minecraft:barrel", level))));
            plan.addProperty("batchCells", 1);
            job = jobTokens(textObject(call("mc_edit_start", plan))); EditJobs.tick(human.getServer());
            var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) level.getBlockEntity(pos);
            barrel.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 6)); barrel.setChanged();
            EditJobs.tick(human.getServer());
            check("batched undo protects NBT changed after an earlier cell write", isError(call("mc_undo", Json.obj()))
                    && barrel.getItem(0).getCount() == 6 && level.getBlockState(other).is(Blocks.BARREL));
            barrel.setItem(0, net.minecraft.world.item.ItemStack.EMPTY); barrel.setChanged();
            check("batched container undo works with unchanged post-write NBT", !isError(call("mc_undo", Json.obj()))
                    && level.getBlockState(pos).isAir() && level.getBlockState(other).isAir());
            level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
            var furnace = level.getBlockEntity(pos);
            var emptyFurnace = furnace.saveWithFullMetadata(level.registryAccess());
            List<EditHistory.Cell> furnaceBefore = new ArrayList<>(); EditHistory.capture(level, List.of(pos), "qa-furnace-nbt", furnaceBefore);
            var changedFurnace = emptyFurnace.copy(); var recipesUsed = new net.minecraft.nbt.CompoundTag();
            recipesUsed.putInt("minecraft:iron_ingot_from_smelting_iron_ore", 1); changedFurnace.put("RecipesUsed", recipesUsed);
            furnace.loadWithComponents(changedFurnace, level.registryAccess()); furnace.setChanged();
            boolean recipeSeeded = furnace.saveWithFullMetadata(level.registryAccess()).getCompound("RecipesUsed")
                    .getInt("minecraft:iron_ingot_from_smelting_iron_ore") == 1;
            int furnaceChanges = EditHistory.pushChanged("qa same-type furnace NBT", level, furnaceBefore);
            JsonObject furnaceUndo = call("mc_undo", Json.obj());
            check("same-type furnace undo restores exact recipe bookkeeping", recipeSeeded && furnaceChanges == 1 && !isError(furnaceUndo)
                    && emptyFurnace.equals(level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess())) && EditHistory.size() == 0);
        } finally {
            EditJobs.stopAll("qa_cleanup"); EditPlans.clear(); EditJobs.clear(); EditHistory.clear();
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3); level.setBlock(other, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private void checkBatchedUndo(ServerPlayer human, ServerLevel level, BlockPos first) throws Exception {
        var server = human.getServer();
        BlockPos middle = first.east(), last = first.east(2);
        List<BlockPos> cells = List.of(first, middle, last);
        if (EditHistory.size() != 0 || EditJobs.active() || UndoJobs.active()
                || cells.stream().anyMatch(pos -> !level.isLoaded(pos) || !level.getBlockState(pos).isAir()))
            throw new IllegalStateException("batched undo fixture needs empty loaded cells and history");
        List<BlockState> original = cells.stream().map(level::getBlockState).toList();
        try {
            level.setBlock(first, Blocks.BARREL.defaultBlockState(), 3);
            setUndoBarrelItem(level, first, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 3));
            var saved = level.getBlockEntity(first).saveWithFullMetadata(level.registryAccess());
            List<EditHistory.Cell> before = new ArrayList<>();
            EditHistory.capture(level, cells, "batch_undo", before);
            setUndoBarrelItem(level, first, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 2));
            var edited = level.getBlockEntity(first).saveWithFullMetadata(level.registryAccess());
            level.setBlock(middle, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            level.setBlock(last, Blocks.BARREL.defaultBlockState(), 3);
            EditHistory.pushChanged("QA batch undo with NBT conflict", level, before);
            setUndoBarrelItem(level, last, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 5));
            var playerNbt = level.getBlockEntity(last).saveWithFullMetadata(level.registryAccess());

            JsonObject strict = undoHeadRequest(null);
            JsonObject invalid = strict.deepCopy();
            invalid.addProperty("worldSessionId", UUID.randomUUID().toString());
            check("batched undo start rejects stale world", isError(call("mc_undo_start", invalid)));
            JsonObject started = textObject(call("mc_undo_start", strict));
            JsonObject job = jobTokens(started);
            check("batched undo accepts an exact record without writing", "checking".equals(Json.str(started, "phase", ""))
                    && Json.i(started, "restoredCells", -1) == 0 && level.getBlockState(middle).is(Blocks.GOLD_BLOCK));
            check("pending undo prevents interleaved managed edits and undo starts",
                    isError(call("mc_fill", previewRequest(middle, middle, "minecraft:stone", level)))
                    && level.getBlockState(middle).is(Blocks.GOLD_BLOCK)
                    && isError(call("mc_undo", Json.obj())) && isError(call("mc_undo_start", strict)));
            UndoJobs.tick(server);
            JsonObject progress = textObject(call("mc_undo_job", job));
            check("one-cell batch bounds preflight before any restoration", Json.i(progress, "checkedCells", -1) == 1
                    && Json.i(progress, "restoredCells", -1) == 0 && level.getBlockState(middle).is(Blocks.GOLD_BLOCK));
            check("active undo accepts a matching keepalive", !isError(call("mc_undo_keepalive", job)));
            JsonObject terminal = finishUndo(server, job);
            check("strict batch preflights the entire record and retains NBT conflict without writes",
                    "conflict".equals(Json.str(terminal, "status", "")) && Json.i(terminal, "checkedCells", -1) == 3
                    && Json.i(terminal, "restoredCells", -1) == 0 && Json.i(terminal, "conflictCells", -1) == 1
                    && Json.str(strict, "recordId", "").equals(Json.str(undoHeadRequest(null), "recordId", "?"))
                    && edited.equals(level.getBlockEntity(first).saveWithFullMetadata(level.registryAccess()))
                    && level.getBlockState(middle).is(Blocks.GOLD_BLOCK)
                    && playerNbt.equals(level.getBlockEntity(last).saveWithFullMetadata(level.registryAccess())));

            JsonObject skip = undoHeadRequest("skip_conflicts");
            terminal = finishUndo(server, jobTokens(textObject(call("mc_undo_start", skip))));
            JsonObject residual = undoHeadRequest(null);
            check("skip policy restores matching cells and exact saved NBT, leaving player NBT intact",
                    "conflict".equals(Json.str(terminal, "status", "")) && Json.i(terminal, "restoredCells", -1) == 2
                    && Json.i(terminal, "verifiedCells", -1) == 2 && Json.i(terminal, "remainingCells", -1) == 1
                    && saved.equals(level.getBlockEntity(first).saveWithFullMetadata(level.registryAccess()))
                    && level.getBlockState(middle).isAir()
                    && playerNbt.equals(level.getBlockEntity(last).saveWithFullMetadata(level.registryAccess())));
            check("skip policy retains a new exact residual record identity",
                    Json.str(residual, "recordId", "").equals(Json.str(terminal, "remainingRecordId", "?"))
                    && !Json.str(residual, "recordId", "").equals(Json.str(skip, "recordId", ""))
                    && isError(call("mc_undo_start", skip)));
            setUndoBarrelItem(level, last, net.minecraft.world.item.ItemStack.EMPTY);
            terminal = finishUndo(server, jobTokens(textObject(call("mc_undo_start", residual))));
            check("residual restores only after its original after-state is available again",
                    "completed".equals(Json.str(terminal, "status", "")) && level.getBlockState(last).isAir()
                    && saved.equals(level.getBlockEntity(first).saveWithFullMetadata(level.registryAccess())) && EditHistory.size() == 0);
            setUndoBarrelItem(level, first, net.minecraft.world.item.ItemStack.EMPTY);
            level.setBlock(first, original.get(0), 3);

            recordUndoStone(level, cells);
            JsonObject interrupted = undoHeadRequest(null);
            job = jobTokens(textObject(call("mc_undo_start", interrupted)));
            tickUntilFirstUndoRestore(server, job);
            terminal = textObject(call("mc_undo_cancel", job));
            UndoJobs.tick(server);
            check("cancelling a partially restored undo retains only unresolved cells",
                    "cancelled".equals(Json.str(terminal, "status", "")) && Json.b(terminal, "partial", false)
                    && Json.i(terminal, "restoredCells", -1) == 1 && Json.i(terminal, "remainingCells", -1) == 2
                    && level.getBlockState(last).isAir() && level.getBlockState(first).is(Blocks.STONE)
                    && level.getBlockState(middle).is(Blocks.STONE) && isError(call("mc_undo_start", interrupted))
                    && isError(call("mc_undo_keepalive", job)));

            residual = undoHeadRequest(null);
            job = jobTokens(textObject(call("mc_undo_start", residual)));
            try {
                AgentControl.stop("qa_batched_undo_stop");
                UndoJobs.tick(server);
                terminal = textObject(call("mc_undo_job", job));
                check("global stop cancels pending undo and leaves receipts readable",
                        "cancelled".equals(Json.str(terminal, "status", "")) && Json.i(terminal, "restoredCells", -1) == 0
                        && level.getBlockState(first).is(Blocks.STONE) && level.getBlockState(middle).is(Blocks.STONE)
                        && isError(call("mc_undo_keepalive", job)));
            } finally { AgentControl.resume(); }
            terminal = finishUndo(server, jobTokens(textObject(call("mc_undo_start", residual))));
            check("explicit fresh undo restores the residual after a stop",
                    "completed".equals(Json.str(terminal, "status", "")) && Json.i(terminal, "restoredCells", -1) == 2
                    && cells.stream().allMatch(pos -> level.getBlockState(pos).isAir()) && EditHistory.size() == 0);

            recordUndoStone(level, cells);
            job = jobTokens(textObject(call("mc_undo_start", undoHeadRequest(null))));
            tickUntilFirstUndoRestore(server, job);
            level.setBlock(middle, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            terminal = finishUndo(server, job);
            check("undo rechecks after preflight and preserves a later player write",
                    "conflict".equals(Json.str(terminal, "status", "")) && Json.i(terminal, "restoredCells", -1) == 1
                    && Json.i(terminal, "remainingCells", -1) == 2 && level.getBlockState(last).isAir()
                    && level.getBlockState(middle).is(Blocks.DIAMOND_BLOCK) && level.getBlockState(first).is(Blocks.STONE));
            level.setBlock(middle, Blocks.STONE.defaultBlockState(), 3);
            terminal = finishUndo(server, jobTokens(textObject(call("mc_undo_start", undoHeadRequest(null)))));
            check("fresh undo can finish the late-conflict residual after deliberate reconciliation",
                    "completed".equals(Json.str(terminal, "status", "")) && cells.stream().allMatch(pos -> level.getBlockState(pos).isAir())
                    && EditHistory.size() == 0);

            recordUndoStone(level, cells);
            JsonObject beforeVerification = undoHeadRequest(null);
            job = jobTokens(textObject(call("mc_undo_start", beforeVerification)));
            tickUntilFirstUndoRestore(server, job);
            if (!level.getBlockState(last).isAir()) throw new IllegalStateException("first restored fixture cell did not read back as air");
            level.setBlock(last, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            terminal = finishUndo(server, job);
            JsonObject unverified = undoHeadRequest("skip_conflicts");
            check("final verification preserves a later change to an already restored cell",
                    "conflict".equals(Json.str(terminal, "status", "")) && Json.i(terminal, "restoredCells", -1) == 3
                    && Json.i(terminal, "verifiedCells", -1) == 2 && Json.i(terminal, "postRestoreConflictCells", -1) == 1
                    && Json.i(terminal, "remainingCells", -1) == 1 && Json.i(terminal, "uncertainCells", -1) == 1
                    && level.getBlockState(first).isAir() && level.getBlockState(middle).isAir()
                    && level.getBlockState(last).is(Blocks.DIAMOND_BLOCK)
                    && Json.str(unverified, "recordId", "").equals(Json.str(terminal, "remainingRecordId", "?"))
                    && !Json.str(unverified, "recordId", "").equals(Json.str(beforeVerification, "recordId", "")));
            // Even when the cell now equals the original edit's after-state, its
            // unverified residue cannot authorize another automatic restoration.
            level.setBlock(last, Blocks.STONE.defaultBlockState(), 3);
            terminal = finishUndo(server, jobTokens(textObject(call("mc_undo_start", unverified))));
            check("a post-restore conflict remains unknown to future undo jobs",
                    "outcome_unknown".equals(Json.str(terminal, "status", "")) && "missing_after_snapshot".equals(Json.str(terminal, "reason", ""))
                    && Json.i(terminal, "restoredCells", -1) == 0 && level.getBlockState(last).is(Blocks.STONE)
                    && Json.str(unverified, "recordId", "").equals(Json.str(undoHeadRequest(null), "recordId", "?")));
            EditHistory.clear();
            level.setBlock(last, original.get(2), 3);

            checkUndoVerificationException(level, cells);
            EditHistory.clear();
            level.setBlock(middle, original.get(1), 3);

            BlockPos unloaded = first.offset(16000, 0, 16000);
            if (level.getChunkSource().getChunkNow(unloaded.getX() >> 4, unloaded.getZ() >> 4) != null)
                throw new IllegalStateException("unloaded undo fixture unexpectedly loaded");
            EditHistory.pushRecorded("QA batched undo unloaded cell", level, List.of(new EditHistory.Change(
                    new EditHistory.Cell(unloaded, Blocks.AIR.defaultBlockState(), null),
                    new EditHistory.Cell(unloaded, Blocks.STONE.defaultBlockState(), null))));
            JsonObject unavailable = undoHeadRequest("skip_conflicts");
            terminal = finishUndo(server, jobTokens(textObject(call("mc_undo_start", unavailable))));
            check("batched undo does not load a missing chunk even under skip policy",
                    "failed".equals(Json.str(terminal, "status", "")) && "target_chunk_unloaded".equals(Json.str(terminal, "reason", ""))
                    && Json.i(terminal, "restoredCells", -1) == 0
                    && level.getChunkSource().getChunkNow(unloaded.getX() >> 4, unloaded.getZ() >> 4) == null
                    && Json.str(unavailable, "recordId", "").equals(Json.str(undoHeadRequest(null), "recordId", "?")));
            EditHistory.clear();

            EditHistory.pushRecorded("QA batched undo uncertain cell", level, List.of(new EditHistory.Change(
                    new EditHistory.Cell(first, original.get(0), null), null)));
            JsonObject unknown = undoHeadRequest("skip_conflicts");
            terminal = finishUndo(server, jobTokens(textObject(call("mc_undo_start", unknown))));
            check("skip policy retains unknown after-snapshot without touching the world",
                    "outcome_unknown".equals(Json.str(terminal, "status", "")) && "missing_after_snapshot".equals(Json.str(terminal, "reason", ""))
                    && Json.i(terminal, "uncertainCells", 0) == 1 && Json.i(terminal, "restoredCells", -1) == 0
                    && level.getBlockState(first).equals(original.get(0))
                    && Json.str(unknown, "recordId", "").equals(Json.str(undoHeadRequest(null), "recordId", "?")));
            EditHistory.clear();

            recordUndoStone(level, List.of(first));
            JsonObject older = undoHeadRequest(null);
            recordUndoStone(level, List.of(middle));
            level.setBlock(middle, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            JsonObject disposable = undoHeadRequest(null);
            String discard = "agent edit discard-undo " + Json.str(disposable, "recordId", "");
            job = jobTokens(textObject(call("mc_undo_start", disposable)));
            check("native discard cannot remove a record during a managed undo", !nativeCommand(human, discard + " confirm")
                    && EditHistory.size() == 2);
            terminal = finishUndo(server, job);
            if (!"conflict".equals(Json.str(terminal, "status", ""))) throw new IllegalStateException("disposable record did not conflict");
            JsonObject bridged = GameBridge.runCommandResult(discard + " confirm", human.getGameProfile().getName(), 4, level);
            check("bridge cannot discard the exact retained conflict even with confirmation",
                    Json.l(bridged, "resultSum", -1) == 0 && EditHistory.size() == 2);
            check("native discard requires confirmation and the exact head identity",
                    !nativeCommand(human, discard)
                    && !nativeCommand(human, "agent edit discard-undo " + Json.str(older, "recordId", "") + " confirm")
                    && EditHistory.size() == 2);
            check("native confirmation discards only the retained conflict and preserves current blocks",
                    nativeCommand(human, discard + " confirm") && EditHistory.size() == 1
                    && Json.str(older, "recordId", "").equals(Json.str(undoHeadRequest(null), "recordId", "?"))
                    && level.getBlockState(first).is(Blocks.STONE) && level.getBlockState(middle).is(Blocks.DIAMOND_BLOCK));
            terminal = finishUndo(server, jobTokens(textObject(call("mc_undo_start", older))));
            check("older record remains usable after explicit conflict disposal",
                    "completed".equals(Json.str(terminal, "status", "")) && EditHistory.size() == 0
                    && level.getBlockState(first).equals(original.get(0)) && level.getBlockState(middle).is(Blocks.DIAMOND_BLOCK));
        } finally {
            UndoJobs.stopAll("qa_batched_undo_cleanup");
            UndoJobs.clear();
            EditHistory.clear();
            for (int i = 0; i < cells.size(); i++) {
                BlockPos pos = cells.get(i);
                if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container inventory) inventory.clearContent();
                level.setBlock(pos, original.get(i), 3);
            }
        }
    }

    private void checkUndoVerificationException(ServerLevel level, List<BlockPos> cells) throws Exception {
        var server = level.getServer();
        BlockPos first = cells.get(0), middle = cells.get(1), last = cells.get(2);
        BlockState barrel = Blocks.BARREL.defaultBlockState();
        level.setBlock(middle, barrel, 3);
        var beforeNbt = level.getBlockEntity(middle).saveWithFullMetadata(level.registryAccess());
        List<EditHistory.Cell> before = new ArrayList<>();
        EditHistory.capture(level, cells, "batch_undo_verification_fault", before);
        setUndoBarrelItem(level, middle, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 2));
        level.setBlock(first, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(last, Blocks.STONE.defaultBlockState(), 3);
        if (EditHistory.pushChanged("QA batch undo verification fault", level, before) != cells.size())
            throw new IllegalStateException("verification fixture did not change every cell");

        JsonObject original = undoHeadRequest(null);
        JsonObject job = jobTokens(textObject(call("mc_undo_start", original)));
        JsonObject progress = textObject(call("mc_undo_job", job));
        for (int i = 0; i < 12 && Json.i(progress, "verifiedCells", -1) == 0; i++) {
            UndoJobs.tick(server);
            progress = textObject(call("mc_undo_job", job));
        }
        if (!"verifying".equals(Json.str(progress, "phase", "")) || Json.i(progress, "verifiedCells", -1) != 1
                || Json.i(progress, "restoredCells", -1) != 3
                || !beforeNbt.equals(level.getBlockEntity(middle).saveWithFullMetadata(level.registryAccess())))
            throw new IllegalStateException("verification fixture did not pause after its first final check");

        var throwingBarrel = new net.minecraft.world.level.block.entity.BarrelBlockEntity(middle, barrel) {
            @Override
            protected void saveAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
                throw new IllegalStateException("QA injected undo verification serialization failure");
            }
        };
        JsonObject terminal;
        try {
            level.removeBlockEntity(middle);
            level.setBlockEntity(throwingBarrel);
            if (level.getBlockEntity(middle) != throwingBarrel) throw new IllegalStateException("verification fault fixture was not installed");
            terminal = finishUndo(server, job);
        } finally {
            level.removeBlockEntity(middle);
            level.setBlockEntity(java.util.Objects.requireNonNull(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                    middle, barrel, beforeNbt.copy(), level.registryAccess())));
        }
        JsonObject residue = undoHeadRequest(null);
        check("verification exception retains only the whole unverified restored tail",
                "outcome_unknown".equals(Json.str(terminal, "status", "")) && "undo_exception".equals(Json.str(terminal, "reason", ""))
                && Json.i(terminal, "restoredCells", -1) == 3 && Json.i(terminal, "verifiedCells", -1) == 1
                && Json.i(terminal, "remainingCells", -1) == 2 && Json.i(terminal, "uncertainCells", -1) == 2
                && Json.str(residue, "recordId", "").equals(Json.str(terminal, "remainingRecordId", "?"))
                && !Json.str(residue, "recordId", "").equals(Json.str(original, "recordId", ""))
                && level.getBlockState(first).isAir() && level.getBlockState(last).isAir()
                && beforeNbt.equals(level.getBlockEntity(middle).saveWithFullMetadata(level.registryAccess())));
    }

    private static void setUndoBarrelItem(ServerLevel level, BlockPos pos, net.minecraft.world.item.ItemStack item) {
        var inventory = (net.minecraft.world.Container) level.getBlockEntity(pos);
        inventory.setItem(0, item);
        inventory.setChanged();
    }

    private static void recordUndoStone(ServerLevel level, List<BlockPos> cells) {
        List<EditHistory.Cell> before = new ArrayList<>();
        EditHistory.capture(level, cells, "batch_undo_stone", before);
        for (BlockPos pos : cells) level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        if (EditHistory.pushChanged("QA batched stone undo", level, before) != cells.size())
            throw new IllegalStateException("stone undo fixture did not change every cell");
    }

    private static JsonObject undoHeadRequest(String policy) throws Exception {
        JsonObject listing = textObject(call("mc_undo_records", Json.obj()));
        JsonObject request = Json.obj();
        request.add("worldSessionId", listing.get("worldSessionId").deepCopy());
        request.add("recordId", listing.getAsJsonArray("records").get(0).getAsJsonObject().get("recordId").deepCopy());
        request.addProperty("batchCells", 1);
        if (policy != null) request.addProperty("policy", policy);
        return request;
    }

    private static JsonObject finishUndo(net.minecraft.server.MinecraftServer server, JsonObject job) throws Exception {
        for (int i = 0; i < 32; i++) {
            JsonObject progress = textObject(call("mc_undo_job", job));
            if (!"running".equals(Json.str(progress, "status", ""))) return progress;
            UndoJobs.tick(server);
        }
        throw new IllegalStateException("batched undo did not reach a terminal receipt");
    }

    private static void tickUntilFirstUndoRestore(net.minecraft.server.MinecraftServer server, JsonObject job) throws Exception {
        for (int i = 0; i < 12; i++) {
            JsonObject progress = textObject(call("mc_undo_job", job));
            if (!"running".equals(Json.str(progress, "status", ""))) break;
            if (Json.i(progress, "restoredCells", -1) == 1) return;
            UndoJobs.tick(server);
        }
        throw new IllegalStateException("batched undo did not stop between its first and second restores");
    }

    private static JsonObject observeRequest(ServerLevel level, BlockPos pos, String path, String comparison, String expected) {
        JsonObject request = Json.obj(); request.addProperty("action", "start");
        request.addProperty("worldSessionId", GameBridge.worldSessionId()); request.addProperty("taskId", "qa-" + java.util.UUID.randomUUID());
        request.addProperty("x", pos.getX()); request.addProperty("y", pos.getY()); request.addProperty("z", pos.getZ());
        request.addProperty("dimension", level.dimension().location().toString()); request.addProperty("path", path);
        request.addProperty("comparison", comparison); if (expected != null) request.addProperty("expectedSnbt", expected);
        return request;
    }

    private void checkBlockObservations(ServerLevel level, BlockPos pos) throws Exception {
        if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("observation fixture occupied");
        AgentMode.Mode mode = AgentMode.get(); boolean write = AgentConfig.get().allowWrite;
        BlockObservations.clear();
        try {
            level.setBlock(pos, Blocks.BARREL.defaultBlockState(), 3);
            var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) level.getBlockEntity(pos);
            barrel.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 5)); barrel.setChanged();
            var before = barrel.saveWithFullMetadata(level.registryAccess());
            JsonObject request = observeRequest(level, pos, "Items[0].count", "equals", "5");
            JsonObject started = textObject(call("mc_observe", request));
            check("NBT observation reads actual typed item count", started.get("matched").getAsBoolean()
                    && started.get("taskId").equals(request.get("taskId")) && started.get("worldSessionId").getAsString().equals(GameBridge.worldSessionId()));
            JsonObject handle = Json.obj(); handle.add("observationId", started.get("observationId")); handle.add("worldSessionId", started.get("worldSessionId"));
            check("completed observation wait returns its actual result", textObject(call("mc_observe_wait", handle)).get("matched").getAsBoolean());
            check("retained task id prevents duplicate observation registration", isError(call("mc_observe", request)));
            request = observeRequest(level, pos, "Items[0].count", "at_least", "4b");
            check("numeric observation accepts differing numeric tag widths", textObject(call("mc_observe", request)).get("matched").getAsBoolean());
            request = observeRequest(level, pos, "Items[0].count", "equals", "5b");
            check("typed equality does not conflate int and byte tags", textObject(call("mc_observe", request)).get("outcome").getAsString().equals("waiting"));
            request = observeRequest(level, pos, "Items[{Slot:2b}].count", "at_least", "1");
            started = textObject(call("mc_observe", request));
            check("missing NBT path is waiting rather than a fabricated match", started.get("outcome").getAsString().equals("waiting")
                    && started.getAsJsonObject("lastSample").get("valueCount").getAsInt() == 0);
            handle.add("observationId", started.get("observationId"));
            check("pending observation cannot block the Minecraft server thread", isError(call("mc_observe_wait", handle)));
            handle.addProperty("action", "cancel");
            check("observation cancellation is explicit and terminal", textObject(call("mc_observe", handle)).get("outcome").getAsString().equals("cancelled"));
            handle.addProperty("worldSessionId", "stale-world");
            check("observation rejects stale world handles", isError(call("mc_observe", handle)));
            handle.remove("action"); check("observation wait rejects stale world handles", isError(call("mc_observe_wait", handle)));
            request = observeRequest(level, pos, "Items[", "exists", null);
            check("invalid observation paths are rejected", isError(call("mc_observe", request)));
            request = observeRequest(level, pos, "Items", "exists", "1");
            check("exists rejects an ambiguous expected value", isError(call("mc_observe", request)));
            request = observeRequest(level, pos, "Items[0].count", "at_least", "1 2");
            check("observation rejects trailing SNBT values", isError(call("mc_observe", request)));
            request = observeRequest(level, pos, "Items[0].count", "at_least", "\"five\"");
            check("numeric condition rejects string expectations", isError(call("mc_observe", request)));
            request = observeRequest(level, pos, "Items", "equals", "[");
            check("incomplete expected SNBT returns a parameter error", isError(call("mc_observe", request)));
            request = observeRequest(level, pos, "Items", "exists", null); request.addProperty("x", 4294967296L + pos.getX());
            check("observation rejects coordinate overflow before integer conversion", isError(call("mc_observe", request)));
            request = observeRequest(level, pos, "Items", "exists", null); request.addProperty("timeoutMs", 4294967296L + 60000);
            check("observation rejects timeout overflow before integer conversion", isError(call("mc_observe", request)));
            AgentMode.set(AgentMode.Mode.CHAT); AgentConfig.get().allowWrite = false;
            request = observeRequest(level, pos, "Items[0].count", "at_least", "1");
            check("observation remains read-only in restricted mode with writes disabled", textObject(call("mc_observe", request)).get("matched").getAsBoolean()
                    && before.equals(barrel.saveWithFullMetadata(level.registryAccess())));
            AgentMode.set(mode); AgentConfig.get().allowWrite = write;
            BlockObservations.clear();
            for (int i = 0; i < 8; i++) call("mc_observe", observeRequest(level, pos, "Missing", "exists", null));
            check("observation bounds active tasks without changing the target", isError(call("mc_observe", observeRequest(level, pos, "Missing", "exists", null)))
                    && before.equals(barrel.saveWithFullMetadata(level.registryAccess())));
            BlockObservations.stopAll("qa_stop");
            JsonObject list = Json.obj(); list.addProperty("action", "list"); list.addProperty("worldSessionId", GameBridge.worldSessionId());
            check("stopping observations releases all active conditions", textObject(call("mc_observe", list)).getAsJsonArray("observations").asList().stream()
                    .allMatch(value -> value.getAsJsonObject().get("terminal").getAsBoolean()));
            for (int i = 0; i < 20; i++) call("mc_observe", observeRequest(level, pos, "Items", "exists", null));
            check("observation retains at most sixteen terminal receipts", textObject(call("mc_observe", list)).getAsJsonArray("observations").size() == 16);
            BlockObservations.clear();
            started = textObject(call("mc_observe", observeRequest(level, pos, "Missing", "exists", null)));
            handle = Json.obj(); handle.addProperty("action", "cancel"); handle.add("observationId", started.get("observationId"));
            handle.add("worldSessionId", started.get("worldSessionId"));
            for (int i = 0; i < 16; i++) call("mc_observe", observeRequest(level, pos, "Items", "exists", null));
            call("mc_observe", handle); handle.addProperty("action", "status");
            check("terminal retention uses completion order and preserves newly finished long tasks", textObject(call("mc_observe", handle))
                    .get("outcome").getAsString().equals("cancelled") && textObject(call("mc_observe", list)).getAsJsonArray("observations").size() == 16);
            BlockObservations.clear();
            started = textObject(call("mc_observe", observeRequest(level, pos, "Missing", "exists", null)));
            handle = Json.obj(); handle.addProperty("action", "status"); handle.add("observationId", started.get("observationId"));
            handle.add("worldSessionId", started.get("worldSessionId"));
            AgentMode.set(AgentMode.Mode.CHAT); AgentMode.set(AgentMode.Mode.WORK);
            check("mode round trip cancels an old observation", textObject(call("mc_observe", handle)).get("outcome").getAsString().equals("cancelled"));
            check("observation does not create edit history", EditHistory.size() == 0);
        } finally {
            BlockObservations.clear(); level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            AgentMode.set(mode); AgentConfig.get().allowWrite = write;
        }
    }

    private void checkKnowledge(ServerPlayer human) throws Exception {
        AgentMode.Mode originalMode = AgentMode.get();
        try {
            AgentMode.set(AgentMode.Mode.CHAT);
            JsonObject environment = textObject(call("mc_knowledge", Json.obj()));
            check("knowledge reports running version", environment.get("minecraft").getAsString().equals(human.getServer().getServerVersion()));
            check("knowledge reports installed mods", !environment.getAsJsonArray("mods").isEmpty());
            JsonObject args = Json.obj();
            args.addProperty("player", human.getGameProfile().getName());
            args.addProperty("command", "give @s minecraft:iron_sword[minecraft:enchantments={levels:{\"minecraft:sharpness\":10}}] 1");
            int before = human.getInventory().countItem(net.minecraft.world.item.Items.IRON_SWORD);
            JsonObject parsed = textObject(call("mc_command_inspect", args));
            check("sharpness X candidate parses against live grammar", parsed.get("syntaxComplete").getAsBoolean());
            check("inspection never grants command execution in Chat", !parsed.get("executionAllowedByBridge").getAsBoolean());
            check("parsing give did not give an item", human.getInventory().countItem(net.minecraft.world.item.Items.IRON_SWORD) == before);
            args.addProperty("command", "give @s minecraft:iron_sword[");
            check("invalid item component syntax diagnosed", !textObject(call("mc_command_inspect", args)).get("syntaxComplete").getAsBoolean());
            JsonObject item = Json.obj();
            item.addProperty("player", human.getGameProfile().getName());
            item.addProperty("slot", 0);
            JsonObject inspected = textObject(call("mc_item_inspect", item));
            var actual = human.getInventory().items.get(0);
            check("item inspection reads real inventory", inspected.get("count").getAsInt() == actual.getCount()
                    && inspected.get("empty").getAsBoolean() == actual.isEmpty());
            if (!actual.isEmpty()) {
                check("item inspection preserves actual components", inspected.get("savedStackSnbt").getAsString()
                        .equals(actual.save(human.registryAccess()).toString()));
            }
        } finally {
            AgentMode.set(originalMode);
        }
    }

    private static JsonObject textObject(JsonObject result) {
        if (isError(result)) throw new IllegalStateException(result.toString());
        return Json.parseObject(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
    }

    private void click(PlayerInteractEvent.LeftClickBlock event) {
        if (cancelClick && event.getEntity() == body && event.getPos().equals(deniedClick)) event.setCanceled(true);
    }

    private void pre(ServerTickEvent.Pre event) {
        sampleTick = body != null && !body.isRemoved();
        if (sampleTick) beforeTick = gameTicks(body);
    }

    private void post(ServerTickEvent.Post event) {
        if (controlQueueCheckAt != 0 && event.getServer().getTickCount() >= controlQueueCheckAt) {
            check("queued MCP write stays rejected after stop and resume", queuedControlTool.get() != null
                    && queuedControlTool.get().contains("control changed before execution") && controlQueueLevel.getBlockState(controlQueuePos).isAir());
            check("queued raw command stays rejected after stop and resume", queuedControlCommand.get() != null
                    && queuedControlCommand.get().contains("control changed before execution") && controlQueueLevel.getBlockState(controlQueuePos).isAir());
            controlQueueCheckAt = 0;
        }
        if (expiredTaskCheckAt != 0 && event.getServer().getTickCount() >= expiredTaskCheckAt) {
            check("expired queued request does not execute on a later tick", !expiredTaskRan.get());
            expiredTaskCheckAt = 0;
        }
        // Nested Brigadier commands enqueue their work in the outer command context.
        // Run on a later tick so assertions observe the same synchronous path as MCP.
        if (pendingRun != null) {
            ServerPlayer human = pendingRun;
            pendingRun = null;
            run(human);
        }
        if (restoreModeAt != 0 && event.getServer().getTickCount() >= restoreModeAt) {
            AgentMode.set(previousMode);
            restoreModeAt = 0;
        }
        if (sampleTick && body != null && !body.isRemoved()) {
            tickSamples++;
            if (gameTicks(body) - beforeTick != 1) badTickSamples++;
        }
    }

    private static int gameTicks(ServerPlayer player) {
        try {
            Field field = net.minecraft.server.level.ServerPlayerGameMode.class.getDeclaredField("gameTicks");
            field.setAccessible(true);
            return field.getInt(player.gameMode);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonObject fill(BlockPos pos, ServerLevel level) throws Exception {
        JsonObject args = Json.obj();
        for (String suffix : List.of("1", "2")) {
            args.addProperty("x" + suffix, pos.getX());
            args.addProperty("y" + suffix, pos.getY());
            args.addProperty("z" + suffix, pos.getZ());
        }
        args.addProperty("dimension", level.dimension().location().toString());
        args.addProperty("block", "minecraft:gold_block");
        return call("mc_fill", args);
    }

    private static JsonObject call(String name, JsonObject args) throws Exception {
        return McAgent.TOOLS.get(name).call(args).toJson();
    }

    private static boolean isError(JsonObject result) { return Json.b(result, "isError", false); }
    private void check(String name, boolean passed) { results.add((passed ? "PASS: " : "FAIL: ") + name); }
}
