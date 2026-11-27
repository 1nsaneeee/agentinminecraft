package dev.mcagent;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

/** Exercises real Brigadier parsing without loading Minecraft or mutating a world. */
public final class CommandKnowledgeRegression {
    private static int executed;
    private static int checks;

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }

    public static void main(String[] args) {
        var dispatcher = new CommandDispatcher<Integer>();
        dispatcher.register(LiteralArgumentBuilder.<Integer>literal("grant")
                .requires(level -> level >= 2)
                .then(RequiredArgumentBuilder.<Integer, Integer>argument("count", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> ++executed)));
        dispatcher.register(LiteralArgumentBuilder.<Integer>literal("mod_command").executes(ctx -> ++executed));
        var roots = CommandKnowledge.inspect(dispatcher, 2, "");
        check(roots.getAsJsonArray("usage").toString().contains("mod_command"), "discovers custom commands");
        check(!CommandKnowledge.inspect(dispatcher, 0, "").getAsJsonArray("usage").toString().contains("grant"), "usage respects source permissions");
        check(CommandKnowledge.inspect(dispatcher, 2, "/grant 10").get("syntaxComplete").getAsBoolean(), "valid command including leading slash");
        check(!CommandKnowledge.inspect(dispatcher, 2, "grant").get("syntaxComplete").getAsBoolean(), "incomplete command is not executable");
        var invalid = CommandKnowledge.inspect(dispatcher, 2, "grant 100");
        check(!invalid.get("syntaxComplete").getAsBoolean() && !invalid.getAsJsonArray("errors").isEmpty(), "constraint failure reports parser evidence");
        check(!CommandKnowledge.inspect(dispatcher, 2, "grant 10 extra").get("syntaxComplete").getAsBoolean(), "trailing garbage rejected");
        check(!CommandKnowledge.inspect(dispatcher, 0, "grant 10").get("syntaxComplete").getAsBoolean(), "hidden command cannot parse as permitted");
        check(!CommandKnowledge.inspect(dispatcher, 2, "missing").get("syntaxComplete").getAsBoolean(), "unknown command rejected");
        check(CommandKnowledge.inspect(dispatcher, 2, "grant ").getAsJsonArray("usage").toString().contains("count"), "incomplete command gives next argument usage");
        try {
            CommandKnowledge.inspect(dispatcher, 2, "x".repeat(4097));
            throw new AssertionError("oversize input accepted");
        } catch (IllegalArgumentException expected) {
            check(true, "bounded input");
        }
        for (int i = 0; i < 100; i++) dispatcher.register(LiteralArgumentBuilder.<Integer>literal("extra" + i));
        var bounded = CommandKnowledge.inspect(dispatcher, 2, "");
        check(bounded.get("usageTruncated").getAsBoolean() && bounded.getAsJsonArray("usage").size() == 80, "bounded root discovery");
        check(executed == 0, "inspection never executes handlers");
        System.out.println("KNOWLEDGE_CHECKS=" + checks);
    }
}
