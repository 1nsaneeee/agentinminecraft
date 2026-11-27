package dev.mcagent;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Native selection and local operator controls. */
public final class AgentCommands {

    private AgentCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        d.register(Commands.literal("agent")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("stop").executes(ctx -> {
                    JsonObject state = AgentControl.stop("human_stopped");
                    ctx.getSource().sendSuccess(() -> Component.literal("[agent] 已停止操作；仍可读取世界。/agent resume 仅重新开放新请求，不重放旧任务。"
                            + (state.getAsJsonArray("stopErrors").isEmpty() ? "" : " 部分停止失败，请检查日志并重试停止。")), false);
                    return state.getAsJsonArray("stopErrors").isEmpty() ? 1 : 0;
                }))
                .then(Commands.literal("resume").executes(ctx -> {
                    if (GameBridge.bridgeCommand()) {
                        ctx.getSource().sendFailure(Component.literal("[agent] resume requires native human input, not a bridge command")); return 0;
                    }
                    try {
                        var human = ctx.getSource().getPlayerOrException();
                        if (dev.mcagent.fakeplayer.FakePlayers.byName(human.getGameProfile().getName()) != null)
                            throw new IllegalStateException("only a human player can resume agent operations");
                        AgentControl.resume();
                        ctx.getSource().sendSuccess(() -> Component.literal("[agent] 已重新开放新操作；请从外部 Agent 发起新任务。"), false);
                        return 1;
                    } catch (IllegalStateException error) {
                        ctx.getSource().sendFailure(Component.literal("[agent] " + error.getMessage())); return 0;
                    }
                }))
                .then(editBranch())
                .then(PlayerSelections.command())
                .then(Commands.literal("config")
                        .requires(src -> !GameBridge.bridgeCommand())
                        .executes(ctx -> showConfig(ctx.getSource()))
                        .then(Commands.literal("reload").executes(ctx -> reloadConfig(ctx.getSource())))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> setConfig(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "key"),
                                                        StringArgumentType.getString(ctx, "value"))))))));

    }


    private static LiteralArgumentBuilder<CommandSourceStack> editBranch() {
        return Commands.literal("edit")
                .then(Commands.literal("stop").executes(ctx -> {
                    EditJobs.stopAll("human_stopped");
                    UndoJobs.stopAll("human_stopped");
                    EditPlans.clear();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "[agent] 已停止当前编辑和撤销任务，并丢弃未执行预览。请查询实际结果和撤销记录。"), false);
                    return 1;
                }))
                .then(Commands.literal("discard-undo")
                        .then(Commands.argument("recordId", StringArgumentType.word())
                                .then(Commands.literal("confirm").executes(ctx ->
                                        discardUndo(ctx.getSource(), StringArgumentType.getString(ctx, "recordId"))))));
    }

    private static int discardUndo(CommandSourceStack source, String recordId) {
        var player = source.getPlayer();
        if (GameBridge.bridgeCommand() || player == null || source.source != player
                || player instanceof net.neoforged.neoforge.common.util.FakePlayer
                || dev.mcagent.fakeplayer.FakePlayers.byName(player.getGameProfile().getName()) == player
                || source.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
            source.sendFailure(Component.literal("[agent] 仅真人可在游戏内确认丢弃撤销记录。"));
            return 0;
        }
        if (EditJobs.active()) {
            source.sendFailure(Component.literal("[agent] 请先停止当前编辑或撤销任务。"));
            return 0;
        }
        if (!EditHistory.discardHead(recordId)) {
            source.sendFailure(Component.literal("[agent] 撤销记录已变化。请先查 mc_undo_records。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("[agent] 已丢弃指定撤销记录。游戏方块保持当前状态。"), false);
        return 1;
    }

    /** Print every value, one line each. The token is masked - never echo a credential. */
    private static int showConfig(CommandSourceStack src) {
        java.util.List<String> lines = AgentConfig.get().describeLines();
        src.sendSuccess(() -> Component.literal("[agent] " + lines.size() + " settings"), false);
        for (String line : lines) {
            src.sendSuccess(() -> Component.literal("  " + line), false);
        }
        src.sendSuccess(() -> Component.literal(
                "  settable: " + String.join(" ", AgentConfig.SETTABLE)), false);
        src.sendSuccess(() -> Component.literal(
                "  /agent config set <key> <value>   |   /agent config reload"), false);
        return 1;
    }

    /** Re-read configuration and revoke old connections without restarting the game. */
    private static int reloadConfig(CommandSourceStack src) {
        try {
            if (!McAgent.reloadConfig()) {
                src.sendFailure(Component.literal("[agent] config file was never loaded"));
                return 0;
            }
        } catch (IllegalStateException error) {
            src.sendFailure(Component.literal("[agent] config reload refused: " + error.getMessage()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "[agent] reloaded " + AgentConfig.path()
                        + "; previous tasks and bridge connections were revoked"), false);
        return 1;
    }


    /** Change one setting from chat and persist it. */
    private static int setConfig(CommandSourceStack src, String key, String value) {
        AgentConfig.SetResult result = AgentConfig.set(key, value);
        if (!result.ok) {
            src.sendFailure(Component.literal("[agent] " + result.message));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "[agent] " + key + " = " + value + " (" + result.message + ")"), false);
        return 1;
    }

}
