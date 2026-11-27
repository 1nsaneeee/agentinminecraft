package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Live channel: connected WebSockets get game events, and clients can send chat
 * lines and commands back into the game.
 *
 * <p>Events are topic-scoped so a noisy feed (every block placed in the world)
 * does not drown the channel the agent actually cares about (chat, machine
 * state). A client opts in with {@code {"type":"sub","topics":["blocks"]}}.</p>
 */
public final class WsHub {
    public static final String TOPIC_CHAT = "chat";
    public static final String TOPIC_EVENTS = "events";
    public static final String TOPIC_BLOCKS = "blocks";

    private final Object lifecycleLock = new Object();
    private final Set<WebSocketConn> sessions = new CopyOnWriteArraySet<>();
    private final Map<WebSocketConn, Set<String>> topics = new ConcurrentHashMap<>();
    private final Map<WebSocketConn, String> connectionIds = new ConcurrentHashMap<>();
    private final Map<WebSocketConn, Long> connectionGenerations = new ConcurrentHashMap<>();
    private long currentGeneration;

    public boolean connectionLive(String id) { return connectionIds.containsValue(id); }

    public long generation() {
        synchronized (lifecycleLock) {
            return currentGeneration;
        }
    }

    public int size() {
        return sessions.size();
    }

    public boolean hasSubscribers(String topic) {
        return topics.values().stream().anyMatch(subs -> subs.contains(topic));
    }

    public static void upgrade(HttpCore.Request req, HttpCore.Response res) throws java.io.IOException {
        upgrade(req, res, McAgent.WS.generation());
    }

    public static void upgrade(HttpCore.Request req, HttpCore.Response res, long acceptedGeneration) throws java.io.IOException {
        WebSocketConn.run(req, res, McAgent.WS.new ListenerImpl(acceptedGeneration));
    }

    public void closeAll() {
        List<WebSocketConn> owned;
        synchronized (lifecycleLock) {
            currentGeneration++;
            owned = new ArrayList<>(sessions);
            sessions.clear();
            topics.clear();
            connectionIds.clear();
            connectionGenerations.clear();
        }
        for (WebSocketConn ws : owned) ws.abort();
    }

    private final class ListenerImpl implements WebSocketConn.Listener {
        private final long acceptedGeneration;

        ListenerImpl() {
            this(WsHub.this.generation());
        }

        ListenerImpl(long acceptedGeneration) {
            this.acceptedGeneration = acceptedGeneration;
        }

        @Override
        public void onOpen(WebSocketConn ws) {
            Set<String> subs = ConcurrentHashMap.newKeySet();
            subs.add(TOPIC_CHAT);
            subs.add(TOPIC_EVENTS);
            String owner = java.util.UUID.randomUUID().toString();
            boolean revoked;
            synchronized (lifecycleLock) {
                revoked = acceptedGeneration != currentGeneration;
                if (!revoked) {
                    ws.setName("ws-" + Integer.toHexString(System.identityHashCode(ws)));
                    sessions.add(ws);
                    connectionIds.put(ws, owner);
                    connectionGenerations.put(ws, acceptedGeneration);
                    topics.put(ws, subs);
                }
            }
            if (revoked) {
                ws.abort();
                return;
            }
            McAgent.LOG.info("[mcagent] ws connected from {}", ws.remote());

            JsonObject hello = Json.obj();
            hello.addProperty("type", "hello");
            hello.addProperty("server", "mcagent");
            hello.addProperty("version", McAgent.VERSION);
            hello.add("topics", toArray(subs));
            hello.add("state", GameBridge.snapshot());
            ws.sendJson(hello);
        }

