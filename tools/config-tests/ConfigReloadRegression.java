package dev.mcagent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/** Exercises the production configuration class using only synthetic temporary files. */
public final class ConfigReloadRegression {
    private static final String SYNTHETIC_SECRET = "synthetic-config-reload-do-not-echo";
    private static int checks;

    private record Settings(boolean enabled, String host, int port, String token,
                            boolean allowCommands, boolean allowWrite, int maxOutputChars,
                            int maxLogLines, boolean greetOnJoin, String skinModel,
                            String playerName, String skinPlayer, String skinFile, String agentName) {
        static Settings capture(AgentConfig config) {
            return new Settings(config.enabled, config.host, config.port, config.token,
                    config.allowCommands, config.allowWrite, config.maxOutputChars,
                    config.maxLogLines, config.greetOnJoin, config.skinModel,
                    config.playerName, config.skinPlayer, config.skinFile, config.agentName);
        }
    }

    public static void main(String[] args) throws Exception {
        AgentConfig initial = AgentConfig.get();
        Settings initialSettings = Settings.capture(initial);
        require(!AgentConfig.reload(), "reload before load must return false");
        retained(initial, initialSettings, "reload before load");
        require(AgentConfig.path().equals("(not loaded)"), "reload before load must not invent a path");
        passed("reload before load preserves the initial instance");

        Path directory = Files.createTempDirectory("mcagent-config-reload-").toAbsolutePath().normalize();
        Path file = directory.resolve("agentinminecraft.json");
        try {
            JsonObject starting = validConfig("initial", 38121, false);
            byte[] startingBytes = write(file, starting);
            AgentConfig.load(directory);
            require(Arrays.equals(startingBytes, Files.readAllBytes(file)), "loading an existing valid fixture must preserve its bytes");
            require(AgentConfig.get().token.equals(SYNTHETIC_SECRET + "-initial"), "load must read the synthetic token");
            passed("valid initial configuration preserves unknown fields and file bytes");

            AgentConfig old = AgentConfig.get();
            JsonObject changed = validConfig("updated", 38122, true);
            changed.addProperty("host", "localhost");
            changed.addProperty("enabled", false);
            changed.addProperty("allowCommands", false);
            changed.addProperty("greetOnJoin", false);
            changed.addProperty("maxOutputChars", 4321);
            changed.addProperty("maxLogLines", 81);
            byte[] changedBytes = write(file, changed);
            require(AgentConfig.reload(), "valid reload must return true");
            AgentConfig updated = AgentConfig.get();
            require(updated != old, "valid reload must publish a new instance");
            require(updated.token.equals(SYNTHETIC_SECRET + "-updated") && updated.port == 38122,
                    "valid reload must update token and port");
            require(updated.host.equals("localhost") && !updated.enabled && !updated.allowCommands
                    && updated.allowWrite && !updated.greetOnJoin
                    && updated.maxOutputChars == 4321 && updated.maxLogLines == 81,
                    "valid reload must apply all requested permission and limit fields");
            require(Arrays.equals(changedBytes, Files.readAllBytes(file)), "valid reload must not serialize the source file");
            passed("valid reload updates settings while preserving source bytes and unknown fields");

            rejected(file, "malformed JSON", "{\"token\":\"" + SYNTHETIC_SECRET + "\",\"allowWrite\":");
            rejected(file, "array root", "[]");
            rejected(file, "null root", "null");
            rejected(file, "non-object primitive root", "true");

            badField(file, "token", "true", "boolean token");
            badField(file, "token", "{}", "object token");
            badField(file, "host", "123", "numeric host");
            badField(file, "host", "[]", "array host");
            badField(file, "port", "\"38123\"", "string port");
            badField(file, "port", "[]", "array port");
            badField(file, "allowWrite", "\"true\"", "string write permission");
            badField(file, "allowCommands", "{}", "object command permission");
            badField(file, "enabled", "1", "numeric enabled flag");
            badField(file, "maxOutputChars", "\"limit\"", "string output limit");
            badField(file, "maxLogLines", "[]", "array log limit");
            badField(file, "skinFile", "{}", "late invalid field after changed credentials and permissions");

            badField(file, "token", "\"\"", "empty token");
            badField(file, "token", "\" \\t\\n \"", "blank token");
            JsonObject missingToken = validConfig("candidate", 38123, true);
            missingToken.remove("token");
            rejected(file, "missing token", missingToken.toString());
            badField(file, "host", "\"\"", "empty host");
            badField(file, "host", "\" \\t \"", "blank host");
            badField(file, "port", "0", "zero port");
            badField(file, "port", "-1", "negative port");
            badField(file, "port", "65536", "port above the TCP range");
            badField(file, "port", "38121.5", "fractional port");
            badField(file, "port", "4295005417", "overflowing port that would otherwise wrap to a valid value");

            AgentConfig beforeMissing = AgentConfig.get();
            Settings missingSettings = Settings.capture(beforeMissing);
            Files.delete(file);
            RuntimeException missingError = rejection("missing configuration");
            retained(beforeMissing, missingSettings, "missing configuration");
            require(!Files.exists(file), "missing configuration must not be silently recreated");
            sanitized(missingError, "missing configuration");
            passed("missing file is rejected without changing settings or recreating it");

            Files.createDirectory(file);
            RuntimeException directoryError = rejection("configuration path is a directory");
            retained(beforeMissing, missingSettings, "configuration path is a directory");
            require(Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS), "unreadable path must remain a directory");
            try (var children = Files.list(file)) {
                require(children.findAny().isEmpty(), "unreadable directory must not acquire a replacement configuration");
            }
            sanitized(directoryError, "configuration path is a directory");
            passed("unreadable directory is rejected without changing settings or the path");
            Files.delete(file);

            byte[] lockedBytes = write(file, validConfig("locked-candidate", 38124, true));
            boolean readsBlocked;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                readsBlocked = !canRead(file);
                if (readsBlocked) {
                    RuntimeException lockedError = rejection("unreadable locked file");
                    retained(beforeMissing, missingSettings, "unreadable locked file");
                    sanitized(lockedError, "unreadable locked file");
                }
            }
            require(Arrays.equals(lockedBytes, Files.readAllBytes(file)), "locked source file bytes must remain unchanged");
            if (readsBlocked) passed("unreadable locked file preserves the prior instance and source bytes");
            else System.out.println("SKIP unreadable-file lock check: this platform allows reads while locked");

