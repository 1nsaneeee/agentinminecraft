package dev.mcagent;

import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mcagent.fakeplayer.FakePlayers;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Selection belongs to the native player and lives only on the server thread. */
final class PlayerSelections {
    private static final double MAX_REACH = 8.0;
    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

    record Region(ServerPlayer player, BlockPos a, BlockPos b) {}
    private record Selection(UUID id, ServerLevel level, Target target, BlockPos a, BlockPos b) {
        boolean ready() {
            return a != null && b != null && loaded(level, a) && loaded(level, b);
        }
    }
    private record Ray(Vec3 start, Vec3 end, AABB bounds, BlockHitResult block) {}
    private enum Action { BLOCK, ENTITY, A, B, SHOW, CLEAR }

    private interface Target {
        JsonObject snapshot(ServerLevel level);
    }

    private record BlockTarget(BlockPos pos, Block block, WeakReference<BlockEntity> blockEntity) implements Target {
        @Override
        public JsonObject snapshot(ServerLevel level) {
            JsonObject out = position(pos);
            out.addProperty("kind", "block");
            out.addProperty("block", BuiltInRegistries.BLOCK.getKey(block).toString());
            if (!loaded(level, pos)) {
                unavailable(out, "chunk_unloaded");
                return out;
            }
            BlockEntity original = blockEntity == null ? null : blockEntity.get();
            if (level.getBlockState(pos).getBlock() != block || (blockEntity != null && original == null)
                    || level.getBlockEntity(pos) != original) {
                unavailable(out, "block_changed");
            } else {
                out.addProperty("status", "active");
            }
            return out;
        }
    }

    private record EntityTarget(UUID id, EntityType<?> type) implements Target {
        @Override
        public JsonObject snapshot(ServerLevel level) {
            JsonObject out = Json.obj();
            out.addProperty("kind", "entity");
            out.addProperty("entityId", id.toString());
            out.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
            Entity entity = level.getEntity(id);
            if (entity == null || !entity.isAlive() || entity.getType() != type || !loaded(level, entity.blockPosition())) {
                unavailable(out, "entity_unavailable");
                return out;
            }
            out.addProperty("status", "active");
            out.addProperty("x", entity.getX());
            out.addProperty("y", entity.getY());
            out.addProperty("z", entity.getZ());
            return out;
        }
    }

    private PlayerSelections() {}

    static void clear() { SELECTIONS.clear(); }
    static void remove(UUID playerId) { SELECTIONS.remove(playerId); }

