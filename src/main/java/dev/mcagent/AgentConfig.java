package dev.mcagent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * On-disk configuration at {@code config/mcagent.json}.
 *
 * <p>The access token is generated on first launch and written back, so the
 * bridge is never open without authentication even if the user never edits the
 * file. Everything binds loopback-only unless the user deliberately changes
 * {@code host}.</p>
 */
public final class AgentConfig {
    public boolean enabled = true;
    public String host = "127.0.0.1";
    public int port = 38121;
    public String token = "";
    /** Allow mc_command / WS exec to run arbitrary commands. */
    public boolean allowCommands = true;
    /** Allow world-mutating tools (set_block, etc). */
    public boolean allowWrite = false;
    /** Command output is truncated to this many characters before returning. */
    public int maxOutputChars = 16000;
    /** Lines of latest.log a single mc_logs call may return. */
    public int maxLogLines = 500;
    /** Print a one-line "bridge ready" notice in chat when a player joins. */
    public boolean greetOnJoin = true;
    /** Skin model: auto (count holes), wide (4px arms) or slim (3px arms, Alex). */
    public String skinModel = "auto";

    /** The player name the agent enters the world as. Empty falls back to agentName. */
    public String playerName = "Deepseek";

    /** Which player wears the skin below; empty means agentName. */
    public String skinPlayer = "";

    /** Optional 64x64 skin png for the local player; empty disables the override. */
    public String skinFile = "";

    /** Display name the agent speaks under, rendered as {@code <name> message}. */
    public String agentName = "Deepseek";

    private static AgentConfig instance = new AgentConfig();
    private static Path file;

    /** Re-read the file without restarting the game. */
    public static synchronized boolean reload() {
        if (file == null) {
            return false;
        }
        load(file.getParent());
        return true;
    }

    /** Where the file lives, for the config command. */
    public static String path() {
        return file == null ? "(not loaded)" : file.toString();
    }

    /** Current values, one "key = value" line each. The token is masked. */
    public java.util.List<String> describeLines() {
        String shownToken = token == null || token.length() < 12
                ? "(unset)"
                : token.substring(0, 4) + "..." + token.substring(token.length() - 4);
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("file      = " + path());
        out.add("enabled   = " + enabled + "    mode = " + AgentMode.id());
        out.add("host      = " + host + "    port = " + port);
        out.add("token     = " + shownToken + "  (masked)");
        out.add("playerName = " + resolvedPlayerName());
        out.add("allowCommands = " + allowCommands + "    allowWrite = " + allowWrite);
        out.add("skinFile  = " + (skinFile == null || skinFile.isBlank() ? "(none)" : skinFile)
                + "    skinModel = " + skinModel);
        out.add("skinPlayer = " + (skinPlayer == null || skinPlayer.isBlank()
                ? "(follows player)" : skinPlayer));
        out.add("greetOnJoin = " + greetOnJoin
                + "    maxOutputChars = " + maxOutputChars + "    maxLogLines = " + maxLogLines);
        return out;
    }

    private static final java.util.List<String> SETTABLE_BOOL =
            java.util.List.of("allowCommands", "allowWrite", "greetOnJoin", "enabled");
    private static final java.util.List<String> SETTABLE_INT =
            java.util.List.of("maxOutputChars", "maxLogLines", "port");
    private static final java.util.List<String> SETTABLE_TEXT =
            java.util.List.of("host", "playerName", "skinFile", "skinPlayer", "skinModel");

    /** Keys this command accepts. Anything else is a file-only or startup-only setting. */
    public static final java.util.List<String> SETTABLE = java.util.List.of(
            "allowCommands", "allowWrite", "playerName",
            "skinFile", "skinModel", "skinPlayer", "greetOnJoin",
            "maxOutputChars", "maxLogLines",
            "host", "port", "enabled");

    /** Outcome of {@link #set}: applied now, saved for the next launch, or refused. */
    public static final class SetResult {
        public final boolean ok;
        public final boolean needsRestart;
        public final String message;

        SetResult(boolean ok, boolean needsRestart, String message) {
            this.ok = ok;
            this.needsRestart = needsRestart;
            this.message = message;
        }

        static SetResult now() { return new SetResult(true, false, "saved"); }
        static SetResult restart() { return new SetResult(true, true, "saved - takes effect after a restart"); }
        static SetResult error(String why) { return new SetResult(false, false, why); }
    }

