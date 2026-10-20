package dev.mcagent;

public final class AgentConfig {
    public boolean allowCommands = true;
    private static final AgentConfig INSTANCE = new AgentConfig();
    public static AgentConfig get() { return INSTANCE; }
}
