package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.neoforged.fml.ModList;

/** Version-aware, read-only discovery for external MCP clients. */
public final class KnowledgeTools {
    private KnowledgeTools() {}

    private interface Read { JsonObject call(JsonObject args); }

    private static void add(ToolRegistry registry, String name, String description, JsonObject schema, Read read) {
        registry.add(new McpTool() {
            public String name() { return name; }
            public String description() { return description; }
            public JsonObject inputSchema() { return schema; }
            public McpResult call(JsonObject args) {
                return GameBridge.onServer(() -> McpResult.text(Json.GSON.toJson(read.call(args))), 10000);
            }
        });
    }

    public static void register(ToolRegistry registry) {
        JsonObject environment = ToolRegistry.schema();
        ToolRegistry.strProp(environment, "filter", "Optional case-insensitive substring of mod id or name.");
        add(registry, "mc_knowledge", "Discover the actual Minecraft version, installed mods/versions and enabled "
                + "data packs before researching mechanics. Recipes, tags and quests have dedicated read tools. "
                + "Tools are entry points, not the limit of possible solutions; use version-matched documentation "
                + "and mc_command_inspect when a goal needs a command. Does not execute commands.", environment, args -> {
            var server = GameBridge.server();
            JsonObject out = Json.obj();
            out.addProperty("minecraft", server.getServerVersion());
            out.addProperty("bridgeVersion", McAgent.VERSION);
            out.addProperty("allowCommands", AgentConfig.get().allowCommands);
            out.addProperty("allowWrite", AgentConfig.get().allowWrite);
            out.add("agentControl", AgentControl.snapshot());
            String filter = Json.str(args, "filter", "").toLowerCase(java.util.Locale.ROOT);
            JsonArray mods = Json.arr();
            for (var mod : ModList.get().getMods()) {
                if (!(mod.getModId() + " " + mod.getDisplayName()).toLowerCase(java.util.Locale.ROOT).contains(filter)) continue;
                JsonObject item = Json.obj();
                item.addProperty("id", mod.getModId());
                item.addProperty("name", mod.getDisplayName());
                item.addProperty("version", mod.getVersion().toString());
                mods.add(item);
            }
            out.add("mods", mods);
            JsonArray packs = Json.arr();
            for (String pack : server.getPackRepository().getSelectedIds()) packs.add(pack);
            out.add("enabledDataPacks", packs);
            out.addProperty("knowledgePolicy", "Use live recipes/tags for this pack; documentation must match versions. "
                    + "Do not infer configuration values or mechanics from a mod name alone.");
            return out;
        });

        JsonObject inspect = ToolRegistry.schema();
        ToolRegistry.strProp(inspect, "command", "Command to parse without executing. Empty lists available root usages.");
        ToolRegistry.strProp(inspect, "player", "Player name; omitted uses the first online player, as mc_command does.");
        ToolRegistry.intProp(inspect, "permission", "0-4; same default as mc_command. This only selects a grammar view, never grants execution.");
        add(registry, "mc_command_inspect", "Read live command usages or parse candidate syntax WITHOUT executing. "
                + "Use to discover vanilla/mod commands and diagnose version-specific syntax before mc_command. "
                + "syntaxComplete does not prove execution will succeed; verify actual state afterward.", inspect, args -> {
            int permission = Json.i(args, "permission", -1);
            if (permission < -1 || permission > 4) throw new IllegalArgumentException("permission must be 0-4 or omitted");
            var player = GameBridge.player(Json.str(args, "player", null));
            int effective = permission < 0 ? Math.max(GameBridge.server().getProfilePermissions(player.getGameProfile()), 2) : permission;
            var source = player.createCommandSourceStack().withPermission(effective);
            JsonObject out = CommandKnowledge.inspect(GameBridge.server().getCommands().getDispatcher(), source,
                    Json.str(args, "command", ""));
            out.addProperty("player", player.getGameProfile().getName());
            out.addProperty("permissionView", effective);
            out.addProperty("executionAllowedByBridge", !AgentMode.isChat() && AgentConfig.get().allowCommands && AgentControl.active());
            return out;
        });

        JsonObject item = ToolRegistry.schema("slot");
        ToolRegistry.intProp(item, "slot", "Main inventory slot 0-35 (including hotbar 0-8).");
        ToolRegistry.strProp(item, "player", "Whose item to inspect; omitted uses the first online player.");
        add(registry, "mc_item_inspect", "Read an actual inventory stack including saved item components, such as "
                + "enchantments. Use after giving or modifying an item; a successful command alone is not verification.", item, args -> {
            int slot = Json.i(args, "slot", -1);
            if (slot < 0 || slot > 35) throw new IllegalArgumentException("slot must be 0-35");
            var player = GameBridge.player(Json.str(args, "player", null));
            var stack = player.getInventory().items.get(slot);
            JsonObject out = Json.obj();
            out.addProperty("player", player.getGameProfile().getName());
            out.addProperty("slot", slot);
            out.addProperty("empty", stack.isEmpty());
            out.addProperty("count", stack.getCount());
            if (!stack.isEmpty()) {
                out.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                String saved = stack.save(player.registryAccess()).toString();
                int limit = 16000;
                out.addProperty("savedStackSnbt", saved.length() > limit ? saved.substring(0, limit) : saved);
                out.addProperty("truncated", saved.length() > limit);
            }
            return out;
        });
    }
}
