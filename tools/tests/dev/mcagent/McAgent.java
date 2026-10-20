package dev.mcagent;

/** The transport tests do not start Minecraft or bind the bridge port. */
public final class McAgent {
    public static final String VERSION = "test";
    public static final WsHub WS = new WsHub();
    public static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("transport-test");
}