    /**
     * Change one setting from in-game and persist it.
     *
     * @return null on success, otherwise the reason it was refused.
     */
    public static synchronized SetResult set(String key, String value) {
        AgentConfig c = instance;
        String v = value == null ? "" : value.trim();

        // Accept the obvious short forms; the canonical names are what the listing prints.
        key = switch (key) {
            case "player", "name", "agentName" -> "playerName";
            case "skin" -> "skinFile";
            case "write" -> "allowWrite";
            case "commands" -> "allowCommands";
            default -> key;
        };

        // A setting that silently becomes "" is indistinguishable from a bug: this is
        // exactly how the skin stopped loading (skinFile was blanked from chat, after
        // which the skin loader returned null without a word).
        if (v.isEmpty() && !key.equals("skinPlayer")) {
            return SetResult.error(key + " cannot be empty - pass a value, or edit the file");
        }

        switch (key) {
            // Binding settings: written to the file but only picked up on the next launch,
            // because the socket is already bound (and the bridge already started or not).
            case "host" -> {
                if (v.isBlank()) {
                    return SetResult.error("host cannot be empty");
                }
                boolean loopback = v.equals("127.0.0.1") || v.equalsIgnoreCase("localhost");
                c.host = v;
                writeKey("host");
                return loopback
                        ? SetResult.restart()
                        : new SetResult(true, true,
                                "saved - WARNING: binding to " + v + " exposes the bridge beyond this "
                                        + "machine; the token is the only thing left protecting it");
            }
            case "port" -> {
                int port;
                try {
                    port = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    return SetResult.error("port must be a number");
                }
                if (port < 1 || port > 65535) {
                    return SetResult.error("port must be 1-65535");
                }
                c.port = port;
                writeKey("port");
                return SetResult.restart();
            }
            case "enabled" -> {
                c.enabled = parseBool(v, c.enabled);
                writeKey("enabled");
                return SetResult.restart();
            }
            case "token" -> {
                return SetResult.error("token is a credential: edit the file and restart "
                        + "(echoing it in chat would put it in screenshots and logs)");
            }
            case "allowCommands" -> c.allowCommands = parseBool(v, c.allowCommands);
            case "allowWrite" -> c.allowWrite = parseBool(v, c.allowWrite);
            case "greetOnJoin" -> c.greetOnJoin = parseBool(v, c.greetOnJoin);
            case "playerName" -> c.playerName = v;
            case "skinFile" -> c.skinFile = v;
            case "skinModel" -> {
                String m = v.toLowerCase(java.util.Locale.ROOT);
                if (m.isEmpty()) {
                    return SetResult.error("skinModel must be auto, wide or slim");
                }
                if (!m.equals("auto") && !m.equals("wide") && !m.equals("slim")) {
                    return SetResult.error("skinModel must be auto, wide or slim");
                }
                c.skinModel = m;
            }
            case "skinPlayer" -> c.skinPlayer = v;
            case "maxOutputChars" -> c.maxOutputChars = parseInt(v, c.maxOutputChars);
            case "maxLogLines" -> c.maxLogLines = parseInt(v, c.maxLogLines);
            default -> {
                String hint = "";
                for (String candidate : SETTABLE) {
                    if (candidate.toLowerCase(java.util.Locale.ROOT)
                            .startsWith(key.toLowerCase(java.util.Locale.ROOT))) {
                        hint = " (did you mean '" + candidate + "'?)";
                        break;
                    }
                }
                return SetResult.error("unknown setting '" + key + "'" + hint
                        + " - run /agent config for the list");
            }
        }
        writeKey(key);
        return SetResult.now();
    }


    private static boolean parseBool(String v, boolean fallback) {
        if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on")) return true;
        if (v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("off")) return false;
        return fallback;
    }

