package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Bounded, immutable edit previews. All access and world inspection is on the server thread. */
public final class EditPlans {
    private static final int MAX_PLANS = 8;
    private static final long TTL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
    private static final LinkedHashMap<String, Plan> PLANS = new LinkedHashMap<>();
    record Plan(JsonObject preview, ServerLevel level, UUID playerId, String command, BlockState proposed,
                List<EditHistory.Cell> before, long expires, long modeRevision, String selectionId) {}
    private EditPlans() {}

    public static void clear() { PLANS.clear(); }

    public static void register(ToolRegistry registry) {
        JsonObject preview = ToolRegistry.schema("x1", "y1", "z1", "x2", "y2", "z2", "block");
        for (String key : List.of("x1", "z1", "x2", "z2")) integer(preview, key, -30000000, 30000000);
        for (String key : List.of("y1", "y2")) integer(preview, key, Integer.MIN_VALUE, Integer.MAX_VALUE);
        ToolRegistry.strProp(preview, "block", "Block state with optional NBT, parsed by this server's actual /fill grammar.");
        ToolRegistry.strProp(preview, "mode", "replace (default), keep, hollow, outline or destroy.");
        ToolRegistry.strProp(preview, "dimension", "Dimension id; defaults to the current player's dimension.");
        add(registry, "mc_edit_preview", "Read-only preview of an undoable fill (use identical corners for one block). "
                + "Captures block/NBT preconditions; returns bounded samples, an estimated change count and single-use plan tokens. "
                + "No world changes or write permission granted. Plans expire after five minutes.", preview, EditPlans::preview);
        JsonObject selection = ToolRegistry.schema("playerId", "worldSessionId", "selectionId", "block");
        ToolRegistry.strProp(selection, "playerId", "Exact player UUID returned by mc_selection.");
        ToolRegistry.strProp(selection, "worldSessionId", "Exact world session returned by mc_selection.");
        ToolRegistry.strProp(selection, "selectionId", "Exact version of the ready region returned by mc_selection.");
        ToolRegistry.strProp(selection, "block", "Block state with optional NBT, parsed by this server's actual /fill grammar.");
        ToolRegistry.strProp(selection, "mode", "replace (default), keep, hollow, outline or destroy.");
        add(registry, "mc_edit_preview_selection", "Preview an undoable fill of one player's explicitly selected region. "
                + "Uses both captured corners in the selected dimension. Apply and start require the same selection version.",
                selection, EditPlans::previewSelection);
        JsonObject tokens = ToolRegistry.schema("planId", "worldSessionId", "planRevision");
        ToolRegistry.strProp(tokens, "planId", "Exact planId returned by an edit preview tool.");
        ToolRegistry.strProp(tokens, "worldSessionId", "Exact world session returned with the preview.");
        integer(tokens, "planRevision", 1, Integer.MAX_VALUE);
        add(registry, "mc_edit_apply", "Apply exactly one retained preview, with allowWrite=true. "
                + "Rejects changed world, mode generation, player, unloaded chunks and conflicting block/NBT state. "
                + "Consumed before command dispatch; never replay on an uncertain result. Verify changedCells and actual world state.", tokens,
                args -> apply(args, false));
        add(registry, "mc_edit_discard", "Discard a retained preview without changing the world.", tokens,
                args -> apply(args, true));
        JsonObject start = tokens.deepCopy();
        integer(start, "batchCells", 1, 128);
        add(registry, "mc_edit_start", "Consume a preview and start a cancellable, queried edit job. "
                + "Up to 32768 cells; one active job per world. Default 32 cells per tick, with a cooperative 5 ms budget. "
                + "Renew control with mc_edit_keepalive within 15 seconds; querying status does not renew. Verify actual outcomes.",
                start, args -> apply(args, false, true));
    }

