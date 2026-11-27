package dev.mcagent;

import dev.mcagent.util.TpsTracker;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
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
            try {
                // World entry also reloads the file. Menu-era connections cannot
                // keep old authentication if its token or bind settings changed.
                McAgent.reloadConfig();
            } catch (IllegalStateException error) {
                McAgent.LOG.warn("[mcagent] world-entry config reload refused: {}", error.getMessage());
                if (McAgent.running()) AgentDiscovery.publish();
                else AgentDiscovery.withdraw();
            }
        });

        bus.addListener((ServerStoppingEvent event) -> {
            EditJobs.stopAll("world_stopping");
            UndoJobs.stopAll("world_stopping");
            BlockObservations.stopAll("world_stopping");
            try {
                dev.mcagent.fakeplayer.AgentMovement.stopAll(event.getServer());
                dev.mcagent.fakeplayer.AgentActions.cancelAll(event.getServer());
                dev.mcagent.fakeplayer.FakePlayers.despawnAll(event.getServer());
            } finally {
                AgentDiscovery.withdraw();
                EditHistory.clear();
                Watchers.clear();
                Highlights.clear();
                GameBridge.detach();
            }
        });

        bus.addListener((ServerTickEvent.Post event) -> {
            ExternalTasks.tick();
            if (AgentControl.active()) {
                EditJobs.tick(event.getServer());
                UndoJobs.tick(event.getServer());
            }
            BlockObservations.tick(event.getServer());
            if (AgentControl.active()) {
                dev.mcagent.fakeplayer.AgentMovement.tick(event.getServer());
                dev.mcagent.fakeplayer.AgentFollow.tick(event.getServer());
            }
            TpsTracker.onTick();
            Highlights.tick(event.getServer());
            Watchers.tick(event.getServer());
        });

        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            PlayerSelections.remove(event.getEntity().getUUID());
        });

        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            PlayerSelections.remove(event.getEntity().getUUID());
        });
    }
}
