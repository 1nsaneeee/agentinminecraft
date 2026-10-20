package dev.mcagent;

/**
 * What the agent is currently doing, as told by the in-game commands.
 *
 * <p>This lives in the mod rather than the harness plugin because the restriction it
 * carries has to be enforced where the tools are: the agent calls MCP directly, so a
 * rule kept only in the chat plugin would be advisory, not binding.</p>
 *
 * <p>{@code WORK} is the default. {@code CHAT} still allows the agent's own body to act -
 * walk, break, place, use, attack - but refuses tools that edit the world directly, so
 * a conversation cannot reshape the map by accident.</p>
 */
public final class AgentMode {

    public enum Mode { WORK, CHAT }

    private static volatile Mode current = Mode.WORK;

    private AgentMode() {}

    public static Mode get() {
        return current;
    }

    public static void set(Mode mode) {
        current = mode == null ? Mode.WORK : mode;
    }

    public static boolean isChat() {
        return current == Mode.CHAT;
    }

    public static String id() {
        return current.name().toLowerCase(java.util.Locale.ROOT);
    }
}