            byte[] recoveredBytes = write(file, validConfig("recovered", 65535, false));
            require(AgentConfig.reload(), "valid reload after failures must succeed");
            require(AgentConfig.get() != beforeMissing && AgentConfig.get().port == 65535
                    && AgentConfig.get().token.equals(SYNTHETIC_SECRET + "-recovered")
                    && !AgentConfig.get().allowWrite, "recovery must publish the intended valid configuration");
            require(Arrays.equals(recoveredBytes, Files.readAllBytes(file)), "recovery must preserve file bytes");
            passed("a later valid reload recovers after parse, validation and IO failures");
            try (var children = Files.list(directory)) {
                require(children.toList().equals(List.of(file)), "reload must not create sidecar or temporary files");
            }
            passed("reload creates no sidecar or temporary files");
            System.out.println("CONFIG_RELOAD_CHECKS=" + checks);
        } finally {
            // Only these paths were created by this test. No user directory is traversed.
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static JsonObject validConfig(String suffix, int port, boolean allowWrite) {
        JsonObject config = new JsonObject();
        config.addProperty("enabled", true);
        config.addProperty("host", "127.0.0.1");
        config.addProperty("port", port);
        config.addProperty("token", SYNTHETIC_SECRET + "-" + suffix);
        config.addProperty("allowCommands", true);
        config.addProperty("allowWrite", allowWrite);
        config.addProperty("maxOutputChars", 1234);
        config.addProperty("maxLogLines", 27);
        config.addProperty("greetOnJoin", true);
        config.addProperty("agentName", "FixtureAgent");
        config.addProperty("playerName", "FixtureWorker");
        config.addProperty("skinPlayer", "FixtureWorker,Helper");
        config.addProperty("skinModel", "slim");
        config.addProperty("skinFile", "config/mcagent/synthetic-skin.png");
        config.add("unknownFutureField", JsonParser.parseString("{\"revision\":7,\"sentinel\":[true,\"preserve\"]}"));
        return config;
    }

    private static byte[] write(Path file, JsonObject config) throws IOException {
        byte[] bytes = (" \r\n" + config + "\n ").getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        return bytes;
    }

    private static void badField(Path file, String field, String value, String name) throws IOException {
        JsonObject config = validConfig("candidate", 38123, true);
        JsonElement replacement = JsonParser.parseString(value);
        config.add(field, replacement);
        rejected(file, name, config.toString());
    }

    private static void rejected(Path file, String name, String json) throws IOException {
        AgentConfig before = AgentConfig.get();
        Settings settings = Settings.capture(before);
        byte[] bytes = (" \r\n" + json + "\n ").getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        RuntimeException failure = rejection(name);
        retained(before, settings, name);
        require(Arrays.equals(bytes, Files.readAllBytes(file)), name + " must preserve the source bytes");
        sanitized(failure, name);
        passed(name + " rejected with settings and source bytes retained");
    }

    private static RuntimeException rejection(String name) {
        try { AgentConfig.reload(); }
        catch (RuntimeException failure) { return failure; }
        throw new AssertionError(name + " must reject reload");
    }

    private static void retained(AgentConfig before, Settings settings, String name) {
        require(AgentConfig.get() == before, name + " must retain the exact prior instance");
        require(Settings.capture(AgentConfig.get()).equals(settings), name + " must retain all prior field values");
    }

    private static void sanitized(RuntimeException failure, String name) {
        StringWriter rendered = new StringWriter();
        failure.printStackTrace(new PrintWriter(rendered));
        require(!rendered.toString().contains(SYNTHETIC_SECRET), name + " must not expose credential-bearing input");
        require(failure.getMessage() != null && failure.getMessage().length() <= 256,
                name + " must return a bounded error message");
    }

    private static boolean canRead(Path file) {
        try { Files.readAllBytes(file); return true; }
        catch (IOException expected) { return false; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void passed(String name) {
        checks++;
        System.out.println("PASS " + name);
    }
}
