package dev.mcagent;

import dev.mcagent.util.TpsTracker;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Mod entry point. The HTTP bridge comes up during mod construction so the
 * agent can already talk to the game from the main menu; game-state tools
 * report "no server running" until a world is loaded.
 */
@Mod(McAgent.MOD_ID)
public final class McAgentMod {

    public McAgentMod(IEventBus modEventBus, ModContainer container) {
        try {
            AgentConfig.load(dev.mcagent.util.Dist2.configDir());
            McAgent.LOG.info("[mcagent] v{} starting on {}", McAgent.VERSION, dev.mcagent.util.Dist2.string());
            McAgent.startHttp();
        } catch (Throwable t) {
            // A broken bridge must never prevent the game itself from starting.
            McAgent.LOG.error("[mcagent] failed to start the HTTP bridge; the game will still load", t);
        }

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(AgentCommands::register);

        bus.addListener((ServerStartedEvent event) -> {
            GameBridge.attach(event.getServer());
            McAgent.LOG.info("[mcagent] world loaded: {}", event.getServer().overworld().dimension().location());
            AgentConfig.load(dev.mcagent.util.Dist2.configDir());
            McAgent.WS.pushEvent("server_started", GameBridge.snapshot());
        });

        bus.addListener((ServerStoppingEvent event) -> {
            McAgent.WS.pushEvent("server_stopping", null);
            GameBridge.detach();
        });

        bus.addListener((ServerTickEvent.Post event) -> {
            net.minecraft.server.MinecraftServer walking = GameBridge.server();
            if (walking != null) {
                dev.mcagent.fakeplayer.AgentMovement.tick(walking);
            }
            TpsTracker.onTick();
            Highlights.tick(event.getServer());
            Watchers.tick(event.getServer());
        });

        // Only blocks that carry a block entity are pushed: a wall of
        // interesting events, not every dirt block the player places.
        bus.addListener((net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) -> {
            if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
            if (!event.getPlacedBlock().hasBlockEntity()) return;
            com.google.gson.JsonObject data = Json.obj();
            data.addProperty("pos", event.getPos().getX() + "," + event.getPos().getY() + "," + event.getPos().getZ());
            data.addProperty("block", String.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock())));
            data.addProperty("dimension", level.dimension().location().toString());
            McAgent.WS.pushTopic(WsHub.TOPIC_BLOCKS, "block_place", data);
        });

        bus.addListener((net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) -> {
            if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
            if (!event.getState().hasBlockEntity()) return;
            com.google.gson.JsonObject data = Json.obj();
            data.addProperty("pos", event.getPos().getX() + "," + event.getPos().getY() + "," + event.getPos().getZ());
            data.addProperty("block", String.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(event.getState().getBlock())));
            data.addProperty("dimension", level.dimension().location().toString());
            data.addProperty("player", event.getPlayer() == null ? null : event.getPlayer().getName().getString());
            McAgent.WS.pushTopic(WsHub.TOPIC_BLOCKS, "block_break", data);
        });

        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            ChatLog.add(null, event.getEntity().getName().getString() + " joined", true);
            McAgent.WS.pushEvent("player_join", GameBridge.snapshot());
            // The greeting belongs here, not in ServerStartedEvent: on an
            // integrated server the local player is added to the list only
            // after the server has started.
            // Only greet a human. The agent's own players log in through the same event,
            // and telling them the bridge is ready is just noise.
            if (AgentConfig.get().greetOnJoin && McAgent.running()
                    && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                    && !dev.mcagent.fakeplayer.FakePlayers.names().contains(
                            player.getGameProfile().getName())) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[mcagent] 桥接已就绪 · 127.0.0.1:" + McAgent.port() + " · MCP + WS 在线"));
            }
        });

        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            ChatLog.add(null, event.getEntity().getName().getString() + " left", true);
            McAgent.WS.pushEvent("player_leave", GameBridge.snapshot());
        });

        bus.addListener((ServerChatEvent event) -> {
            String player = event.getUsername();
            String raw = event.getRawText();
            ChatLog.add(player, raw, false);
            McAgent.WS.pushChat(player, raw, false);
        });
    }
}