    private interface Operation { McpResult run(JsonObject args); }
    private static void add(ToolRegistry registry, String name, String description, JsonObject schema, Operation operation) {
        registry.add(new McpTool() {
            public String name() { return name; }
            public String description() { return description; }
            public JsonObject inputSchema() { return schema; }
            public McpResult call(JsonObject args) {
                return GameBridge.onServer(() -> {
                    try { return operation.run(args); }
                    catch (IllegalArgumentException e) { return McpResult.error(e.getMessage()); }
                }, 30000);
            }
        });
    }
    private static void integer(JsonObject schema, String key, int min, int max) {
        ToolRegistry.intProp(schema, key, key);
        JsonObject property = schema.getAsJsonObject("properties").getAsJsonObject(key);
        property.addProperty("minimum", min); property.addProperty("maximum", max);
    }

    private static McpResult preview(JsonObject args) {
        var server = GameBridge.server();
        ServerPlayer player = GameBridge.player(null);
        String dimension = Json.str(args, "dimension", player.level().dimension().location().toString());
        ResourceLocation id = ResourceLocation.tryParse(dimension.contains(":") ? dimension : "minecraft:" + dimension);
        if (id == null) return McpResult.error("invalid dimension id");
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) return McpResult.error("dimension is not available");
        BlockPos a = new BlockPos(Json.i(args, "x1", 0), Json.i(args, "y1", 0), Json.i(args, "z1", 0));
        BlockPos b = new BlockPos(Json.i(args, "x2", 0), Json.i(args, "y2", 0), Json.i(args, "z2", 0));
        return preview(args, player, level, a, b, null);
    }

    private static McpResult previewSelection(JsonObject args) {
        String selectionId = Json.str(args, "selectionId", "");
        var region = PlayerSelections.requireRegion(Json.str(args, "playerId", ""),
                Json.str(args, "worldSessionId", ""), selectionId);
        return preview(args, region.player(), region.player().serverLevel(), region.a(), region.b(), selectionId);
    }

    private static McpResult preview(JsonObject args, ServerPlayer player, ServerLevel level,
                                     BlockPos a, BlockPos b, String selectionId) {
        var server = GameBridge.server();
        String block = Json.str(args, "block", "").strip();
        String mode = Json.str(args, "mode", "replace");
        if (!Set.of("replace", "keep", "hollow", "outline", "destroy").contains(mode))
            return McpResult.error("unsupported fill mode");
        if (block.isEmpty() || block.length() > 2048 || block.contains("\n") || block.contains("\r"))
            return McpResult.error("block must be one nonempty line of at most 2048 characters");
        PLANS.entrySet().removeIf(entry -> System.nanoTime() >= entry.getValue().expires());
        if (PLANS.size() >= MAX_PLANS) return McpResult.error("eight previews already retained; discard one before creating another");
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
        long width = (long) maxX - minX + 1, height = (long) maxY - minY + 1, depth = (long) maxZ - minZ + 1;
        if (width > 32768 || height > 32768 || depth > 32768 || width * height * depth > 32768)
            return McpResult.error("preview region exceeds 32768 cells");
        if (!level.isInWorldBounds(new BlockPos(minX, minY, minZ)) || !level.isInWorldBounds(new BlockPos(maxX, maxY, maxZ)))
            return McpResult.error("preview region is outside this dimension's build bounds");
        String command = "fill " + minX + " " + minY + " " + minZ + " " + maxX + " " + maxY + " " + maxZ + " " + block + " " + mode;
        var source = player.createCommandSourceStack().withPermission(4).withLevel(level);
        var dispatcher = server.getCommands().getDispatcher();
        JsonObject syntax = CommandKnowledge.inspect(dispatcher, source, command);
        if (!Json.b(syntax, "syntaxComplete", false)) return McpResult.error(syntax.toString());
        BlockState proposed = BlockStateArgument.getBlock(dispatcher.parse(command, source).getContext().build(command), "block").getState();
        List<BlockPos> positions = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) return McpResult.error("preview region contains unloaded chunks; move closer first");
            positions.add(pos);
        }
        List<EditHistory.Cell> before = new ArrayList<>();
        EditHistory.capture(level, positions, "preview", before);
        int estimated = 0, blockEntities = 0, nbtChars = 0;
        JsonArray samples = Json.arr();
        for (EditHistory.Cell cell : before) {
            BlockPos pos = cell.pos();
            boolean edge = pos.getX() == minX || pos.getX() == maxX || pos.getY() == minY || pos.getY() == maxY || pos.getZ() == minZ || pos.getZ() == maxZ;
            boolean candidate = !(mode.equals("outline") && !edge) && !(mode.equals("keep") && !cell.oldState().isAir());
            BlockState after = mode.equals("hollow") && !edge ? Blocks.AIR.defaultBlockState() : proposed;
            if (cell.oldBlockEntity() != null) {
                blockEntities++;
                nbtChars += cell.oldBlockEntity().toString().length();
                if (nbtChars > 1048576) return McpResult.error("preview NBT snapshot exceeds 1 MiB characters; select a smaller region");
            }
            boolean mayChange = candidate && (!cell.oldState().equals(after) || cell.oldBlockEntity() != null || block.contains("{") || mode.equals("destroy"));
            if (mayChange) estimated++;
            if (mayChange && samples.size() < 16) {
                JsonObject sample = Json.obj(); sample.addProperty("pos", pos.toShortString());
                sample.addProperty("before", Tools.describe(cell.oldState())); sample.addProperty("proposed", Tools.describe(after));
                sample.addProperty("hasBlockEntity", cell.oldBlockEntity() != null); samples.add(sample);
            }
        }
        JsonObject out = Json.obj();
        String planId = UUID.randomUUID().toString();
        out.addProperty("planId", planId); out.addProperty("planRevision", 1);
        out.addProperty("worldSessionId", GameBridge.worldSessionId());
        out.addProperty("saveFolder", server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize().getFileName().toString());
        out.addProperty("dimension", level.dimension().location().toString());
        out.addProperty("player", player.getGameProfile().getName());
        if (selectionId != null) {
            out.addProperty("selectionId", selectionId);
            out.addProperty("playerId", player.getUUID().toString());
        }
        out.addProperty("min", minX + "," + minY + "," + minZ); out.addProperty("max", maxX + "," + maxY + "," + maxZ);
        out.addProperty("block", block); out.addProperty("mode", mode);
        out.addProperty("cellCount", before.size()); out.addProperty("estimatedChangedCells", estimated);
        out.addProperty("estimateScope", "conservative before neighbor updates and mod hooks; includes possible NBT changes");
        out.addProperty("blockEntityCells", blockEntities); out.add("samples", samples);
        out.addProperty("samplesTruncated", estimated > samples.size()); out.addProperty("expiresInSeconds", 300);
        out.addProperty("executionAllowed", !AgentMode.isChat() && AgentConfig.get().allowWrite);
        out.addProperty("undoScope", "target blocks and NBT only; drops, neighbor effects and arbitrary commands are not restored");
        PLANS.put(planId, new Plan(out.deepCopy(), level, player.getUUID(), command, proposed, before,
                System.nanoTime() + TTL_NANOS, AgentMode.revision(), selectionId));
        return McpResult.text(out.toString());
    }

    private static McpResult apply(JsonObject args, boolean discard) {
        return apply(args, discard, false);
    }

    private static McpResult apply(JsonObject args, boolean discard, boolean batched) {
        String id = Json.str(args, "planId", "");
        Plan plan = PLANS.get(id);
        if (plan == null) return McpResult.error("preview missing, consumed or from an earlier world; inspect before creating a new plan");
        if (!GameBridge.worldSessionId().equals(Json.str(args, "worldSessionId", "")) || Json.i(args, "planRevision", 0) != 1)
            return McpResult.error("preview world or revision does not match");
        if (discard) { PLANS.remove(id); return McpResult.text("preview discarded; world unchanged"); }
        if (plan.selectionId() != null) {
            try {
                var region = PlayerSelections.requireRegion(plan.playerId().toString(), GameBridge.worldSessionId(), plan.selectionId());
                if (region.player().serverLevel() != plan.level())
                    throw new IllegalArgumentException("selected region dimension changed since preview");
            } catch (IllegalArgumentException stale) {
                PLANS.remove(id);
                return McpResult.error(stale.getMessage());
            }
        }
        if (EditJobs.active()) return McpResult.error("an edit job is active; wait or cancel it before another managed edit");
        if (!batched && plan.before().size() > 4096) return McpResult.error("preview exceeds synchronous limit; use mc_edit_start");
        if (System.nanoTime() >= plan.expires()) { PLANS.remove(id); return McpResult.error("preview expired; create a fresh preview"); }
        if (AgentMode.isChat() || !AgentConfig.get().allowWrite) return McpResult.error("edit apply requires Work and allowWrite=true");
        if (AgentMode.revision() != plan.modeRevision()) { PLANS.remove(id); return McpResult.error("mode changed since preview; create a fresh preview"); }
        if (GameBridge.server().getLevel(plan.level().dimension()) != plan.level()) return McpResult.error("preview dimension is no longer active");
        ServerPlayer player = GameBridge.server().getPlayerList().getPlayer(plan.playerId());
        if (player == null) return McpResult.error("preview player is no longer online");
        int conflicts = 0;
        JsonArray changedPositions = Json.arr();
        for (EditHistory.Cell cell : plan.before()) {
            if (!plan.level().isLoaded(cell.pos())) return McpResult.error("preview region is no longer loaded; no changes applied");
            var be = plan.level().getBlockEntity(cell.pos());
            var nbt = be == null ? null : be.saveWithFullMetadata(plan.level().registryAccess());
            if (!cell.oldState().equals(plan.level().getBlockState(cell.pos())) || !java.util.Objects.equals(cell.oldBlockEntity(), nbt)) {
                conflicts++; if (changedPositions.size() < 16) changedPositions.add(cell.pos().toShortString());
            }
        }
        if (conflicts > 0) {
            PLANS.remove(id);
            JsonObject error = Json.obj(); error.addProperty("outcome", "conflict"); error.addProperty("conflictingCells", conflicts);
            error.add("positions", changedPositions); error.addProperty("dispatched", false);
            return McpResult.text(error.toString()).asError();
        }
        // Consume before dispatch: an uncertain result can never turn a retry into a second write.
        PLANS.remove(id);
        if (batched) return EditJobs.start(id, plan, Json.i(args, "batchCells", 32));
        JsonObject receipt;
        int changed;
        try { receipt = GameBridge.runCommandResult(plan.command(), player.getGameProfile().getName(), 4, plan.level()); }
        catch (RuntimeException e) {
            McAgent.LOG.warn("[mcagent] edit preview {} failed after dispatch may have started; inspect actual state", id, e);
            receipt = Json.obj(); receipt.addProperty("outcome", "outcome_unknown"); receipt.addProperty("dispatchState", "unknown");
            receipt.addProperty("verificationRequired", true);
            receipt.addProperty("error", "command raised an exception; plan consumed; inspect world and history before another operation");
        }
        finally { changed = EditHistory.pushChanged("preview " + id, plan.level(), plan.before()); }
        receipt.addProperty("planId", id); receipt.addProperty("worldSessionId", GameBridge.worldSessionId());
        receipt.addProperty("changedCells", changed); receipt.addProperty("undoRecorded", changed > 0);
        McpResult result = McpResult.text(receipt.toString());
        return receipt.has("error") || !Json.b(receipt, "dispatched", false) || receipt.get("failedCallbacks").getAsLong() > 0 ? result.asError() : result;
    }
}
