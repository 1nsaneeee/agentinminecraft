package dev.mcagent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Thin, null-tolerant helpers over Gson so call sites stay readable. */
public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {}

    public static JsonObject parseObject(String s) {
        JsonElement e = JsonParser.parseString(s);
        if (!e.isJsonObject()) {
            throw new IllegalArgumentException("expected JSON object");
        }
        return e.getAsJsonObject();
    }

    public static JsonObject obj() {
        return new JsonObject();
    }

    public static JsonArray arr() {
        return new JsonArray();
    }

    public static String str(JsonObject o, String key, String def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        return e.getAsString();
    }

    public static int i(JsonObject o, String key, int def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    public static long l(JsonObject o, String key, long def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsLong();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    public static double d(JsonObject o, String key, double def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    public static boolean b(JsonObject o, String key, boolean def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    public static JsonObject sub(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray subArr(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }
}
