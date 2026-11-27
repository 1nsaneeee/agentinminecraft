package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.tree.CommandNode;

/** Reads the live command grammar; never dispatches a command or a suggestion provider. */
public final class CommandKnowledge {
    private static final int LIMIT = 80;

    private CommandKnowledge() {}

    public static <S> JsonObject inspect(CommandDispatcher<S> dispatcher, S source, String input) {
        if (input.length() > 4096) throw new IllegalArgumentException("command exceeds 4096 characters");
        String command = input.stripLeading();
        if (command.startsWith("/")) command = command.substring(1);
        JsonObject out = Json.obj();
        out.addProperty("executed", false);
        out.addProperty("scope", "syntax only; permissions, targets and runtime preconditions still apply");
        JsonArray usage = Json.arr();
        if (command.isBlank()) {
            appendUsage(dispatcher, dispatcher.getRoot(), source, usage, out);
            out.add("usage", usage);
            return out;
        }
        var parsed = dispatcher.parse(command, source);
        CommandContextBuilder<S> context = parsed.getContext();
        while (context.getChild() != null) context = context.getChild();
        boolean consumed = !parsed.getReader().canRead();
        boolean executable = context.getCommand() != null;
        out.addProperty("syntaxComplete", consumed && executable);
        out.addProperty("cursor", parsed.getReader().getCursor());
        out.addProperty("remaining", parsed.getReader().getRemaining());
        JsonArray errors = Json.arr();
        if (!consumed || !executable) {
            for (var error : parsed.getExceptions().values()) {
                if (errors.size() >= 8) break;
                errors.add(error.getMessage());
            }
            if (errors.isEmpty()) errors.add(consumed ? "incomplete command" : "unknown argument or insufficient permission");
        }
        out.add("errors", errors);
        var nodes = context.getNodes();
        CommandNode<S> node = nodes.isEmpty() ? context.getRootNode() : nodes.get(nodes.size() - 1).getNode();
        out.addProperty("usageRelativeTo", node.getName());
        appendUsage(dispatcher, node, source, usage, out);
        out.add("usage", usage);
        return out;
    }

    private static <S> void appendUsage(CommandDispatcher<S> dispatcher, CommandNode<S> node,
            S source, JsonArray usage, JsonObject out) {
        var entries = dispatcher.getSmartUsage(node, source).values();
        for (String entry : entries) {
            if (usage.size() == LIMIT) break;
            usage.add(entry);
        }
        out.addProperty("usageTruncated", entries.size() > LIMIT);
    }
}
