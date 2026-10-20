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
import net.minecraft.world.level.block.Blocks;
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
import java.util.List;

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
            AgentMode.set(AgentMode.Mode.WORK);
            AgentConfig.get().allowWrite = true;
            ServerLevel level = human.serverLevel();
            BlockPos p = human.blockPosition().offset(8 + 8 * runs++, 8, 8);
            level.getChunkAt(p);
            if (!level.getBlockState(p).isAir() || !level.getBlockState(p.below()).isAir()) {
                throw new IllegalStateException("QA fixture area is occupied");
            }
            level.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
            body = FakePlayers.spawn(human.getServer(), level, "McAgentQA", p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
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

    private void click(PlayerInteractEvent.LeftClickBlock event) {
        if (cancelClick && event.getEntity() == body && event.getPos().equals(deniedClick)) event.setCanceled(true);
    }

    private void pre(ServerTickEvent.Pre event) {
        sampleTick = body != null && !body.isRemoved();
        if (sampleTick) beforeTick = gameTicks(body);
    }

    private void post(ServerTickEvent.Post event) {
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
