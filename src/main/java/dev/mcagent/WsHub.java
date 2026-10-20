package dev.mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
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

    private final Set<WebSocketConn> sessions = new CopyOnWriteArraySet<>();
    private final Map<WebSocketConn, Set<String>> topics = new ConcurrentHashMap<>();

    public int size() {
        return sessions.size();
    }

    public static void upgrade(HttpCore.Request req, HttpCore.Response res) throws java.io.IOException {
        WebSocketConn.run(req, res, McAgent.WS.new ListenerImpl());
    }

    private final class ListenerImpl implements WebSocketConn.Listener {
        @Override
        public void onOpen(WebSocketConn ws) {
            ws.setName("ws-" + Integer.toHexString(System.identityHashCode(ws)));
            sessions.add(ws);
            Set<String> subs = ConcurrentHashMap.newKeySet();
            subs.add(TOPIC_CHAT);
            subs.add(TOPIC_EVENTS);
            topics.put(ws, subs);
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
                        JsonObject pong = Json.obj();
                        pong.addProperty("type", "pong");
                        pong.addProperty("t", System.currentTimeMillis());
                        ws.sendJson(pong);
                    }
                    case "sub", "unsub" -> {
                        Set<String> subs = topics.computeIfAbsent(ws, k -> ConcurrentHashMap.newKeySet());
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
                        JsonObject ack = Json.obj();
                        ack.addProperty("type", "subscribed");
                        ack.add("topics", toArray(subs));
                        ws.sendJson(ack);
                    }
                    case "say" -> GameBridge.say(Json.str(msg, "text", ""));
                    case "say_json" -> GameBridge.sayComponent(Json.sub(msg, "component"));
                    case "exec" -> {
                        String cmd = Json.str(msg, "command", "");
                        if (!AgentConfig.get().allowCommands) {
                            ws.sendJson(error("commands disabled by config (allowCommands=false)"));
                        } else {
                            String out = GameBridge.runCommand(cmd, Json.str(msg, "player", null), -1);
                            JsonObject reply = Json.obj();
                            reply.addProperty("type", "exec_result");
                            reply.addProperty("command", cmd);
                            reply.addProperty("output", out);
                            ws.sendJson(reply);
                        }
                    }
                    case "state" -> {
                        JsonObject reply = Json.obj();
                        reply.addProperty("type", "state");
                        reply.add("state", GameBridge.snapshot());
                        ws.sendJson(reply);
                    }
                    default -> ws.sendJson(error("unknown type: " + type));
                }
            } catch (Exception e) {
                McAgent.LOG.warn("[mcagent] ws command failed", e);
                ws.sendJson(error(e.toString()));
            }
        }

        @Override
        public void onClose(WebSocketConn ws) {
            sessions.remove(ws);
            topics.remove(ws);
            McAgent.LOG.info("[mcagent] ws disconnected {}", ws.remote());
        }
    }

    /** Sends to every client subscribed to {@code topic}. */
    public void broadcast(String topic, JsonObject event) {
        if (sessions.isEmpty()) return;
        String text = event.toString();
        for (WebSocketConn ws : sessions) {
            Set<String> subs = topics.get(ws);
            if (subs == null || !subs.contains(topic)) continue;
            try {
                ws.send(text);
            } catch (RuntimeException e) {
                sessions.remove(ws);
                topics.remove(ws);
            }
        }
    }

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