    private static int parseInt(String v, int fallback) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }


    /** The configured player name, falling back to the display name. */
    public String resolvedPlayerName() {
        return playerName == null || playerName.isBlank() ? agentName : playerName.trim();
    }

    public static AgentConfig get() {
        return instance;
    }

    public static synchronized void load(Path configDir) {
        file = configDir.resolve("mcagent.json");
        AgentConfig cfg = new AgentConfig();
        if (Files.isRegularFile(file)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = Json.b(o, "enabled", cfg.enabled);
                cfg.host = Json.str(o, "host", cfg.host);
                cfg.port = Json.i(o, "port", cfg.port);
                cfg.token = Json.str(o, "token", cfg.token);
                cfg.allowCommands = Json.b(o, "allowCommands", cfg.allowCommands);
                cfg.allowWrite = Json.b(o, "allowWrite", cfg.allowWrite);
                cfg.maxOutputChars = Json.i(o, "maxOutputChars", cfg.maxOutputChars);
                cfg.maxLogLines = Json.i(o, "maxLogLines", cfg.maxLogLines);
                cfg.greetOnJoin = Json.b(o, "greetOnJoin", cfg.greetOnJoin);
                cfg.agentName = Json.str(o, "agentName", cfg.agentName);
                cfg.playerName = Json.str(o, "playerName", cfg.playerName);
                cfg.skinPlayer = Json.str(o, "skinPlayer", cfg.skinPlayer);
                cfg.skinModel = Json.str(o, "skinModel", cfg.skinModel);
                cfg.skinFile = Json.str(o, "skinFile", cfg.skinFile);
                // A blank persisted value would silently disable the feature it feeds.
                if (cfg.skinFile == null || cfg.skinFile.isBlank()) {
                    cfg.skinFile = "config/mcagent/skin.png";
                }
                if (cfg.skinModel == null || cfg.skinModel.isBlank()) {
                    cfg.skinModel = "auto";
                }
                if (cfg.playerName == null || cfg.playerName.isBlank()) {
                    cfg.playerName = cfg.agentName;
                }
            } catch (Exception e) {
                McAgent.LOG.warn("[mcagent] config unreadable, falling back to defaults", e);
            }
        }
        boolean needWrite = cfg.token == null || cfg.token.isBlank();
        if (needWrite) {
            cfg.token = newToken();
        }
        instance = cfg;
        if (needWrite || !Files.isRegularFile(file)) {
            save();
        }
    }

    /**
     * Write every known setting, merging into whatever is already on disk.
     *
     * <p>Merging matters: a full overwrite from memory silently drops any key this build
     * does not know about (a newer build's setting, a hand-edited one), and an in-memory
     * blank then erases a good value on disk. Both of those have already happened once.</p>
     */
    public static synchronized void save() {
        if (file == null) {
            return;
        }
        JsonObject o = readDisk();
        for (String key : SETTABLE_BOOL) {
            putKey(o, key);
        }
        for (String key : SETTABLE_INT) {
            putKey(o, key);
        }
        for (String key : SETTABLE_TEXT) {
            putKey(o, key);
        }
        o.addProperty("token", instance.token);
        o.addProperty("enabled", instance.enabled);
        write(o);
    }

    /** Persist exactly one key - what {@code /agent config set} must do. */
    private static void writeKey(String key) {
        if (file == null) {
            return;
        }
        JsonObject o = readDisk();
        putKey(o, key);
        write(o);
    }

    private static JsonObject readDisk() {
        if (file != null && Files.isRegularFile(file)) {
            try {
                return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (Exception e) {
                McAgent.LOG.warn("[mcagent] existing config is unreadable, rewriting it", e);
            }
        }
        return new JsonObject();
    }

    private static void write(JsonObject o) {
        try {
            Files.writeString(file,
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            McAgent.LOG.warn("[mcagent] could not write the config file", e);
        }
    }

    /** Copy one setting out of the live instance into the JSON object. */
    private static void putKey(JsonObject o, String key) {
        AgentConfig c = instance;
        switch (key) {
            case "enabled" -> o.addProperty(key, c.enabled);
            case "host" -> o.addProperty(key, c.host);
            case "port" -> o.addProperty(key, c.port);
            case "token" -> o.addProperty(key, c.token);
            case "allowCommands" -> o.addProperty(key, c.allowCommands);
            case "allowWrite" -> o.addProperty(key, c.allowWrite);
            case "greetOnJoin" -> o.addProperty(key, c.greetOnJoin);
            case "maxOutputChars" -> o.addProperty(key, c.maxOutputChars);
            case "maxLogLines" -> o.addProperty(key, c.maxLogLines);
            case "playerName" -> o.addProperty(key, c.playerName);
            case "skinFile" -> o.addProperty(key, c.skinFile);
            case "skinPlayer" -> o.addProperty(key, c.skinPlayer);
            case "skinModel" -> o.addProperty(key, c.skinModel);
            default -> { }
        }
    }


    private static JsonObject toJson() {
        AgentConfig c = instance;
        JsonObject o = Json.obj();
        o.addProperty("enabled", c.enabled);
        o.addProperty("host", c.host);
        o.addProperty("port", c.port);
        o.addProperty("token", c.token);
        o.addProperty("allowCommands", c.allowCommands);
        o.addProperty("allowWrite", c.allowWrite);
        o.addProperty("maxOutputChars", c.maxOutputChars);
        o.addProperty("maxLogLines", c.maxLogLines);
        o.addProperty("greetOnJoin", c.greetOnJoin);
        o.addProperty("agentName", c.agentName);
        return o;
    }

    private static String newToken() {
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
