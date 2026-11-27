package dev.mcagent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;

/** Validates the flat scalar schemas emitted by ToolRegistry; never coerces model values. */
public final class ToolArguments {
    private ToolArguments() {}

    public static String error(JsonObject schema, JsonObject args) {
        if (args == null) return "arguments must be an object";
        JsonObject properties = schema.getAsJsonObject("properties");
        if (properties == null) throw new IllegalArgumentException("tool schema has no properties");
        if (schema.has("required")) {
            for (JsonElement required : schema.getAsJsonArray("required")) {
                if (!args.has(required.getAsString())) return "missing argument: " + required.getAsString();
            }
        }
        for (var entry : args.entrySet()) {
            String name = entry.getKey();
            if (!properties.has(name)) return "unknown argument: " + name;
            JsonObject property = properties.getAsJsonObject(name);
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive()) return name + " must be " + property.get("type").getAsString();
            var primitive = value.getAsJsonPrimitive();
            String type = property.get("type").getAsString();
            switch (type) {
                case "string":
                    if (!primitive.isString()) return name + " must be string";
                    break;
                case "boolean":
                    if (!primitive.isBoolean()) return name + " must be boolean";
                    break;
                case "integer":
                case "number":
                    if (!primitive.isNumber()) return name + " must be " + type;
                    BigDecimal number;
                    try { number = primitive.getAsBigDecimal(); }
                    catch (NumberFormatException e) { return name + " must be finite"; }
                    if (!Double.isFinite(number.doubleValue())) return name + " must be finite";
                    if (type.equals("integer") && number.stripTrailingZeros().scale() > 0)
                        return name + " must be integer";
                    if (property.has("minimum") && number.compareTo(property.get("minimum").getAsBigDecimal()) < 0)
                        return name + " is below minimum";
                    if (property.has("maximum") && number.compareTo(property.get("maximum").getAsBigDecimal()) > 0)
                        return name + " is above maximum";
                    break;
                default:
                    throw new IllegalArgumentException("unsupported tool argument type: " + type);
            }
        }
        return null;
    }
}
