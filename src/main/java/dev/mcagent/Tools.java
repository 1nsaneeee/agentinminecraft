package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcagent.client.ClientApi;
import dev.mcagent.fakeplayer.AgentActions;
import dev.mcagent.fakeplayer.AgentMovement;
import dev.mcagent.fakeplayer.FakePlayers;
import dev.mcagent.util.Dist2;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** All MCP tools. Read-only by default; mutating or privileged actions are gated by config. */
public final class Tools {

    private interface Body {
        McpResult call(JsonObject args) throws Exception;
    }

    private Tools() {}

    public static void registerAll(ToolRegistry registry) {
        registerStatus(registry);
        registerCommand(registry);
        registerChat(registry);
        registerWorld(registry);
        registerPlayers(registry);
        registerAgentPlayers(registry);
        registerMovement(registry);
        registerActions(registry);
        registerDiag(registry);
        registerEditing(registry);
        registerWatches(registry);
        registerRecipes(registry);
        registerQuests(registry);
    }

    private static void reg(ToolRegistry registry, String name, String desc, JsonObject schema, Body body) {
        registry.add(new McpTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return desc;
            }

            @Override
            public JsonObject inputSchema() {
                return schema;
            }

            @Override
            public McpResult call(JsonObject args) throws Exception {
                return body.call(args);
            }
        });
    }

    // ------------------------------------------------------------------ status

    private static void registerStatus(ToolRegistry registry) {
        reg(registry, "mc_status",
                "Live state of the game: whether a server is running, tick, TPS estimate, world, "
                        + "and every online player with position/dimension/health. Also client window info when available.",
                ToolRegistry.schema(),
                args -> {
                    JsonObject snap = GameBridge.snapshot();
                    ClientApi client = ClientSupport.get();
                    if (client != null) {
                        try {
                            snap.add("client", client.clientInfo());
                        } catch (Throwable t) {
                            snap.addProperty("clientError", t.toString());
                        }
                    }
                    JsonObject bridge = Json.obj();
                    bridge.addProperty("mod", McAgent.VERSION);
                    bridge.addProperty("port", McAgent.port());
                    bridge.addProperty("dist", Dist2.string());
                    bridge.addProperty("wsClients", McAgent.WS.size());
                    bridge.addProperty("allowCommands", AgentConfig.get().allowCommands);
                    bridge.addProperty("allowWrite", AgentConfig.get().allowWrite);
                    bridge.addProperty("mode", AgentMode.id());
                    snap.add("bridge", bridge);
                    return McpResult.text(Json.GSON.toJson(snap));
                });
    }

    // ----------------------------------------------------------------- command

    private static void registerCommand(ToolRegistry registry) {
        JsonObject schema = ToolRegistry.schema("command");
        ToolRegistry.strProp(schema, "command", "Command to run, with or without a leading slash.");
        ToolRegistry.strProp(schema, "player", "Run as this player name; defaults to the first online player.");
        ToolRegistry.intProp(schema, "permission",
                "Permission level 0-4. Defaults to max(player level, 2); 4 unlocks operator-only commands.");

        reg(registry, "mc_command",
                "Run a Minecraft command on the integrated server and return everything it printed "
                        + "(works for /data, /list, /setblock, /give, custom mod commands...).",
                schema,
                args -> {
                    if (AgentMode.isChat()) {
                        return McpResult.error("chat mode does not edit the world directly - "
                                + "use the agent player's own hands, or /agent work first");
                    }
                    if (!AgentConfig.get().allowCommands) {
                        return McpResult.error("commands are disabled by config (allowCommands=false)");
                    }
                    String command = Json.str(args, "command", "").trim();
                    if (command.isEmpty()) return McpResult.error("command is required");
                    int permission = Json.i(args, "permission", -1);
                    String out = GameBridge.runCommand(command, Json.str(args, "player", null), permission);
                    return McpResult.text(out.isEmpty() ? "(no output)" : out);
                });
    }

    // -------------------------------------------------------------------- chat

    private static void registerChat(ToolRegistry registry) {
        JsonObject say = ToolRegistry.schema("text");
        ToolRegistry.strProp(say, "text", "Line to print into the player's chat.");
        ToolRegistry.strProp(say, "player", "Send only to this player; omitted means every online player.");
        reg(registry, "mc_chat",
                "Print a line into the game chat, addressed to the player you are collaborating with.",
                say,
                args -> {
                    String text = Json.str(args, "text", "");
                    if (text.isEmpty()) return McpResult.error("text is required");
                    String player = Json.str(args, "player", null);
                    if (player == null || player.isBlank()) {
                        GameBridge.say(text);
                    } else {
                        GameBridge.tell(player, text);
                    }
                    return McpResult.text("sent: " + text);
                });

        JsonObject read = ToolRegistry.schema();
        ToolRegistry.intProp(read, "count", "How many recent lines to return (default 20).");
        ToolRegistry.strProp(read, "filter", "Optional case-insensitive regex on the message text.");
        reg(registry, "mc_chat_read",
                "Read what the player has typed recently. Combined with mc_wait this replaces log tailing.",
                read,
                args -> {
                    int count = Math.max(1, Math.min(200, Json.i(args, "count", 20)));
                    List<ChatLog.Line> lines = ChatLog.tail(count, Json.str(args, "filter", null));
                    if (lines.isEmpty()) return McpResult.text("(no chat lines)");
                    StringBuilder sb = new StringBuilder();
                    for (ChatLog.Line l : lines) {
                        sb.append(l.system() ? "[system] " : "<" + l.player() + "> ").append(l.text()).append('\n');
                    }
                    JsonObject meta = Json.obj();
                    meta.addProperty("lastSeq", ChatLog.lastSeq());
                    sb.append("\nlastSeq=").append(ChatLog.lastSeq());
                    return McpResult.text(sb.toString());
                });
    }

    // ------------------------------------------------------------------- world

    private static void registerWorld(ToolRegistry registry) {
        JsonObject blocks = ToolRegistry.schema("x1", "y1", "z1", "x2", "y2", "z2");
        ToolRegistry.intProp(blocks, "x1", "First corner X");
        ToolRegistry.intProp(blocks, "y1", "First corner Y");
        ToolRegistry.intProp(blocks, "z1", "First corner Z");
        ToolRegistry.intProp(blocks, "x2", "Second corner X");
        ToolRegistry.intProp(blocks, "y2", "Second corner Y");
        ToolRegistry.intProp(blocks, "z2", "Second corner Z");
        ToolRegistry.strProp(blocks, "dimension", "Dimension id, e.g. minecraft:overworld. Defaults to the player's.");
        ToolRegistry.boolProp(blocks, "includeAir", "Include air blocks in the output (default false).");
        ToolRegistry.intProp(blocks, "limit", "Max block lines to return (default 2000, cap 8000).");

        reg(registry, "mc_blocks",
                "Dump block states inside an inclusive box, non-air only by default. "
                        + "Output is one 'x,y,z minecraft:block[props]' line per block.",
                blocks,
                args -> {
                    BlockPos a = new BlockPos(Json.i(args, "x1", 0), Json.i(args, "y1", 0), Json.i(args, "z1", 0));
                    BlockPos b = new BlockPos(Json.i(args, "x2", 0), Json.i(args, "y2", 0), Json.i(args, "z2", 0));
                    int limit = Math.max(1, Math.min(8000, Json.i(args, "limit", 2000)));
                    boolean includeAir = Json.b(args, "includeAir", false);
                    String dim = Json.str(args, "dimension", null);
                    return GameBridge.onServer(() -> {
                        ServerLevel level = level(dim);
                        int minX = Math.min(a.getX(), b.getX());
                        int minY = Math.min(a.getY(), b.getY());
                        int minZ = Math.min(a.getZ(), b.getZ());
                        int maxX = Math.max(a.getX(), b.getX());
                        int maxY = Math.max(a.getY(), b.getY());
                        int maxZ = Math.max(a.getZ(), b.getZ());
                        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
                        if (volume > 512L * 512L * 64L) {
                            return McpResult.error("box too large: " + volume + " blocks");
                        }
                        requireLoaded(level, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
                        StringBuilder sb = new StringBuilder();
                        int shown = 0;
                        boolean truncated = false;
                        outer:
                        for (int y = minY; y <= maxY; y++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                for (int x = minX; x <= maxX; x++) {
                                    BlockPos pos = new BlockPos(x, y, z);
                                    BlockState state = level.getBlockState(pos);
                                    if (!includeAir && state.isAir()) continue;
                                    if (shown >= limit) {
                                        truncated = true;
                                        break outer;
                                    }
                                    sb.append(x).append(',').append(y).append(',').append(z).append(' ')
                                            .append(describe(state)).append('\n');
                                    shown++;
                                }
                            }
                        }
                        if (shown == 0) return McpResult.text("(nothing in that box)");
                        sb.append("--- ").append(shown).append(includeAir ? " blocks (air included)" : " non-air blocks")
                                .append(" in ").append(level.dimension().location());
                        if (truncated) sb.append(" (truncated at limit)");
                        return McpResult.text(sb.toString());
                    }, 30000);
                });

        JsonObject block = ToolRegistry.schema("x", "y", "z");
        ToolRegistry.intProp(block, "x", "Block X");
        ToolRegistry.intProp(block, "y", "Block Y");
        ToolRegistry.intProp(block, "z", "Block Z");
        ToolRegistry.strProp(block, "dimension", "Dimension id; defaults to the player's.");
        reg(registry, "mc_block",
                "Read a single block state and the block entity id at that position (if any).",
                block,
                args -> GameBridge.onServer(() -> {
                    BlockPos pos = pos(args);
                    ServerLevel level = level(Json.str(args, "dimension", null));
                    requireLoaded(level, pos);
                    BlockState state = level.getBlockState(pos);
                    BlockEntity be = level.getBlockEntity(pos);
                    JsonObject o = Json.obj();
                    o.addProperty("pos", pos.getX() + "," + pos.getY() + "," + pos.getZ());
                    o.addProperty("dimension", level.dimension().location().toString());
                    o.addProperty("block", describe(state));
                    o.addProperty("air", state.isAir());
                    o.addProperty("blockEntity", be == null ? null : typeName(be));
                    return McpResult.text(Json.GSON.toJson(o));
                }, 15000));

        JsonObject beTag = ToolRegistry.schema("x", "y", "z");
        ToolRegistry.intProp(beTag, "x", "Block X");
        ToolRegistry.intProp(beTag, "y", "Block Y");
        ToolRegistry.intProp(beTag, "z", "Block Z");
        ToolRegistry.strProp(beTag, "dimension", "Dimension id; defaults to the player's.");
        reg(registry, "mc_block_entity",
                "Full NBT (SNBT text) of the block entity at a position. Use this for machine internals: "
                        + "energy, progress, inventory, custom mod data.",
                beTag,
                args -> GameBridge.onServer(() -> {
                    BlockPos pos = pos(args);
                    ServerLevel level = level(Json.str(args, "dimension", null));
                    requireLoaded(level, pos);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be == null) return McpResult.error("no block entity at " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
                    CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
                    return McpResult.text(typeName(be) + "\n" + tag);
                }, 15000));

        JsonObject entities = ToolRegistry.schema();
        ToolRegistry.numProp(entities, "radius", "Search radius in blocks around the player (default 16).", 1, 256);
        ToolRegistry.strProp(entities, "player", "Center on this player instead of the first online player.");
        ToolRegistry.strProp(entities, "type", "Only entities whose type id contains this substring.");
        reg(registry, "mc_entities",
                "List entities around a player with type, position, distance and health.",
                entities,
                args -> GameBridge.onServer(() -> {
                    double radius = Json.d(args, "radius", 16);
                    ServerPlayer player = GameBridge.player(Json.str(args, "player", null));
                    String filter = Json.str(args, "type", null);
                    AABB box = player.getBoundingBox().inflate(radius);
                    List<Entity> found = player.level().getEntitiesOfClass(Entity.class, box);
                    List<String> lines = new ArrayList<>();
                    for (Entity e : found) {
                        String type = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
                        if (filter != null && !filter.isBlank() && !type.contains(filter.toLowerCase(Locale.ROOT))) continue;
                        StringBuilder sb = new StringBuilder();
                        sb.append(type).append(" @ ").append(GameBridge.round(e.getX())).append(',')
                                .append(GameBridge.round(e.getY())).append(',')
                                .append(GameBridge.round(e.getZ()));
                        sb.append(" dist=").append(GameBridge.round(Math.sqrt(e.distanceToSqr(player))));
                        if (e instanceof LivingEntity living) {
                            sb.append(" hp=").append(GameBridge.round(living.getHealth()))
                                    .append('/').append(GameBridge.round(living.getMaxHealth()));
                        }
                        String name = e.getName().getString();
                        if (!name.isEmpty() && !name.equals(type)) sb.append(" name=").append(name);
                        lines.add(sb.toString());
                    }
                    if (lines.isEmpty()) return McpResult.text("(nothing within " + radius + " blocks)");
                    return McpResult.text(String.join("\n", lines));
                }, 15000));

        JsonObject highlight = ToolRegistry.schema("x1", "y1", "z1", "x2", "y2", "z2");
        ToolRegistry.intProp(highlight, "x1", "Corner X");
        ToolRegistry.intProp(highlight, "y1", "Corner Y");
        ToolRegistry.intProp(highlight, "z1", "Corner Z");
        ToolRegistry.intProp(highlight, "x2", "Opposite corner X");
        ToolRegistry.intProp(highlight, "y2", "Opposite corner Y");
        ToolRegistry.intProp(highlight, "z2", "Opposite corner Z");
        ToolRegistry.numProp(highlight, "seconds", "How long the outline stays visible (default 6).", 1, 60);
        ToolRegistry.strProp(highlight, "dimension", "Dimension id; defaults to the player's.");
        reg(registry, "mc_highlight",
                "Draw a glowing particle outline around a box so the player can see exactly what you mean.",
                highlight,
                args -> {
                    BlockPos a = pos(args, "x1", "y1", "z1");
                    BlockPos b = pos(args, "x2", "y2", "z2");
                    double seconds = Json.d(args, "seconds", 6);
                    String dim = Json.str(args, "dimension", null);
                    return GameBridge.onServer(() -> {
                        ServerLevel level = level(dim);
                        int drawn = Highlights.add(level, a, b, (int) Math.round(seconds));
                        return McpResult.text("outline " + a.getX() + "," + a.getY() + "," + a.getZ()
                                + " -> " + b.getX() + "," + b.getY() + "," + b.getZ()
                                + " drawn with " + drawn + " particles, refreshed every 8 ticks for "
                                + Math.round(seconds) + "s");
                    }, 15000);
                });

        JsonObject setBlock = ToolRegistry.schema("x", "y", "z", "block");
        ToolRegistry.intProp(setBlock, "x", "Block X");
        ToolRegistry.intProp(setBlock, "y", "Block Y");
        ToolRegistry.intProp(setBlock, "z", "Block Z");
        ToolRegistry.strProp(setBlock, "block", "Block id with optional state, e.g. minecraft:oak_log[axis=x]");
        reg(registry, "mc_set_block",
                "Place a block via /setblock. Requires allowWrite=true in config/mcagent.json.",
                setBlock,
                args -> {
                    if (AgentMode.isChat()) {
                        return McpResult.error("chat mode does not edit the world directly - "
                                + "use the agent player's own hands, or /agent work first");
                    }
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("world writes are disabled; set allowWrite=true in config/mcagent.json");
                    }
                    String cmd = String.format(Locale.ROOT, "setblock %d %d %d %s",
                            Json.i(args, "x", 0), Json.i(args, "y", 0), Json.i(args, "z", 0),
                            Json.str(args, "block", "minecraft:air"));
                    return McpResult.text(GameBridge.runCommand(cmd, null, 4));
                });
    }

    // ----------------------------------------------------------------- players

    private static void registerPlayers(ToolRegistry registry) {
        JsonObject inv = ToolRegistry.schema();
        ToolRegistry.strProp(inv, "player", "Player name; defaults to the first online player.");
        reg(registry, "mc_inventory",
                "List a player's inventory (hotbar, main, armor, offhand) plus the selected hotbar slot.",
                inv,
                args -> GameBridge.onServer(() -> {
                    ServerPlayer player = GameBridge.player(Json.str(args, "player", null));
                    Inventory inventory = player.getInventory();
                    StringBuilder sb = new StringBuilder();
                    sb.append("player=").append(player.getGameProfile().getName())
                            .append(" selected=").append(inventory.selected).append('\n');
                    for (int i = 0; i < inventory.items.size(); i++) {
                        appendStack(sb, "slot" + i, inventory.items.get(i));
                    }
                    for (int i = 0; i < inventory.armor.size(); i++) {
                        appendStack(sb, "armor" + i, inventory.armor.get(i));
                    }
                    for (int i = 0; i < inventory.offhand.size(); i++) {
                        appendStack(sb, "offhand" + i, inventory.offhand.get(i));
                    }
                    return McpResult.text(sb.toString());
                }, 15000));

        JsonObject shot = ToolRegistry.schema();
        ToolRegistry.boolProp(shot, "save", "Also write the PNG into screenshots/ (default false).");
        reg(registry, "mc_screenshot",
                "Capture the game window as a PNG and return it as an image. Client only.",
                shot,
                args -> {
                    ClientApi client = ClientSupport.get();
                    if (client == null) return McpResult.error("screenshot is only available on a physical client");
                    byte[] png = client.screenshotPng();
                    String note = "captured " + png.length + " bytes PNG";
                    if (Json.b(args, "save", false)) {
                        Path dir = Dist2.gameDir().resolve("screenshots");
                        Files.createDirectories(dir);
                        Path file = dir.resolve("mcagent-" + Instant.now().toString().replace(':', '-') + ".png");
                        Files.write(file, png);
                        note += ", saved to " + file;
                    }
                    return McpResult.text(note).addImage(png, "image/png");
                });
    }

    // -------------------------------------------------------------------- diag

    private static void registerDiag(ToolRegistry registry) {
        JsonObject logs = ToolRegistry.schema();
        ToolRegistry.intProp(logs, "lines", "How many matching tail lines to return (default 80, cap 500).");
        ToolRegistry.strProp(logs, "filter", "Optional case-insensitive regex; only matching lines are returned.");
        ToolRegistry.strProp(logs, "file", "'latest' (default) or 'kubejs-server'.");
        reg(registry, "mc_logs",
                "Tail the game log without shell access. Use it to see crashes, warnings and script errors.",
                logs,
                args -> {
                    String which = Json.str(args, "file", "latest");
                    int lines = Math.max(1, Math.min(500, Json.i(args, "lines", 80)));
                    String filter = Json.str(args, "filter", null);
                    Path file = "kubejs-server".equalsIgnoreCase(which)
                            ? Dist2.gameDir().resolve("logs").resolve("kubejs").resolve("server.log")
                            : Dist2.logFile();
                    if (!Files.isRegularFile(file)) return McpResult.error("no such log: " + file);
                    Pattern p = filter == null || filter.isBlank() ? null : Pattern.compile(filter, Pattern.CASE_INSENSITIVE);
                    Deque<String> tail = new ArrayDeque<>();
                    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (p != null && !p.matcher(line).find()) continue;
                            tail.addLast(line);
                            while (tail.size() > lines) tail.removeFirst();
                        }
                    }
                    if (tail.isEmpty()) return McpResult.text("(no matching lines in " + file.getFileName() + ")");
                    return McpResult.text(String.join("\n", tail));
                });

        JsonObject wait = ToolRegistry.schema();
        ToolRegistry.intProp(wait, "ticks", "Wait for this many ticks to elapse (0 = ignore).");
        ToolRegistry.strProp(wait, "untilChat", "Wait until a chat line matches this regex.");
        ToolRegistry.intProp(wait, "timeoutMs", "Give up after this many milliseconds (default 20000).");
        reg(registry, "mc_wait",
                "Block until N ticks pass and/or the player says something matching a regex. "
                        + "Returns what happened, so you can stay in sync with the game instead of polling.",
                wait,
                args -> {
                    int ticks = Math.max(0, Json.i(args, "ticks", 0));
                    String untilChat = Json.str(args, "untilChat", null);
                    long timeoutMs = Math.max(500, Math.min(120000, Json.i(args, "timeoutMs", 20000)));
                    if (ticks == 0 && (untilChat == null || untilChat.isBlank())) {
                        return McpResult.error("give either ticks or untilChat");
                    }
                    Pattern pattern = untilChat == null || untilChat.isBlank()
                            ? null : Pattern.compile(untilChat, Pattern.CASE_INSENSITIVE);
                    long deadline = System.currentTimeMillis() + timeoutMs;
                    long seq = ChatLog.lastSeq();
                    int startTick = GameBridge.running() ? GameBridge.server().getTickCount() : 0;
                    while (System.currentTimeMillis() < deadline) {
                        if (pattern != null) {
                            List<ChatLog.Line> fresh = ChatLog.since(seq, 50);
                            for (ChatLog.Line line : fresh) {
                                if (pattern.matcher(line.text()).find()) {
                                    JsonObject hit = Json.obj();
                                    hit.addProperty("reason", "chat");
                                    hit.addProperty("player", line.player());
                                    hit.addProperty("text", line.text());
                                    hit.addProperty("ticks", GameBridge.running()
                                            ? GameBridge.server().getTickCount() - startTick : -1);
                                    return McpResult.text(Json.GSON.toJson(hit));
                                }
                            }
                        } else {
                            int elapsed = GameBridge.running() ? GameBridge.server().getTickCount() - startTick : 0;
                            if (elapsed >= ticks) {
                                JsonObject ok = Json.obj();
                                ok.addProperty("reason", "ticks");
                                ok.addProperty("ticks", elapsed);
                                return McpResult.text(Json.GSON.toJson(ok));
                            }
                        }
                        Thread.sleep(25);
                    }
                    JsonObject done = Json.obj();
                    done.addProperty("reason", "timeout");
                    done.addProperty("ticks", GameBridge.running()
                            ? GameBridge.server().getTickCount() - startTick : -1);
                    return McpResult.text(Json.GSON.toJson(done));
                });
    }

    // ----------------------------------------------------------------- editing

    private static void registerEditing(ToolRegistry registry) {
        JsonObject fill = ToolRegistry.schema("x1", "y1", "z1", "x2", "y2", "z2", "block");
        ToolRegistry.intProp(fill, "x1", "Corner X");
        ToolRegistry.intProp(fill, "y1", "Corner Y");
        ToolRegistry.intProp(fill, "z1", "Corner Z");
        ToolRegistry.intProp(fill, "x2", "Opposite corner X");
        ToolRegistry.intProp(fill, "y2", "Opposite corner Y");
        ToolRegistry.intProp(fill, "z2", "Opposite corner Z");
        ToolRegistry.strProp(fill, "block", "Block id with optional state, e.g. minecraft:oak_log[axis=x]");
        ToolRegistry.strProp(fill, "mode", "replace (default), keep, hollow, outline or destroy.");
        ToolRegistry.strProp(fill, "dimension", "Dimension id; defaults to the player's.");

        reg(registry, "mc_fill",
                "Fill a box with a block and remember the previous contents so mc_undo can revert it. "
                        + "Wraps /fill for block-state syntax. Requires allowWrite=true; capped at 4096 cells.",
                fill,
                args -> {
                    if (AgentMode.isChat()) {
                        return McpResult.error("chat mode does not edit the world directly - "
                                + "use the agent player's own hands, or /agent work first");
                    }
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("world writes are disabled; set allowWrite=true in config/mcagent.json");
                    }
                    BlockPos a = pos(args, "x1", "y1", "z1");
                    BlockPos b = pos(args, "x2", "y2", "z2");
                    String block = Json.str(args, "block", "").trim();
                    if (block.isEmpty()) return McpResult.error("block is required");
                    String mode = Json.str(args, "mode", "replace");
                    String dim = Json.str(args, "dimension", null);
                    return GameBridge.onServer(() -> {
                        ServerLevel level = level(dim);
                        int minX = Math.min(a.getX(), b.getX());
                        int minY = Math.min(a.getY(), b.getY());
                        int minZ = Math.min(a.getZ(), b.getZ());
                        int maxX = Math.max(a.getX(), b.getX());
                        int maxY = Math.max(a.getY(), b.getY());
                        int maxZ = Math.max(a.getZ(), b.getZ());
                        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
                        if (volume > 4096) {
                            return McpResult.error("box too large for an undoable fill: " + volume + " cells > 4096");
                        }
                        List<BlockPos> positions = new ArrayList<>();
                        for (int y = minY; y <= maxY; y++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                for (int x = minX; x <= maxX; x++) {
                                    positions.add(new BlockPos(x, y, z));
                                }
                            }
                        }
                        List<EditHistory.Cell> cells = new ArrayList<>();
                        EditHistory.capture(level, positions, "fill", cells);
                        String cmd = String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s %s",
                                minX, minY, minZ, maxX, maxY, maxZ, block, mode);
                        String out = GameBridge.runCommand(cmd, null, 4);
                        EditHistory.push("fill " + block + " @ " + minX + "," + minY + "," + minZ, level, cells);
                        return McpResult.text(out + "\n[" + positions.size() + " cells recorded, "
                                + EditHistory.size() + " operations in undo history]");
                    }, 60000);
                });

        JsonObject undo = ToolRegistry.schema();
        ToolRegistry.intProp(undo, "steps", "How many operations to revert (default 1, max 8).");
        reg(registry, "mc_undo",
                "Revert the most recent world edits made through mc_fill, restoring block states and "
                        + "block entity contents. Only agent-made edits are restorable.",
                undo,
                args -> GameBridge.onServer(() -> {
                    int steps = Math.max(1, Math.min(8, Json.i(args, "steps", 1)));
                    List<String> done = new ArrayList<>();
                    for (int i = 0; i < steps; i++) {
                        String result = EditHistory.undoLast();
                        if (result == null) break;
                        done.add(result);
                    }
                    return McpResult.text(done.isEmpty() ? "nothing to undo" : String.join("\n", done));
                }, 60000));

        reg(registry, "mc_history",
                "List undoable world edits, newest first.",
                ToolRegistry.schema(),
                args -> McpResult.text(EditHistory.size() == 0 ? "(undo history empty)" : String.join("\n", EditHistory.describe())));
    }

    // ------------------------------------------------------------------ watches

    private static void registerWatches(ToolRegistry registry) {
        JsonObject watch = ToolRegistry.schema("x", "y", "z");
        ToolRegistry.intProp(watch, "x", "Block X");
        ToolRegistry.intProp(watch, "y", "Block Y");
        ToolRegistry.intProp(watch, "z", "Block Z");
        ToolRegistry.strProp(watch, "label", "Short name used in the event payload (default 'watch').");
        ToolRegistry.intProp(watch, "seconds", "How long to watch (default 300, cap 3600).");
        ToolRegistry.strProp(watch, "dimension", "Dimension id; defaults to the player's.");

        reg(registry, "mc_watch",
                "Watch a block entity and push a WebSocket event whenever its NBT changes - machine "
                        + "progress, status, contents. The WS client must subscribe to the 'blocks' topic. "
                        + "The payload includes before/after digests and the machine's 'status' field.",
                watch,
                args -> GameBridge.onServer(() -> {
                    BlockPos pos = pos(args);
                    ServerLevel level = level(Json.str(args, "dimension", null));
                    if (level.getBlockEntity(pos) == null) {
                        return McpResult.error("no block entity at " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
                    }
                    int seconds = Math.max(5, Math.min(3600, Json.i(args, "seconds", 300)));
                    String msg = Watchers.add(level, pos, Json.str(args, "label", "watch"), seconds);
                    return McpResult.text(msg + "\n(WS clients must sub to 'blocks' to receive the events)");
                }, 15000));

        reg(registry, "mc_watch_list",
                "List active block entity watchers.",
                ToolRegistry.schema(),
                args -> {
                    if (AgentMode.isChat()) {
                        return McpResult.error("chat mode does not edit the world directly - "
                                + "use the agent player's own hands, or /agent work first");
                    }
                    List<String> active = Watchers.describe();
                    return McpResult.text(active.isEmpty() ? "(no watchers)" : String.join("\n", active));
                });
    }

    // ---------------------------------------------------------------- recipes

    private static void registerRecipes(ToolRegistry registry) {
        JsonObject schema = ToolRegistry.schema("item");
        ToolRegistry.strProp(schema, "item", "Item id, e.g. factory:dirt_nugget or minecraft:iron_ingot");
        ToolRegistry.intProp(schema, "limit", "Max recipes per section (default 20, cap 200).");
        ToolRegistry.boolProp(schema, "usage", "Also list recipes that consume this item (default true).");

        reg(registry, "mc_recipe",
                "Find recipes that produce or consume an item, with ingredient lists and recipe types. "
                        + "This is the direct answer to 'why won't this craft / machine produce X'.",
                schema,
                args -> {
                    String id = Json.str(args, "item", "").trim();
                    if (id.isEmpty()) return McpResult.error("item is required");
                    int limit = Math.max(1, Math.min(200, Json.i(args, "limit", 20)));
                    boolean usage = Json.b(args, "usage", true);
                    return GameBridge.onServer(() -> {
                        MinecraftServer server = GameBridge.server();
                        net.minecraft.world.item.Item item = resolveItem(id);
                        ItemStack probe = new ItemStack(item);
                        net.minecraft.core.HolderLookup.Provider registries = server.registryAccess();

                        List<String> producers = new ArrayList<>();
                        List<String> consumers = new ArrayList<>();
                        java.util.Map<String, Integer> typeCounts = new java.util.TreeMap<>();
                        int uninspectable = 0;

                        for (net.minecraft.world.item.crafting.RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
                            String type = String.valueOf(
                                    BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()));
                            typeCounts.merge(type, 1, Integer::sum);
                            try {
                                ItemStack out = holder.value().getResultItem(registries);
                                if (!out.isEmpty() && out.getItem() == item) {
                                    producers.add(describeRecipe(holder, out));
                                }
                                if (usage) {
                                    for (Ingredient ingredient : holder.value().getIngredients()) {
                                        if (ingredient.test(probe)) {
                                            consumers.add(holder.id() + "  [" + type + "]  -> "
                                                    + out.getCount() + "x " + itemId(out));
                                            break;
                                        }
                                    }
                                }
                            } catch (RuntimeException e) {
                                // Custom recipe types may not support generic result inspection.
                                uninspectable++;
                            }
                        }

                        StringBuilder sb = new StringBuilder();
                        sb.append("item: ").append(id).append('\n');
                        sb.append("== produced by (").append(producers.size()).append(") ==\n");
                        appendCapped(sb, producers, limit);
                        if (usage) {
                            sb.append("== used as ingredient by (").append(consumers.size()).append(") ==\n");
                            appendCapped(sb, consumers, limit);
                        }
                        if (uninspectable > 0) {
                            sb.append("note: ").append(uninspectable)
                                    .append(" recipes could not be inspected generically (custom recipe types)\n");
                        }
                        sb.append("== recipe types loaded (top 12) ==\n");
                        typeCounts.entrySet().stream()
                                .sorted((l, r) -> Integer.compare(r.getValue(), l.getValue()))
                                .limit(12)
                                .forEach(e -> sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n'));
                        return McpResult.text(sb.toString());
                    }, 45000);
                });

        JsonObject tags = ToolRegistry.schema("item");
        ToolRegistry.strProp(tags, "item", "Item id to inspect.");
        reg(registry, "mc_tags",
                "Item id plus the item tags and block tags it belongs to. Handy for checking whether a "
                        + "tag-based recipe or block filter will accept an item.",
                tags,
                args -> {
                    String id = Json.str(args, "item", "").trim();
                    if (id.isEmpty()) return McpResult.error("item is required");
                    return GameBridge.onServer(() -> {
                        net.minecraft.world.item.Item item = resolveItem(id);
                        ItemStack stack = new ItemStack(item);
                        StringBuilder sb = new StringBuilder();
                        sb.append("item: ").append(itemId(stack)).append('\n');
                        sb.append("name: ").append(stack.getHoverName().getString()).append('\n');
                        sb.append("maxStack: ").append(stack.getMaxStackSize())
                                .append("  damageable: ").append(stack.isDamageableItem()).append('\n');
                        sb.append("== item tags ==\n");
                        item.builtInRegistryHolder().tags().forEach(t -> sb.append("  ").append(t.location()).append('\n'));
                        net.minecraft.world.level.block.Block block = net.minecraft.world.level.block.Block.byItem(item);
                        if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                            sb.append("== block ==\n  ").append(String.valueOf(BuiltInRegistries.BLOCK.getKey(block))).append('\n');
                            sb.append("== block tags ==\n");
                            block.builtInRegistryHolder().tags().forEach(t -> sb.append("  ").append(t.location()).append('\n'));
                        }
                        return McpResult.text(sb.toString());
                    }, 20000);
                });
    }

    private static void appendCapped(StringBuilder sb, List<String> lines, int limit) {
        if (lines.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        for (int i = 0; i < Math.min(lines.size(), limit); i++) {
            sb.append("  ").append(lines.get(i)).append('\n');
        }
        if (lines.size() > limit) {
            sb.append("  … ").append(lines.size() - limit).append(" more\n");
        }
    }

    private static String describeRecipe(net.minecraft.world.item.crafting.RecipeHolder<?> holder, ItemStack out) {
        StringBuilder sb = new StringBuilder();
        sb.append(holder.id()).append("  -> ").append(out.getCount()).append("x ").append(itemId(out));
        List<String> parts = new ArrayList<>();
        for (Ingredient ingredient : holder.value().getIngredients()) {
            parts.add(describeIngredient(ingredient));
        }
        if (!parts.isEmpty()) sb.append("  <- ").append(String.join(" + ", parts));
        return sb.toString();
    }

    private static String describeIngredient(Ingredient ingredient) {
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0) return "(empty)";
        ItemStack first = items[0];
        String name = first.getCount() + "x " + itemId(first);
        return items.length == 1 ? name : name + " (1 of " + items.length + ")";
    }

    private static String itemId(ItemStack stack) {
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static net.minecraft.world.item.Item resolveItem(String id) {
        String full = id.contains(":") ? id : "minecraft:" + id;
        ResourceLocation rl = ResourceLocation.tryParse(full);
        if (rl == null) throw new IllegalArgumentException("not a valid item id: " + id);
        if (!BuiltInRegistries.ITEM.containsKey(rl)) throw new IllegalArgumentException("unknown item: " + id);
        return BuiltInRegistries.ITEM.get(rl);
    }

    // ------------------------------------------------------------------ quests

    private static void registerQuests(ToolRegistry registry) {
        if (!QuestSupport.installed()) {
            McAgent.LOG.info("[mcagent] FTB Quests not detected; quest tools not registered");
            return;
        }

        JsonObject summary = ToolRegistry.schema();
        ToolRegistry.strProp(summary, "player", "Player whose team progress to read; defaults to the first online player.");
        ToolRegistry.strProp(summary, "chapter",
                "Only chapters whose title contains this text (case-insensitive), or an exact chapter id.");
        ToolRegistry.intProp(summary, "limit", "Max quests to list (default 200, cap 500).");
        ToolRegistry.strProp(summary, "locale",
                "Which quest copy to show, read from config/ftbquests/quests/lang/<locale>.snbt (default zh_cn).");

        reg(registry, "mc_quests",
                "Read the FTB Quests tree with completion state for the player's team: chapter by chapter, "
                        + "[x]/[ ] per quest, task counts. Use it to see where a playtester actually is.",
                summary,
                args -> GameBridge.onServer(() -> {
                    dev.mcagent.quests.QuestApi api = QuestSupport.get();
                    if (api == null || !api.available()) {
                        return McpResult.error("quest file not loaded (no world yet, or FTB Quests unavailable)");
                    }
                    return McpResult.text(api.summary(
                            Json.str(args, "player", null),
                            Json.str(args, "chapter", null),
                            Math.max(1, Math.min(500, Json.i(args, "limit", 200))),
                            Json.str(args, "locale", "zh_cn")));
                }, 20000));

        JsonObject detail = ToolRegistry.schema("id");
        ToolRegistry.intProp(detail, "id", "Quest object id (the number printed by mc_quests).");
        ToolRegistry.strProp(detail, "player", "Player whose team progress to read; defaults to the first online player.");
        ToolRegistry.strProp(detail, "locale",
                "Which quest copy to show, read from config/ftbquests/quests/lang/<locale>.snbt (default zh_cn).");

        reg(registry, "mc_quest",
                "One quest in detail: type, title, started/completed times, per-task progress and reward count.",
                detail,
                args -> GameBridge.onServer(() -> {
                    dev.mcagent.quests.QuestApi api = QuestSupport.get();
                    if (api == null || !api.available()) {
                        return McpResult.error("quest file not loaded (no world yet, or FTB Quests unavailable)");
                    }
                    return McpResult.text(api.questDetail(
                            Json.l(args, "id", 0),
                            Json.str(args, "player", null),
                            Json.str(args, "locale", "zh_cn")));
                }, 20000));
    }

    // ----------------------------------------------------------------- helpers
    /**
     * Refuses to read a position whose chunk is not loaded.
     *
     * <p>{@code Level.getBlockState} on an unloaded chunk synchronously loads it,
     * and on the server thread that parks the whole tick loop on a
     * {@code CompletableFuture.join()} - it deadlocked the game once. Reads are
     * only served for chunks the player already has loaded.</p>
     */
    private static void requireLoaded(ServerLevel level, BlockPos... positions) {
        for (BlockPos pos : positions) {
            if (!level.isLoaded(pos)) {
                throw new IllegalStateException("chunk not loaded at " + pos.getX() + "," + pos.getY() + ","
                        + pos.getZ() + " - move closer or load that area first (refusing to force a chunk load)");
            }
        }
    }

    private static void appendStack(StringBuilder sb, String slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        sb.append(slot).append(": ").append(id).append(" x").append(stack.getCount());
        String name = stack.getHoverName().getString();
        if (!name.isEmpty() && !name.equals(id)) sb.append(" (\"").append(name).append("\")");
        if (stack.isDamageableItem()) sb.append(" dmg=").append(stack.getDamageValue());
        sb.append('\n');
    }

    private static BlockPos pos(JsonObject args) {
        return pos(args, "x", "y", "z");
    }

    /**
     * BlockState.toString() yields {@code Block{minecraft:stone}}, which is
     * useless in a tool response, so render the registry id plus properties.
     */
    static String describe(BlockState state) {
        StringBuilder sb = new StringBuilder(String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
        java.util.Collection<Property<?>> properties = state.getProperties();
        if (!properties.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Property<?> property : properties) {
                if (!first) sb.append(',');
                first = false;
                sb.append(property.getName()).append('=').append(valueName(state, property));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static String typeName(BlockEntity blockEntity) {
        return String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()));
    }

    private static BlockPos pos(JsonObject args, String xk, String yk, String zk) {
        return new BlockPos(Json.i(args, xk, 0), Json.i(args, yk, 0), Json.i(args, zk, 0));
    }

    /** Resolves a level from a dimension id, defaulting to the executing player's level. */
    private static ServerLevel level(String dimension) {
        MinecraftServer server = GameBridge.server();
        if (dimension == null || dimension.isBlank()) {
            ServerPlayer player = GameBridge.player(null);
            return player.serverLevel();
        }
        String id = dimension.contains(":") ? dimension : "minecraft:" + dimension;
        ResourceLocation rl = ResourceLocation.parse(id);
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
        if (level == null) throw new IllegalArgumentException("unknown dimension: " + dimension);
        return level;
    }

    static JsonArray arrayOf(List<String> lines) {
        JsonArray arr = Json.arr();
        for (String l : lines) arr.add(l);
        return arr;
    }

    // ------------------------------------------------------- agent players

    /** The first online player that the agent did not create itself. */
    private static ServerPlayer realPlayer(MinecraftServer server) {
        List<String> agentNames = FakePlayers.names();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!agentNames.contains(p.getGameProfile().getName())) {
                return p;
            }
        }
        return null;
    }

    private static void registerAgentPlayers(ToolRegistry registry) {
        JsonObject spawn = ToolRegistry.schema();
        ToolRegistry.strProp(spawn, "name",
                "Ignored: the name comes from playerName in config/mcagent.json.");
        ToolRegistry.numProp(spawn, "x", "Spawn X; defaults to the first real player's position.",
                -30000000, 30000000);
        ToolRegistry.numProp(spawn, "y", "Spawn Y; defaults to the first real player's position.", -64, 320);
        ToolRegistry.numProp(spawn, "z", "Spawn Z; defaults to the first real player's position.",
                -30000000, 30000000);
        reg(registry, "mc_player_spawn",
                "Create a server-side player owned by the agent: a real ServerPlayer with no client behind it. "
                        + "It appears in the tab list, has its own inventory and health, obeys normal tool rules "
                        + "when mining, takes damage, and keeps the chunks around it loaded.",
                spawn,
                args -> GameBridge.onServer(() -> {
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("spawning players is disabled by config (allowWrite=false)");
                    }
                    MinecraftServer server = GameBridge.server();
                    ServerPlayer anchor = realPlayer(server);
                    if (anchor == null) {
                        return McpResult.error("no real player is online to anchor the spawn to");
                    }
                    // The name is configuration, not a tool argument: the agent must not be
                    // able to rename its own body (a skin bound to a name would silently
                    // stop applying, as happened when it renamed itself to Dafeiyu).
                    String name = AgentConfig.get().resolvedPlayerName();
                    try {
                        ServerLevel level = (ServerLevel) anchor.level();
                        double x = Json.d(args, "x", anchor.getX());
                        double y = Json.d(args, "y", anchor.getY());
                        double z = Json.d(args, "z", anchor.getZ());
                        ServerPlayer p = FakePlayers.spawn(server, level, name, x, y, z, anchor.getYRot(), 0.0F);
                        JsonObject o = Json.obj();
                        o.addProperty("name", p.getGameProfile().getName());
                        o.addProperty("uuid", p.getUUID().toString());
                        o.addProperty("dimension", level.dimension().location().toString());
                        o.addProperty("x", p.getX());
                        o.addProperty("y", p.getY());
                        o.addProperty("z", p.getZ());
                        return McpResult.text(Json.GSON.toJson(o));
                    } catch (Throwable t) {
                        return McpResult.error("spawn failed: " + t);
                    }
                }, 15000));

        JsonObject byName = ToolRegistry.schema("name");
        ToolRegistry.strProp(byName, "name", "Name of the agent player.");
        reg(registry, "mc_player_despawn", "Remove one of the agent's players from the world.", byName,
                args -> GameBridge.onServer(() -> {
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("despawning players is disabled by config (allowWrite=false)");
                    }
                    String name = Json.str(args, "name", "");
                    boolean gone = FakePlayers.despawn(GameBridge.server(), name);
                    return McpResult.text(gone ? "despawned " + name : "no agent player named " + name);
                }, 15000));

        reg(registry, "mc_player_list",
                "List the agent's spawned players with position, health and held item.",
                ToolRegistry.schema(),
                args -> GameBridge.onServer(() -> {
                    JsonArray out = Json.arr();
                    for (String name : FakePlayers.names()) {
                        ServerPlayer p = FakePlayers.byName(name);
                        if (p == null) {
                            continue;
                        }
                        JsonObject o = Json.obj();
                        o.addProperty("name", name);
                        o.addProperty("x", Math.round(p.getX() * 100) / 100.0);
                        o.addProperty("y", Math.round(p.getY() * 100) / 100.0);
                        o.addProperty("z", Math.round(p.getZ() * 100) / 100.0);
                        o.addProperty("health", p.getHealth());
                        o.addProperty("selected", p.getInventory().selected);
                        o.addProperty("holding", p.getMainHandItem().getItem().toString());
                        o.addProperty("walking", AgentMovement.isWalking(p));
                        out.add(o);
                    }
                    return McpResult.text(Json.GSON.toJson(out));
                }, 15000));

        JsonObject move = ToolRegistry.schema("name", "x", "y", "z");
        ToolRegistry.strProp(move, "name", "Name of the agent player.");
        ToolRegistry.numProp(move, "x", "Target X.", -30000000, 30000000);
        ToolRegistry.numProp(move, "y", "Target Y.", -64, 320);
        ToolRegistry.numProp(move, "z", "Target Z.", -30000000, 30000000);
        reg(registry, "mc_player_teleport",
                "Teleport an agent player (dev/build tool; refused in chat mode - use mc_player_walk).",
                move,
                args -> GameBridge.onServer(() -> {
                    // Teleport is a build tool, not a way to move around. In chat mode the
                    // body has to actually walk, otherwise "chat" keeps a silent cheat.
                    if (AgentMode.isChat()) {
                        return McpResult.error("chat mode moves with mc_player_walk; "
                                + "mc_player_teleport is a build/dev tool");
                    }
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("moving players is disabled by config (allowWrite=false)");
                    }
                    ServerPlayer p = FakePlayers.byName(Json.str(args, "name", ""));
                    if (p == null) {
                        return McpResult.error("no such agent player");
                    }
                    FakePlayers.teleport(p, (ServerLevel) p.level(),
                            Json.d(args, "x", p.getX()),
                            Json.d(args, "y", p.getY()),
                            Json.d(args, "z", p.getZ()),
                            p.getYRot(), p.getXRot());
                    return McpResult.text("moved " + p.getGameProfile().getName() + " to "
                            + p.getX() + ", " + p.getY() + ", " + p.getZ());
                }, 15000));

        JsonObject look = ToolRegistry.schema("name");
        ToolRegistry.strProp(look, "name", "Name of the agent player.");
        ToolRegistry.numProp(look, "yaw", "Yaw in degrees.", -360, 360);
        ToolRegistry.numProp(look, "pitch", "Pitch in degrees.", -90, 90);
        reg(registry, "mc_player_look", "Aim one of the agent's players.", look,
                args -> GameBridge.onServer(() -> {
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("aiming players is disabled by config (allowWrite=false)");
                    }
                    ServerPlayer p = FakePlayers.byName(Json.str(args, "name", ""));
                    if (p == null) {
                        return McpResult.error("no such agent player");
                    }
                    float yaw = (float) Json.d(args, "yaw", p.getYRot());
                    float pitch = (float) Json.d(args, "pitch", p.getXRot());
                    p.teleportTo((ServerLevel) p.level(), p.getX(), p.getY(), p.getZ(), yaw, pitch);
                    return McpResult.text("yaw=" + yaw + " pitch=" + pitch);
                }, 15000));

        JsonObject hold = ToolRegistry.schema("name", "slot");
        ToolRegistry.strProp(hold, "name", "Name of the agent player.");
        ToolRegistry.intProp(hold, "slot", "Hotbar slot 0-8.");
        reg(registry, "mc_player_hold", "Select a hotbar slot on one of the agent's players.", hold,
                args -> GameBridge.onServer(() -> {
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("changing the held item is disabled by config (allowWrite=false)");
                    }
                    ServerPlayer p = FakePlayers.byName(Json.str(args, "name", ""));
                    if (p == null) {
                        return McpResult.error("no such agent player");
                    }
                    int slot = Json.i(args, "slot", 0);
                    if (slot < 0 || slot > 8) {
                        return McpResult.error("slot must be 0-8");
                    }
                    p.getInventory().selected = slot;
                    return McpResult.text("selected=" + slot + " holding=" + p.getMainHandItem().getItem());
                }, 15000));
    }


    // ------------------------------------------------------- agent movement

    private static void registerMovement(ToolRegistry registry) {
        JsonObject walk = ToolRegistry.schema("name", "x", "y", "z");
        ToolRegistry.strProp(walk, "name", "Name of the agent player.");
        ToolRegistry.numProp(walk, "x", "Target X.", -30000000, 30000000);
        ToolRegistry.numProp(walk, "y", "Target Y.", -64, 320);
        ToolRegistry.numProp(walk, "z", "Target Z.", -30000000, 30000000);
        ToolRegistry.boolProp(walk, "sprint", "Sprint while walking.");
        reg(registry, "mc_player_walk",
                "Walk one of the agent's players to a point with normal movement: the game's own "
                        + "physics handles collisions, gravity, step-up and jumping. Not a teleport.",
                walk,
                args -> GameBridge.onServer(() -> {
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("walking is disabled by config (allowWrite=false)");
                    }
                    ServerPlayer p = FakePlayers.byName(Json.str(args, "name", ""));
                    if (p == null) {
                        return McpResult.error("no such agent player");
                    }
                    net.minecraft.world.phys.Vec3 target = new net.minecraft.world.phys.Vec3(
                            Json.d(args, "x", p.getX()),
                            Json.d(args, "y", p.getY()),
                            Json.d(args, "z", p.getZ()));
                    AgentMovement.start(p, target, Json.b(args, "sprint", false),
                            GameBridge.server().getTickCount());
                    return McpResult.text("walking to " + target.x + ", " + target.y + ", " + target.z
                            + " (" + AgentMovement.planSummary(p) + ")");
                }, 15000));

        JsonObject halt = ToolRegistry.schema("name");
        ToolRegistry.strProp(halt, "name", "Name of the agent player.");
        reg(registry, "mc_player_halt", "Stop one of the agent's players where it stands.", halt,
                args -> GameBridge.onServer(() -> {
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("movement is disabled by config (allowWrite=false)");
                    }
                    ServerPlayer p = FakePlayers.byName(Json.str(args, "name", ""));
                    if (p == null) {
                        return McpResult.error("no such agent player");
                    }
                    boolean was = AgentMovement.stop(p);
                    return McpResult.text(was ? "stopped" : "was not walking");
                }, 15000));
    }


    // ------------------------------------------------------- agent hands

    private static void registerActions(ToolRegistry registry) {
        JsonObject brk = ToolRegistry.schema("name", "x", "y", "z");
        ToolRegistry.strProp(brk, "name", "Name of the agent player.");
        ToolRegistry.numProp(brk, "x", "Block X.", -30000000, 30000000);
        ToolRegistry.numProp(brk, "y", "Block Y.", -64, 320);
        ToolRegistry.numProp(brk, "z", "Block Z.", -30000000, 30000000);
        ToolRegistry.strProp(brk, "face", "Face to mine from: up, down, north, south, east, west.");
        reg(registry, "mc_player_break",
                "Start mining a block as the agent's player. Real server-side mining: progress builds "
                        + "over ticks, tool and block hardness decide how long it takes, drops are normal.",
                brk,
                args -> GameBridge.onServer(() -> {
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("breaking blocks is disabled by config (allowWrite=false)");
                    }
                    ServerPlayer p = FakePlayers.byName(Json.str(args, "name", ""));
                    if (p == null) {
                        return McpResult.error("no such agent player");
                    }
                    BlockPos pos = new BlockPos(Json.i(args, "x", 0), Json.i(args, "y", 0), Json.i(args, "z", 0));
                    Direction face = faceOf(Json.str(args, "face", "up"));
                    return McpResult.text(AgentActions.startBreak(p, pos, face));
                }, 15000));

        JsonObject useOn = ToolRegistry.schema("name", "x", "y", "z");
        ToolRegistry.strProp(useOn, "name", "Name of the agent player.");
        ToolRegistry.numProp(useOn, "x", "Block X.", -30000000, 30000000);
        ToolRegistry.numProp(useOn, "y", "Block Y.", -64, 320);
        ToolRegistry.numProp(useOn, "z", "Block Z.", -30000000, 30000000);
        ToolRegistry.strProp(useOn, "face",
                "Which face to click: up, down, north, south, east, west. Placement goes onto that face. Default up.");
        reg(registry, "mc_player_place",
                "Right-click a block face as the agent's player: places the held block, opens chests, "
                        + "flips levers, uses doors - whatever the held item does in vanilla.",
                useOn,
                args -> GameBridge.onServer(() -> {
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("placing is disabled by config (allowWrite=false)");
                    }
                    ServerPlayer p = FakePlayers.byName(Json.str(args, "name", ""));
                    if (p == null) {
                        return McpResult.error("no such agent player");
                    }
                    BlockPos pos = new BlockPos(Json.i(args, "x", 0), Json.i(args, "y", 0), Json.i(args, "z", 0));
                    return McpResult.text(AgentActions.useOn(p, pos, faceOf(Json.str(args, "face", "up"))));
                }, 15000));

        JsonObject use = ToolRegistry.schema("name");
        ToolRegistry.strProp(use, "name", "Name of the agent player.");
        reg(registry, "mc_player_use",
                "Use the held item in the air as the agent's player: eat, drink, throw, shoot.",
                use,
                args -> GameBridge.onServer(() -> {
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("using items is disabled by config (allowWrite=false)");
                    }
                    ServerPlayer p = FakePlayers.byName(Json.str(args, "name", ""));
                    if (p == null) {
                        return McpResult.error("no such agent player");
                    }
                    return McpResult.text(AgentActions.use(p));
                }, 15000));

        JsonObject atk = ToolRegistry.schema("name");
        ToolRegistry.strProp(atk, "name", "Name of the agent player.");
        ToolRegistry.intProp(atk, "id", "Entity id; omit to hit the nearest living entity.");
        ToolRegistry.numProp(atk, "radius", "Search radius when no id is given.", 1, 16);
        reg(registry, "mc_player_attack",
                "Hit an entity as the agent's player, with normal damage calculation.",
                atk,
                args -> GameBridge.onServer(() -> {
                    if (!AgentConfig.get().allowWrite) {
                        return McpResult.error("attacking is disabled by config (allowWrite=false)");
                    }
                    ServerPlayer p = FakePlayers.byName(Json.str(args, "name", ""));
                    if (p == null) {
                        return McpResult.error("no such agent player");
                    }
                    return McpResult.text(AgentActions.attack(p,
                            Json.i(args, "id", -1), Json.d(args, "radius", 3.5)));
                }, 15000));
    }

    private static Direction faceOf(String name) {
        return switch (name == null ? "up" : name.toLowerCase(java.util.Locale.ROOT)) {
            case "down" -> Direction.DOWN;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> Direction.UP;
        };
    }

}
