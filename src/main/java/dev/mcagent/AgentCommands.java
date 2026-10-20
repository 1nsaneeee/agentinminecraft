package dev.mcagent;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * In-game control surface for the agent.
 *
 * <p>The game does not act on these commands itself: it turns them into
 * {@code agent_cmd} frames on the chat topic and lets the harness decide what they
 * mean. That keeps session semantics on the side that owns sessions, and it means a
 * command sent while no agent is attached gets an honest refusal instead of silently
 * doing nothing.</p>
 *
 * <p>{@code /agent ...} is the canonical form. The bare {@code /work}, {@code /chat},
 * {@code /new} and {@code /session} are registered too, but only when no other mod has
 * already claimed the name - a silent clash with someone else's command would be far
 * worse than a slightly longer command.</p>
 */
public final class AgentCommands {

    private AgentCommands() {}

    private static int send(CommandSourceStack src, String cmd, String arg) {
        if (!AgentConfig.get().allowCommands) {
            src.sendFailure(Component.literal("[agent] 命令已被配置禁用 (allowCommands=false)"));
            return 0;
        }
        if (McAgent.WS.size() == 0) {
            src.sendFailure(Component.literal("[agent] 没有 agent 在听（WS 客户端 0 个）"));
            return 0;
        }
        JsonObject frame = Json.obj();
        frame.addProperty("type", "agent_cmd");
        frame.addProperty("cmd", cmd);
        if (arg != null) {
            frame.addProperty("arg", arg);
        }
        // The mod owns the mode so it can enforce it; the plugin listens on the same
        // frame and keeps its own copy for the reply style.
        if ("work".equals(cmd)) {
            AgentMode.set(AgentMode.Mode.WORK);
        } else if ("chat".equals(cmd)) {
            AgentMode.set(AgentMode.Mode.CHAT);
        }
        frame.addProperty("mode", AgentMode.id());
        frame.addProperty("by", src.getTextName());
        McAgent.WS.broadcast(WsHub.TOPIC_CHAT, frame);
        src.sendSuccess(() -> Component.literal("[agent] " + cmd + (arg == null ? "" : " " + arg)), false);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> sessionBranch() {
        return Commands.literal("session")
                .executes(ctx -> send(ctx.getSource(), "session", null))
                .then(Commands.argument("which", StringArgumentType.greedyString())
                        .executes(ctx -> send(ctx.getSource(), "session",
                                StringArgumentType.getString(ctx, "which"))));
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        d.register(Commands.literal("agent")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("work").executes(ctx -> send(ctx.getSource(), "work", null)))
                .then(Commands.literal("chat").executes(ctx -> send(ctx.getSource(), "chat", null)))
                .then(Commands.literal("new").executes(ctx -> send(ctx.getSource(), "new", null)))
                .then(sessionBranch())
                .then(Commands.literal("config")
                        .executes(ctx -> showConfig(ctx.getSource()))
                        .then(Commands.literal("reload").executes(ctx -> reloadConfig(ctx.getSource())))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> setConfig(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "key"),
                                                        StringArgumentType.getString(ctx, "value"))))))));

        alias(d, "work", "work");
        alias(d, "chat", "chat");
        alias(d, "new", "new");
        alias(d, "session", "session");
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

    /** Re-read config/mcagent.json without restarting the game. */
    private static int reloadConfig(CommandSourceStack src) {
        if (!AgentConfig.reload()) {
            src.sendFailure(Component.literal("[agent] config file was never loaded"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(
                "[agent] reloaded " + AgentConfig.path() + " (mode = " + AgentMode.id() + ")"), false);
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

    private static void alias(CommandDispatcher<CommandSourceStack> d, String name, String cmd) {
        if (d.getRoot().getChild(name) != null) {
            McAgent.LOG.info("[mcagent] /{} is already claimed by another mod; alias skipped", name);
            return;
        }
        LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(name)
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> send(ctx.getSource(), cmd, null));
        if ("session".equals(cmd)) {
            b.then(Commands.argument("which", StringArgumentType.greedyString())
                    .executes(ctx -> send(ctx.getSource(), "session",
                            StringArgumentType.getString(ctx, "which"))));
        }
        d.register(b);
    }
}
