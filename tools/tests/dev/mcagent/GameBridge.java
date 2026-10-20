package dev.mcagent;

/** The world boundary is substituted so even a failing test cannot edit a save. */
public final class GameBridge {
    public static com.google.gson.JsonObject snapshot() { return Json.obj(); }
    public static void say(String text) {}
    public static void sayComponent(com.google.gson.JsonObject json) {}
    public static String runCommand(String command, String player, int permission) { return "executed"; }
}