        @Override
        public void onMessage(WebSocketConn ws, String text) {
            Membership membership = membership(ws);
            if (membership == null) {
                ws.abort();
                return;
            }
            JsonObject msg;
            try {
                msg = Json.parseObject(text);
            } catch (Exception e) {
                ws.sendJson(error("malformed json"));
                return;
            }
            String type = Json.str(msg, "type", "");
            try {
                switch (type) {
                    case "ping" -> {
                        if (!isCurrent(membership)) {
                            ws.abort();
                            return;
                        }
                        JsonObject pong = Json.obj();
                        pong.addProperty("type", "pong");
                        pong.addProperty("t", System.currentTimeMillis());
                        ws.sendJson(pong);
                    }
                    case "sub", "unsub" -> {
                        Set<String> subs;
                        synchronized (lifecycleLock) {
                            requireCurrentLocked(membership);
                            subs = topics.get(ws);
                            if (subs == null) throw new StaleMembership();
                            JsonArray list = Json.subArr(msg, "topics");
                            if (list != null) {
                                for (JsonElement el : list) {
                                    String topic = el.getAsString();
                                    if ("sub".equals(type)) {
                                        subs.add(topic);
                                    } else {
                                        subs.remove(topic);
                                    }
                                }
                            }
                        }
                        JsonObject ack = Json.obj();
                        ack.addProperty("type", "subscribed");
                        ack.add("topics", toArray(subs));
                        ws.sendJson(ack);
                    }
                    case "say" -> {
                        String sayText = Json.str(msg, "text", "");
                        GameBridge.onServer(() -> {
                            requireCurrent(membership);
                            GameBridge.say(sayText);
                            return null;
                        }, 10000);
                    }
                    case "say_json" -> {
                        JsonObject component = Json.sub(msg, "component");
                        GameBridge.onServer(() -> {
                            requireCurrent(membership);
                            GameBridge.sayComponent(component);
                            return null;
                        }, 10000);
                    }
                    case "task_bind", "task_renew" -> {
                        String requestId = ExternalTaskLease.string(msg, "requestId", 96);
                        JsonObject reply;
                        try {
                            reply = GameBridge.onServer(() -> {
                                requireCurrent(membership);
                                return type.equals("task_bind")
                                        ? ExternalTasks.bind(membership.owner, msg)
                                        : ExternalTasks.renew(membership.owner, msg);
                            }, 5000);
                        } catch (RuntimeException error) {
                            reply = Json.obj(); reply.addProperty("isError", true); reply.addProperty("message", error.getMessage());
                        }
                        reply.addProperty("type", "task_result"); reply.addProperty("requestId", requestId); ws.sendJson(reply);
                    }
                    case "exec" -> {
                        String cmd = Json.str(msg, "command", "");
                        JsonObject reply = GameBridge.onServer(() -> {
                            requireCurrent(membership);
                            if (AgentMode.isChat()) {
                                return error("chat mode does not run commands; use /agent work first");
                            }
                            if (!AgentConfig.get().allowCommands) {
                                return error("commands disabled by config (allowCommands=false)");
                            }
                            String out = GameBridge.runCommand(cmd, Json.str(msg, "player", null), -1);
                            JsonObject result = Json.obj();
                            result.addProperty("type", "exec_result");
                            result.addProperty("command", cmd);
                            result.addProperty("output", out);
                            return result;
                        }, 30000);
                        ws.sendJson(reply);
                    }
                    case "state" -> {
                        JsonObject state = GameBridge.onServer(() -> {
                            requireCurrent(membership);
                            return GameBridge.snapshot();
                        }, 5000);
                        JsonObject reply = Json.obj();
                        reply.addProperty("type", "state");
                        reply.add("state", state);
                        ws.sendJson(reply);
                    }
                    default -> ws.sendJson(error("unknown type: " + type));
                }
            } catch (StaleMembership ignored) {
                ws.abort();
            } catch (Exception e) {
                McAgent.LOG.warn("[mcagent] ws command failed", e);
                ws.sendJson(error(e.toString()));
            }
        }

        @Override
        public void onClose(WebSocketConn ws) {
            String owner;
            synchronized (lifecycleLock) {
                sessions.remove(ws);
                topics.remove(ws);
                connectionGenerations.remove(ws);
                owner = connectionIds.remove(ws);
            }
            if (owner != null && GameBridge.running()) {
                try { GameBridge.onServer(() -> { ExternalTasks.ownerClosed(owner); return null; }, 5000); }
                catch (RuntimeException error) { McAgent.LOG.warn("[mcagent] task owner disconnect cleanup unavailable", error); }
            }
            McAgent.LOG.info("[mcagent] ws disconnected {}", ws.remote());
        }
    }

