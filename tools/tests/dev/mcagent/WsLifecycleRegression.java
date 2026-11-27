package dev.mcagent;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class WsLifecycleRegression {
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        WsHub hub = McAgent.WS;
        AgentMode.set(AgentMode.Mode.WORK);
        AgentConfig.get().allowCommands = true;
        hub.closeAll();
        GameBridge.resetLifecycleHarness();
        try {
            check("real WebSocket communicates before closeAll and closes afterward",
                    () -> communicatesBeforeCloseAndClosesAfter(hub));
            check("new-generation WebSocket still communicates", () -> newGenerationStillWorks(hub));
            check("stale accepted-generation handshake is refused", () -> staleHandshakeIsRefused(hub));
            check("queued mutation cannot run after closeAll", () -> queuedMutationIsInvalidated(hub));
            check("abort releases a blocked WebSocket read", WsLifecycleRegression::abortReleasesBlockedRead);
            check("normal remote close invokes onClose once", WsLifecycleRegression::normalRemoteCloseIsOnce);
            check("failed broadcast closes the socket and removes all membership", WsLifecycleRegression::failedBroadcastReleasesMembership);
        } finally {
            GameBridge.resetLifecycleHarness();
            hub.closeAll();
            AgentMode.set(AgentMode.Mode.WORK);
            AgentConfig.get().allowCommands = true;
        }
        if (failures > 0) throw new AssertionError(failures + " websocket lifecycle regression(s) failed");
        System.out.println("PASS: " + checks + " websocket lifecycle regressions");
    }

    private static boolean communicatesBeforeCloseAndClosesAfter(WsHub hub) throws Exception {
        long generation = hub.generation();
        try (HttpCore core = startWsServer(generation); Socket client = openWebSocket(core.port())) {
            if (!"hello".equals(readJson(client).get("type").getAsString())) return false;
            sendText(client, "{\"type\":\"ping\"}");
            if (!"pong".equals(readJson(client).get("type").getAsString())) return false;

            hub.closeAll();
            return hub.generation() == generation + 1 && hub.size() == 0
                    && !hub.hasSubscribers(WsHub.TOPIC_CHAT) && closesWithoutText(client);
        }
    }

    private static boolean newGenerationStillWorks(WsHub hub) throws Exception {
        hub.closeAll();
        long generation = hub.generation();
        try (HttpCore core = startWsServer(generation); Socket client = openWebSocket(core.port())) {
            if (!"hello".equals(readJson(client).get("type").getAsString())) return false;
            sendText(client, "{\"type\":\"ping\"}");
            return "pong".equals(readJson(client).get("type").getAsString()) && hub.size() == 1;
        }
    }

    private static boolean staleHandshakeIsRefused(WsHub hub) throws Exception {
        long staleGeneration = hub.generation();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService clientWorker = Executors.newSingleThreadExecutor();
        Future<Socket> opening = null;
        try (HttpCore core = new HttpCore("127.0.0.1", 0)) {
            core.route("GET", "/ws", (request, response) -> {
                entered.countDown();
                if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("stale handshake was not released");
                WsHub.upgrade(request, response, staleGeneration);
            });
            core.start();
            opening = clientWorker.submit(() -> openWebSocket(core.port()));
            if (!entered.await(2, TimeUnit.SECONDS)) return false;
            hub.closeAll();
            release.countDown();
            try (Socket stale = opening.get(2, TimeUnit.SECONDS)) {
                return hub.size() == 0 && closesWithoutText(stale);
            }
        } finally {
            release.countDown();
            if (opening != null) opening.cancel(true);
            clientWorker.shutdownNow();
        }
    }

    private static boolean queuedMutationIsInvalidated(WsHub hub) throws Exception {
        GameBridge.resetLifecycleHarness();
        long generation = hub.generation();
        try (HttpCore core = startWsServer(generation); Socket client = openWebSocket(core.port())) {
            if (!"hello".equals(readJson(client).get("type").getAsString())) return false;
            GameBridge.deferServerTasks();
            sendText(client, "{\"type\":\"say\",\"text\":\"must not run\"}");
            if (!GameBridge.awaitDeferredTask(2, TimeUnit.SECONDS)) return false;

            hub.closeAll();
            if (hub.size() != 0) return false;
            int drained = GameBridge.drainDeferredTasks();
            return drained == 1 && GameBridge.sayCalls() == 0 && closesWithoutText(client);
        } finally {
            GameBridge.resetLifecycleHarness();
        }
    }

    private static boolean abortReleasesBlockedRead() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch messageSeen = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<WebSocketConn> connection = new AtomicReference<>();
        try (HttpCore core = new HttpCore("127.0.0.1", 0)) {
            core.route("GET", "/ws", (request, response) -> {
                try {
                    WebSocketConn.run(request, response, new WebSocketConn.Listener() {
                        public void onOpen(WebSocketConn ws) {
                            connection.set(ws);
                            opened.countDown();
                        }

                        public void onMessage(WebSocketConn ws, String text) { messageSeen.countDown(); }

                        public void onClose(WebSocketConn ws) { }
                    });
                } finally {
                    finished.countDown();
                }
            });
            core.start();
            try (Socket client = openWebSocket(core.port())) {
                if (!opened.await(2, TimeUnit.SECONDS)) return false;
                sendText(client, "reading");
                if (!messageSeen.await(2, TimeUnit.SECONDS) || connection.get() == null) return false;
                connection.get().abort();
                return finished.await(2, TimeUnit.SECONDS) && closesWithoutText(client);
            }
        }
    }

    private static boolean normalRemoteCloseIsOnce() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        try (HttpCore core = new HttpCore("127.0.0.1", 0)) {
            core.route("GET", "/ws", (request, response) -> {
                try {
                    WebSocketConn.run(request, response, new WebSocketConn.Listener() {
                        public void onOpen(WebSocketConn ws) { opened.countDown(); }

                        public void onMessage(WebSocketConn ws, String text) { }

                        public void onClose(WebSocketConn ws) {
                            closeCalls.incrementAndGet();
                            closed.countDown();
                        }
                    });
                } finally {
                    finished.countDown();
                }
            });
            core.start();
            try (Socket client = openWebSocket(core.port())) {
                if (!opened.await(2, TimeUnit.SECONDS)) return false;
                client.close();
                return closed.await(2, TimeUnit.SECONDS) && finished.await(2, TimeUnit.SECONDS)
                        && closeCalls.get() == 1;
            }
        }
    }

    private static boolean failedBroadcastReleasesMembership() throws Exception {
        WsHub hub = McAgent.WS;
        CountDownLatch finished = new CountDownLatch(1);
        long generation = hub.generation();
        try (HttpCore core = new HttpCore("127.0.0.1", 0)) {
            core.route("GET", "/ws", (request, response) -> {
                try { WsHub.upgrade(request, response, generation); }
                finally { finished.countDown(); }
            });
            core.start();
            try (Socket client = openWebSocket(core.port())) {
                if (!"hello".equals(readJson(client).get("type").getAsString())) return false;
                JsonObject oversized = Json.obj();
                oversized.addProperty("text", "x".repeat(4 * 1024 * 1024));
                hub.broadcast(WsHub.TOPIC_CHAT, oversized);
                return closesWithoutText(client) && finished.await(2, TimeUnit.SECONDS)
                        && hub.size() == 0 && !hub.hasSubscribers(WsHub.TOPIC_CHAT);
            }
        }
    }

    private static HttpCore startWsServer(long acceptedGeneration) throws IOException {
        HttpCore core = new HttpCore("127.0.0.1", 0);
        core.route("GET", "/ws", (request, response) -> WsHub.upgrade(request, response, acceptedGeneration));
        core.start();
        return core;
    }

    private static Socket openWebSocket(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(2000);
        socket.getOutputStream().write(("GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int value = socket.getInputStream().read();
            if (value < 0) throw new EOFException("WebSocket handshake ended early");
            headers.write(value);
            state = value == (state % 2 == 0 ? '\r' : '\n') ? state + 1 : value == '\r' ? 1 : 0;
            if (headers.size() > 8192) throw new IOException("WebSocket handshake is oversized");
        }
        String response = headers.toString(StandardCharsets.US_ASCII);
        if (!response.startsWith("HTTP/1.1 101 ") || !response.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")) {
            socket.close();
            throw new IOException("WebSocket handshake was rejected");
        }
        return socket;
    }

    private static void sendText(Socket socket, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = { 7, 3, 5, 1 };
        if (payload.length >= 126) throw new IllegalArgumentException("test frame is too large");
        OutputStream out = socket.getOutputStream();
        out.write(0x81);
        out.write(0x80 | payload.length);
        out.write(mask);
        for (int index = 0; index < payload.length; index++) out.write(payload[index] ^ mask[index & 3]);
        out.flush();
    }

    private static JsonObject readJson(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int opcode = in.readUnsignedByte();
        if ((opcode & 0x0F) != 0x1) throw new IOException("expected a text frame, got opcode " + (opcode & 0x0F));
        int length = in.readUnsignedByte() & 0x7F;
        if (length == 126) length = in.readUnsignedShort();
        else if (length == 127) length = Math.toIntExact(in.readLong());
        byte[] payload = in.readNBytes(length);
        if (payload.length != length) throw new EOFException("incomplete text frame");
        return Json.parseObject(new String(payload, StandardCharsets.UTF_8));
    }

    private static boolean closesWithoutText(Socket socket) throws IOException {
        socket.setSoTimeout(2000);
        int first = socket.getInputStream().read();
        if (first < 0) return true;
        return (first & 0x0F) == 0x8;
    }

    private static void check(String name, Check test) {
        checks++;
        try {
            if (!test.run()) throw new AssertionError(name);
            System.out.println("PASS: " + name);
        } catch (Throwable error) {
            failures++;
            System.out.println("FAIL: " + name + " (" + error + ")");
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean run() throws Exception;
    }
}
