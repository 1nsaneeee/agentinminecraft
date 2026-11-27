package dev.mcagent;

import com.google.gson.JsonObject;
import dev.mcagent.fakeplayer.AgentActions;
import dev.mcagent.fakeplayer.AgentFollow;
import dev.mcagent.fakeplayer.AgentIdle;
import dev.mcagent.fakeplayer.AgentMovement;
import dev.mcagent.fakeplayer.FakePlayers;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Server-thread-owned observations and the single companion's control boundary. */
public final class CompanionRuntime {
    public static final String TOPIC = "companion";
    private static CompanionLease lease;
    private static long sequence;
    private static String bodyName;
    private static AgentMode.Mode lastMode;
    private static float lastHealth;
    private static CompanionMemory memory;
    private static JsonObject remembered;
    private static JsonObject intent;
    private static String persistenceError;

    private CompanionRuntime() {}
    private static long now() { return System.nanoTime() / 1_000_000; }

    public static void attach() {
        lease = new CompanionLease(UUID.randomUUID().toString());
        sequence = 0;
        bodyName = null;
        lastMode = AgentMode.get();
        lastHealth = Float.NaN;
        ActionJournal.clear();
        memory = null;
        remembered = intent = null;
        persistenceError = null;
        try {
            memory = new CompanionMemory(GameBridge.server().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
            remembered = memory.read();
        } catch (Exception e) {
            persistenceError = "companion memory could not be loaded; preserve the file and repair before rebinding";
            McAgent.LOG.error("[mcagent] companion persistence unavailable", e);
        }
    }

    public static void detach() {
        stopBody();
        lease = null;
        bodyName = null;
        ActionJournal.clear();
        memory = null;
        remembered = intent = null;
        persistenceError = null;
    }

    public static void bind(ServerPlayer human, String name) {
        AgentControl.requireActive(AgentControl.epoch());
        requireStorage();
        if (!AgentMode.isChat()) throw new IllegalStateException("companion binding requires Chat mode");
        ServerPlayer body = FakePlayers.byName(name);
        if (body == null || FakePlayers.byName(human.getGameProfile().getName()) != null)
            throw new IllegalArgumentException("bind an existing agent body to a human player");
        if (body.level() != human.level()) throw new IllegalStateException("body and player must be in the same dimension");
        stopBody();
        bodyName = name;
        lease.bind(body.getUUID().toString(), human.getUUID().toString());
        boolean restoring = remembered != null && body.getUUID().toString().equals(Json.str(remembered, "bodyId", null))
                && human.getUUID().toString().equals(Json.str(remembered, "targetId", null));
        intent = restoring && remembered.has("intent") ? remembered.getAsJsonObject("intent").deepCopy() : null;
        if (restoring) lease.pause("restored_state_needs_review");
        lastMode = AgentMode.get();
        stopBody();
        lastHealth = body.getHealth();
        if (!persist()) throw new IllegalStateException(persistenceError);
        publish("bound");
    }

    public static void pause(String reason) {
        if (lease == null || !lease.bound()) return;
        stopBody();
        lease.pause(reason);
        persist();
        publish(reason);
    }

    public static void resume() {
        AgentControl.requireActive(AgentControl.epoch());
        requireStorage();
        if (!AgentMode.isChat()) throw new IllegalStateException("resume requires Chat mode");
        requireParticipants();
        stopBody();
        lease.resume();
        lastMode = AgentMode.get();
        if (!persist()) throw new IllegalStateException(persistenceError);
        publish("resumed");
    }

    public static void release() {
        requireStorage();
        stopBody();
        if (lease != null) lease.release();
        bodyName = null;
        intent = null;
        if (!persist()) throw new IllegalStateException(persistenceError);
        publish("released");
    }

    public static void onHumanChat(ServerPlayer human, String message) {
        if (lease == null || !lease.bound() || !AgentMode.isChat()
                || !human.getUUID().toString().equals(lease.targetId())) return;
        String action = CompanionChatControl.actionFor(message);
        if (action == null) return;
        try {
            if (action.equals("pause")) pause("human_paused");
            else if (lease.paused()) resume();
        } catch (IllegalStateException e) {
            human.sendSystemMessage(net.minecraft.network.chat.Component.literal("[agent] " + e.getMessage()));
        }
    }

    private static void requireParticipants() {
        if (lease == null || !lease.bound()) throw new IllegalStateException("no companion bound");
        ServerPlayer body = FakePlayers.byName(bodyName);
        ServerPlayer target = GameBridge.server().getPlayerList().getPlayer(UUID.fromString(lease.targetId()));
        if (body == null || !body.getUUID().toString().equals(lease.bodyId()) || !body.isAlive()
                || target == null || !target.isAlive() || target.level() != body.level())
            throw new IllegalStateException("companion body/target missing, dead or in another dimension");
    }

    /** Called before body ticks and also before accepting a tool action. */
    private static void reconcile() {
        if (lease == null) return;
        if (lastMode != AgentMode.get()) {
            lastMode = AgentMode.get();
            pause("mode_changed");
        }
        if (lease.expire(now())) {
            stopBody();
            persist();
            publish("lease_expired");
        }
        if (lease.bound() && !lease.paused()) {
            if (!AgentConfig.get().allowWrite) { pause("writes_disabled"); return; }
            try { requireParticipants(); }
            catch (IllegalStateException e) { pause("participants_unavailable"); }
        }
    }

    public static void tick(MinecraftServer server) {
        reconcile();
        if (lease == null || !lease.bound() || server.getTickCount() % 20 != 0) return;
        ServerPlayer body = FakePlayers.byName(bodyName);
        if (body != null) {
            if (!Float.isNaN(lastHealth) && body.getHealth() < lastHealth) publish("body_hurt");
            lastHealth = body.getHealth();
        }
        if (McAgent.WS.hasSubscribers(TOPIC)) McAgent.WS.pushTopic(TOPIC, "companion_state", snapshot());
    }

    public static boolean blocked(ServerPlayer body) {
        return persistenceError != null || lease != null && body.getUUID().toString().equals(lease.bodyId())
                && (!AgentMode.isChat() || !lease.active(now()));
    }

    private static void stopBody() {
        if (bodyName == null) return;
        ServerPlayer body = FakePlayers.byName(bodyName);
        if (body == null) return;
        AgentFollow.stop(body);
        AgentMovement.stop(body);
        AgentActions.cancelBreak(body);
        body.stopUsingItem();
        AgentIdle.cancel(body);
        body.xxa = body.zza = 0;
        body.setJumping(false);
        body.setSprinting(false);
    }

    public static void guard(String tool, JsonObject args) {
        requireStorage();
        reconcile();
        String name = tool.equals("mc_player_spawn") ? AgentConfig.get().resolvedPlayerName() : Json.str(args, "name", "");
        boolean carriesControl = args.has("controlEpoch") || args.has("worldSessionId") || args.has("controllerId");
        if (lease == null || !lease.bound() || !name.equals(bodyName)) {
            // Never reinterpret a late managed request as an unowned legacy request after release/world change.
            if (carriesControl) throw new IllegalStateException("companion binding no longer matches this request");
            return;
        }
        if (!AgentMode.isChat()) throw new IllegalStateException("companion suspended in Work; human must release binding to use legacy body control");
        lease.validate(Json.str(args, "worldSessionId", null), Json.str(args, "controlEpoch", null),
                Json.str(args, "controllerId", null), now());
        if (tool.equals("mc_player_follow")) {
            ServerPlayer target = GameBridge.server().getPlayerList().getPlayer(UUID.fromString(lease.targetId()));
            String requested = Json.str(args, "target", "");
            if (!requested.isEmpty() && !requested.equals(target.getGameProfile().getName()))
                throw new IllegalStateException("follow target must be the bound companion player");
            args.addProperty("target", target.getGameProfile().getName());
        }
    }

    public static JsonObject control(JsonObject args) {
        requireStorage();
        reconcile();
        if (lease == null) throw new IllegalStateException("no active world");
        String action = Json.str(args, "action", "claim");
        String world = Json.str(args, "worldSessionId", null);
        String epoch = Json.str(args, "controlEpoch", null);
        String owner = Json.str(args, "controllerId", null);
        if (!AgentMode.isChat()) throw new IllegalStateException("companion control requires Chat mode");
        requireParticipants();
        switch (action) {
            case "claim", "renew" -> {
                if (action.equals("renew")) lease.validate(world, epoch, owner, now());
                lease.claim(world, epoch, owner, now());
            }
            case "pause" -> {
                lease.validate(world, epoch, owner, now());
                pause("controller_paused");
            }
            default -> throw new IllegalArgumentException("action must be claim, renew or pause; resume is a human game command");
        }
        return snapshot();
    }

    private static void requireStorage() {
        if (memory == null || persistenceError != null) throw new IllegalStateException(
                persistenceError == null ? "companion storage is not initialized" : persistenceError);
    }

    private static boolean persist() {
        if (memory == null || persistenceError != null || lease == null) return false;
        try {
            JsonObject record = memory.record(lease.bodyId(), bodyName, lease.targetId(), lease.paused(), intent);
            memory.write(record);
            remembered = record;
            return true;
        } catch (Exception e) {
            persistenceError = "companion memory write failed; control paused until storage is repaired";
            stopBody();
            lease.pause("persistence_failed");
            McAgent.LOG.error("[mcagent] companion state was not saved", e);
            return false;
        }
    }

    public static JsonObject updateIntent(JsonObject args) {
        requireStorage();
        reconcile();
        if (!AgentMode.isChat()) throw new IllegalStateException("companion intent requires Chat mode");
        lease.validate(Json.str(args, "worldSessionId", null), Json.str(args, "controlEpoch", null),
                Json.str(args, "controllerId", null), now());
        String operation = Json.str(args, "operation", "update");
        JsonObject next;
        if (operation.equals("start")) {
            if (intent != null && java.util.Set.of("planned", "active", "waiting").contains(Json.str(intent, "status", "")))
                throw new IllegalStateException("finish or cancel the current intent before replacing it");
            next = Json.obj();
            next.addProperty("intentId", UUID.randomUUID().toString());
            next.addProperty("status", "planned");
            next.addProperty("goal", Json.str(args, "goal", ""));
            next.addProperty("reason", ""); next.addProperty("progress", ""); next.addProperty("stopConditions", "");
        } else if (operation.equals("update")) {
            if (intent == null || !Json.str(intent, "intentId", "").equals(Json.str(args, "intentId", null)))
                throw new IllegalStateException("intentId does not match current intent");
            if (java.util.Set.of("completed", "cancelled").contains(Json.str(intent, "status", "")))
                throw new IllegalStateException("terminal intent cannot be revived; start a new intent");
            if (args.has("goal") && !args.get("goal").equals(intent.get("goal")))
                throw new IllegalStateException("cancel current intent before changing its goal");
            next = intent.deepCopy();
        } else throw new IllegalArgumentException("operation must be start or update");
        for (String field : java.util.List.of("goal", "reason", "progress", "stopConditions", "status")) {
            if (args.has(field)) next.add(field, args.get(field).deepCopy());
        }
        next.addProperty("reportedBy", "agent");
        CompanionMemory.validateIntent(next);
        JsonObject previous = intent;
        intent = next;
        if (!persist()) { intent = previous; throw new IllegalStateException(persistenceError); }
        publish("intent_updated");
        return snapshot();
    }

    public static void actionFinished(String id) {
        JsonObject result = ActionJournal.get(id);
        if (lease == null || !lease.bound() || !result.has("bodyId")
                || !lease.bodyId().equals(result.get("bodyId").getAsString())) return;
        JsonObject event = snapshot();
        event.addProperty("kind", "action_finished");
        event.addProperty("eventId", lease.worldSessionId() + ":" + sequence);
        event.add("action", result);
        McAgent.WS.pushTopic(TOPIC, "companion_event", event);
    }

    private static void publish(String kind) {
        if (lease == null) return;
        JsonObject event = snapshot();
        event.addProperty("kind", kind);
        event.addProperty("eventId", lease.worldSessionId() + ":" + sequence);
        McAgent.WS.pushTopic(TOPIC, "companion_event", event);
    }

    public static JsonObject snapshot() {
        JsonObject state = Json.obj();
        state.addProperty("schemaVersion", 1);
        if (memory != null) state.addProperty("saveId", memory.saveId());
        if (persistenceError != null) state.addProperty("persistenceError", persistenceError);
        if (intent != null) state.add("intent", intent.deepCopy());
        else if (remembered != null && remembered.has("bodyId") && (lease == null || !lease.bound())) {
            state.add("rememberedBinding", remembered.deepCopy());
        }
        if (lease == null) { state.addProperty("bound", false); return state; }
        state.addProperty("worldSessionId", lease.worldSessionId());
        state.addProperty("controlEpoch", lease.epoch());
        state.addProperty("sequence", ++sequence);
        state.addProperty("mode", AgentMode.id());
        state.addProperty("bound", lease.bound());
        state.addProperty("paused", lease.paused());
        state.addProperty("reason", lease.reason());
        state.addProperty("controllerId", lease.owner());
        state.addProperty("leaseRemainingMs", lease.remainingMs(now()));
        state.addProperty("active", lease.active(now()) && AgentMode.isChat());
        if (lease.bound()) {
            state.addProperty("bodyName", bodyName);
            state.addProperty("bodyId", lease.bodyId());
            state.addProperty("targetId", lease.targetId());
            ServerPlayer body = FakePlayers.byName(bodyName);
            ServerPlayer target = GameBridge.server().getPlayerList().getPlayer(UUID.fromString(lease.targetId()));
            if (body != null) {
                state.add("body", describe(body));
                String actionId = AgentMovement.actionId(body);
                state.addProperty("walking", actionId != null && ActionJournal.get(actionId).get("status").getAsString().equals("running"));
                state.addProperty("following", AgentFollow.isFollowing(body));
                state.addProperty("breaking", AgentActions.isBreaking(body));
                state.addProperty("usingItem", body.isUsingItem());
                if (actionId != null) state.add("action", ActionJournal.get(actionId));
            }
            if (target != null) state.add("target", describe(target));
        }
        return state;
    }

    private static JsonObject describe(ServerPlayer p) {
        JsonObject out = Json.obj();
        out.addProperty("name", p.getGameProfile().getName());
        out.addProperty("uuid", p.getUUID().toString());
        out.addProperty("dimension", p.level().dimension().location().toString());
        out.addProperty("x", p.getX()); out.addProperty("y", p.getY()); out.addProperty("z", p.getZ());
        out.addProperty("health", p.getHealth());
        out.addProperty("food", p.getFoodData().getFoodLevel());
        return out;
    }
}