    /** Sends to every client subscribed to {@code topic}. */
    public void broadcast(String topic, JsonObject event) {
        if (sessions.isEmpty()) return;
        String text = event.toString();
        for (WebSocketConn ws : sessions) {
            Long connectionGeneration = connectionGenerations.get(ws);
            if (connectionGeneration == null || !isCurrent(ws, connectionGeneration)) continue;
            Set<String> subs = topics.get(ws);
            if (subs == null || !subs.contains(topic)) continue;
            try {
                ws.send(text);
            } catch (RuntimeException e) {
                // Let the normal onClose path remove every map and revoke its
                // owner. Dropping only the subscriber leaves a live socket behind.
                ws.abort();
            }
        }
    }

    private Membership membership(WebSocketConn ws) {
        synchronized (lifecycleLock) {
            String owner = connectionIds.get(ws);
            Long connectionGeneration = connectionGenerations.get(ws);
            if (owner == null || connectionGeneration == null
                    || connectionGeneration != currentGeneration || !sessions.contains(ws)) {
                return null;
            }
            return new Membership(ws, owner, connectionGeneration);
        }
    }

    private boolean isCurrent(Membership membership) {
        synchronized (lifecycleLock) {
            return isCurrentLocked(membership);
        }
    }

    private boolean isCurrent(WebSocketConn ws, long connectionGeneration) {
        synchronized (lifecycleLock) {
            Long accepted = connectionGenerations.get(ws);
            return accepted != null && accepted == connectionGeneration && accepted == currentGeneration
                    && sessions.contains(ws) && connectionIds.containsKey(ws);
        }
    }

    private boolean isCurrentLocked(Membership membership) {
        Long accepted = connectionGenerations.get(membership.ws);
        return accepted != null && accepted == membership.generation && accepted == currentGeneration
                && sessions.contains(membership.ws)
                && Objects.equals(connectionIds.get(membership.ws), membership.owner);
    }

    private void requireCurrent(Membership membership) {
        synchronized (lifecycleLock) {
            requireCurrentLocked(membership);
        }
    }

    private void requireCurrentLocked(Membership membership) {
        if (!isCurrentLocked(membership)) throw new StaleMembership();
    }

    private record Membership(WebSocketConn ws, String owner, long generation) {}

    private static final class StaleMembership extends RuntimeException {}

    /** Player chat (or a system line) pushed to every live client. */
    public void pushChat(String player, String message, boolean system) {
        JsonObject o = Json.obj();
        o.addProperty("type", "chat");
        o.addProperty("player", player);
        o.addProperty("text", message);
        o.addProperty("system", system);
        o.addProperty("t", System.currentTimeMillis());
        broadcast(TOPIC_CHAT, o);
    }

    public void pushEvent(String kind, JsonObject data) {
        JsonObject o = Json.obj();
        o.addProperty("type", "event");
        o.addProperty("kind", kind);
        if (data != null) o.add("data", data);
        o.addProperty("t", System.currentTimeMillis());
        broadcast(TOPIC_EVENTS, o);
    }

    /** Topic-scoped event, e.g. machine state changes on the "blocks" topic. */
    public void pushTopic(String topic, String kind, JsonObject data) {
        JsonObject o = Json.obj();
        o.addProperty("type", kind);
        if (data != null) o.add("data", data);
        o.addProperty("t", System.currentTimeMillis());
        broadcast(topic, o);
    }

    private static JsonArray toArray(Set<String> values) {
        JsonArray arr = Json.arr();
        values.forEach(arr::add);
        return arr;
    }

    private static JsonObject error(String message) {
        JsonObject o = Json.obj();
        o.addProperty("type", "error");
        o.addProperty("message", message);
        return o;
    }

    static List<String> knownTopics() {
        return List.of(TOPIC_CHAT, TOPIC_EVENTS, TOPIC_BLOCKS);
    }
}
