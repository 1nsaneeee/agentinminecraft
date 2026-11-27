package dev.mcagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tells the harness which instance is running right now.
 *
 * <p>The harness plugin needs a config file to read the port and token from. Until now
 * that path was hard-coded into the profile patch, which meant it pointed at exactly one
 * instance: start a second one and the plugin silently talked to nothing. Sharing one
 * token across instances would have "fixed" it by weakening the thing the token is for.</p>
 *
 * <p>So the running game advertises itself instead. On server start it writes
 * {@code ~/.mcagent/active.json} with its own config path; on server stop it removes the
 * file. The plugin reads that pointer and follows, with the hard-coded path downgraded to
 * an optional override.</p>
 *
 * <p>The file holds a path, never a credential - the plugin still reads the token from
 * the config itself.</p>
 */
public final class AgentDiscovery {

    private AgentDiscovery() {}

    private static Path pointer() {
        return Path.of(System.getProperty("user.home"), ".mcagent", "active.json");
    }

    /** Called when a world is loaded: advertise this instance's config. */
    public static void publish() {
        try {
            Path target = pointer();
            Files.createDirectories(target.getParent());
            String json = "{\n"
                    + "  \"configPath\": " + quote(AgentConfig.path()) + ",\n"
                    + "  \"port\": " + AgentConfig.get().port + ",\n"
                    + "  \"version\": " + quote(McAgent.VERSION) + ",\n"
                    + "  \"updatedAt\": " + System.currentTimeMillis() + "\n"
                    + "}\n";
            Files.writeString(target, json, StandardCharsets.UTF_8);
            McAgent.LOG.info("[mcagent] advertised this instance at {}", target);
        } catch (java.io.IOException | RuntimeException t) {
            // Discovery is a convenience; failing it must never stop a world from loading.
            McAgent.LOG.warn("[mcagent] could not write the discovery pointer", t);
        }
    }

    /** Called when the server stops: withdraw the pointer so nothing follows a dead instance. */
    public static void withdraw() {
        try {
            Files.deleteIfExists(pointer());
            McAgent.LOG.info("[mcagent] withdrew the discovery pointer");
        } catch (java.io.IOException | RuntimeException t) {
            McAgent.LOG.warn("[mcagent] could not remove the discovery pointer", t);
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