    /** Include the same facts as mc_selection when the player has selected a target or corner. */
    static JsonObject current(ServerPlayer player) {
        UUID id = player.getUUID();
        return SELECTIONS.containsKey(id) ? snapshot(id) : null;
    }

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("select")
                .then(Commands.literal("block").executes(ctx -> select(ctx.getSource(), Action.BLOCK)))
                .then(Commands.literal("entity").executes(ctx -> select(ctx.getSource(), Action.ENTITY)))
                .then(Commands.literal("corner")
                        .then(Commands.literal("a").executes(ctx -> select(ctx.getSource(), Action.A)))
                        .then(Commands.literal("b").executes(ctx -> select(ctx.getSource(), Action.B))))
                .then(Commands.literal("show").executes(ctx -> select(ctx.getSource(), Action.SHOW)))
                .then(Commands.literal("clear").executes(ctx -> select(ctx.getSource(), Action.CLEAR)));
    }

    static void register(ToolRegistry registry) {
        JsonObject schema = ToolRegistry.schema("playerId");
        ToolRegistry.strProp(schema, "playerId", "Exact native player's UUID, from mc_status.");
        registry.add(new McpTool() {
            public String name() { return "mc_selection"; }
            public String description() {
                return "Read a player's current explicit target or region. Check the world session and "
                        + "selection ID before previewing. The usual write permissions still apply.";
            }
            public JsonObject inputSchema() { return schema; }
            public McpResult call(JsonObject args) {
                try {
                    UUID playerId = uuid(Json.str(args, "playerId", ""), "playerId");
                    return GameBridge.onServer(() -> McpResult.text(snapshot(playerId).toString()), 5000);
                } catch (IllegalArgumentException error) {
                    return McpResult.error(error.getMessage());
                }
            }
        });
    }

    static Region requireRegion(String playerId, String worldSessionId, String selectionId) {
        if (!GameBridge.worldSessionId().equals(worldSessionId))
            throw new IllegalArgumentException("selection world session changed");
        UUID owner = uuid(playerId, "playerId");
        UUID revision = uuid(selectionId, "selectionId");
        ServerPlayer player = onlineHuman(owner);
        if (player == null) throw new IllegalArgumentException("selection owner is unavailable");
        Selection selection = SELECTIONS.get(owner);
        if (selection == null || !selection.id().equals(revision) || selection.level() != player.serverLevel())
            throw new IllegalArgumentException("selection changed or is unavailable in the current dimension");
        if (selection.a() == null || selection.b() == null)
            throw new IllegalArgumentException("select both region corners first");
        if (!selection.ready()) throw new IllegalArgumentException("selection corners are not loaded");
        return new Region(player, selection.a(), selection.b());
    }

    private static int select(CommandSourceStack source, Action action) {
        ServerPlayer player = nativePlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("[agent] 请由真人玩家在游戏内直接使用此命令"));
            return 0;
        }
        try {
            String message = switch (action) {
                case SHOW -> describe(snapshot(player.getUUID()));
                case CLEAR -> {
                    remove(player.getUUID());
                    yield "已清除选择";
                }
                default -> capture(player, action);
            };
            source.sendSuccess(() -> Component.literal("[agent] " + message), false);
            return 1;
        } catch (IllegalArgumentException error) {
            source.sendFailure(Component.literal("[agent] " + error.getMessage()));
            return 0;
        }
    }

    private static String capture(ServerPlayer player, Action action) {
        UUID owner = player.getUUID();
        ServerLevel level = player.serverLevel();
        Selection previous = SELECTIONS.get(owner);
        if (previous != null && previous.level() != level) previous = null;
        Target target = previous == null ? null : previous.target();
        BlockPos a = previous == null ? null : previous.a();
        BlockPos b = previous == null ? null : previous.b();
        String message;
        if (action == Action.ENTITY) {
            Ray ray = ray(player, player.entityInteractionRange());
            Vec3 end = ray.block().getType() == HitResult.Type.BLOCK ? ray.block().getLocation() : ray.end();
            var hit = ProjectileUtil.getEntityHitResult(player, ray.start(), end, ray.bounds(),
                    entity -> entity.isAlive() && entity.isPickable() && !entity.isSpectator(),
                    ray.start().distanceToSqr(end));
            if (hit == null) throw new IllegalArgumentException("请看向未被遮挡且在交互范围内的实体");
            Entity entity = hit.getEntity();
            if (!loaded(level, entity.blockPosition())) throw new IllegalArgumentException("目标所在区块尚未加载");
            target = new EntityTarget(entity.getUUID(), entity.getType());
            message = "已选中实体 " + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " " + entity.getUUID();
        } else {
            BlockHitResult hit = ray(player, player.blockInteractionRange()).block();
            if (hit.getType() != HitResult.Type.BLOCK)
                throw new IllegalArgumentException("请看向交互范围内的方块");
            BlockPos pos = hit.getBlockPos().immutable();
            if (!level.isInWorldBounds(pos) || !loaded(level, pos))
                throw new IllegalArgumentException("目标方块超出建造范围或所在区块尚未加载");
            if (action == Action.BLOCK) {
                Block block = level.getBlockState(pos).getBlock();
                BlockEntity be = level.getBlockEntity(pos);
                target = new BlockTarget(pos, block, be == null ? null : new WeakReference<>(be));
                message = "已选中方块 " + BuiltInRegistries.BLOCK.getKey(block) + " " + pos.toShortString();
            } else {
                if (action == Action.A) a = pos;
                else b = pos;
                message = "已标记区域角 " + action + " " + pos.toShortString();
            }
        }
        SELECTIONS.put(owner, new Selection(UUID.randomUUID(), level, target, a, b));
        return message;
    }

    private static Ray ray(ServerPlayer player, double interactionRange) {
        double reach = Math.min(MAX_REACH, interactionRange);
        if (!Double.isFinite(reach) || reach <= 0) throw new IllegalArgumentException("当前没有可用的交互范围");
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(reach));
        AABB bounds = new AABB(start, end).inflate(1.0);
        ServerLevel level = player.serverLevel();
        // Clip inspects block states. Check the short corridor before reading any of them.
        int minX = SectionPos.blockToSectionCoord(bounds.minX), maxX = SectionPos.blockToSectionCoord(bounds.maxX);
        int minZ = SectionPos.blockToSectionCoord(bounds.minZ), maxZ = SectionPos.blockToSectionCoord(bounds.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null)
                    throw new IllegalArgumentException("交互范围内有区块尚未加载");
            }
        }
        return new Ray(start, end, bounds, level.clip(
                new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)));
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ())) != null;
    }

    private static JsonObject snapshot(UUID owner) {
        JsonObject out = Json.obj();
        out.addProperty("playerId", owner.toString());
        out.addProperty("worldSessionId", GameBridge.worldSessionId());
        ServerPlayer player = onlineHuman(owner);
        if (player == null) return unavailable(out, "player_unavailable");
        out.addProperty("dimension", player.serverLevel().dimension().location().toString());
        Selection selection = SELECTIONS.get(owner);
        if (selection == null) {
            out.addProperty("status", "missing");
            return out;
        }
        if (selection.level() != player.serverLevel()) return unavailable(out, "dimension_changed");
        out.addProperty("selectionId", selection.id().toString());
        out.addProperty("status", "active");
        if (selection.target() != null) out.add("target", selection.target().snapshot(selection.level()));
        if (selection.a() != null || selection.b() != null) {
            JsonObject region = Json.obj();
            if (selection.a() != null) region.add("a", position(selection.a()));
            if (selection.b() != null) region.add("b", position(selection.b()));
            region.addProperty("ready", selection.ready());
            out.add("region", region);
        }
        return out;
    }

    private static String describe(JsonObject state) {
        if (!"active".equals(Json.str(state, "status", ""))) return "当前没有可用选择";
        StringBuilder message = new StringBuilder("当前选择位于 ").append(state.get("dimension").getAsString());
        if (state.has("target")) {
            JsonObject target = state.getAsJsonObject("target");
            if (target.has("block")) message.append("，方块 ").append(target.get("block").getAsString())
                    .append(' ').append(coords(target));
            else message.append("，实体 ").append(target.get("type").getAsString())
                    .append(' ').append(target.get("entityId").getAsString());
            if ("unavailable".equals(Json.str(target, "status", ""))) message.append("（目标已不可用）");
        }
        if (state.has("region")) {
            JsonObject region = state.getAsJsonObject("region");
            if (region.has("a")) message.append("，区域角 A ").append(coords(region.getAsJsonObject("a")));
            if (region.has("b")) message.append("，区域角 B ").append(coords(region.getAsJsonObject("b")));
            if (!Json.b(region, "ready", false)) message.append("（区域尚不可用）");
        }
        return message.toString();
    }

    private static String coords(JsonObject pos) {
        return pos.get("x").getAsInt() + ", " + pos.get("y").getAsInt() + ", " + pos.get("z").getAsInt();
    }

    private static JsonObject position(BlockPos pos) {
        JsonObject out = Json.obj();
        out.addProperty("x", pos.getX());
        out.addProperty("y", pos.getY());
        out.addProperty("z", pos.getZ());
        return out;
    }

    private static JsonObject unavailable(JsonObject out, String reason) {
        out.addProperty("status", "unavailable");
        out.addProperty("reason", reason);
        return out;
    }

    private static UUID uuid(String value, String field) {
        if (value == null || value.length() != 36) throw new IllegalArgumentException(field + " must be a UUID");
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(field + " must be a UUID", error);
        }
        if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(field + " must be a UUID");
        return parsed;
    }

    static ServerPlayer nativePlayer(CommandSourceStack source) {
        if (!GameBridge.running() || GameBridge.bridgeCommand()) return null;
        ServerPlayer player = source.getPlayer();
        // /execute as can change the selected entity but retains the command's actual source.
        return player != null && source.source == player && onlineHuman(player.getUUID()) == player ? player : null;
    }

    private static ServerPlayer onlineHuman(UUID id) {
        ServerPlayer player = GameBridge.server().getPlayerList().getPlayer(id);
        if (player == null || !player.isAlive() || player instanceof FakePlayer
                || FakePlayers.byName(player.getGameProfile().getName()) == player) return null;
        return player;
    }
}
