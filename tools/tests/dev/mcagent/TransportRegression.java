package dev.mcagent;

import java.io.*;
import java.lang.reflect.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class TransportRegression {
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        check("chat rejects WS commands", () -> wsReply(AgentMode.Mode.CHAT, true).equals("error"));
        check("work permits enabled WS commands", () -> wsReply(AgentMode.Mode.WORK, true).equals("exec_result"));
        check("disabled commands stay disabled", () -> wsReply(AgentMode.Mode.WORK, false).equals("error"));
        check("small chunked body survives decoding", () -> new String(decode("3\r\nabc\r\n0\r\n\r\n"), StandardCharsets.UTF_8).equals("abc"));
        check("8 MiB chunked body is accepted", () -> decode(chunks(8 * 1024 * 1024)).length == 8 * 1024 * 1024);
        check("oversized chunk is rejected", () -> rejected(chunks(8 * 1024 * 1024 + 1)));
        check("cumulative chunk limit is enforced", () -> rejected(chunks(4 * 1024 * 1024, 4 * 1024 * 1024 + 1)));
        check("negative chunk is rejected as IO error", () -> rejected("-1\r\n".getBytes(StandardCharsets.US_ASCII)));
        check("unterminated chunk stream is rejected", () -> rejected("3\r\nabc\r\n".getBytes(StandardCharsets.US_ASCII)));
        check("core close stops a silent upgraded WebSocket read and invokes onClose", TransportRegression::closeStopsWebSocketRead);
        check("core close releases partial HTTP requests and active handler socket reads", TransportRegression::closeStopsHttpReads);
        check("normal peer disconnect unregisters its connection and keeps the core usable", TransportRegression::peerDisconnectUnregisters);
        check("old core close preserves another core and a replacement on the same port", TransportRegression::oldCoreDoesNotCloseReplacement);
        check("a handler can close its own core repeatedly without waiting on itself", TransportRegression::closeFromHandler);
        check("concurrent accept and repeated close release every accepted socket", TransportRegression::acceptCloseRace);
        check("rejected worker submission closes and unregisters its accepted socket", TransportRegression::rejectedSubmissionClosesSocket);
        if (failures > 0) throw new AssertionError(failures + " regression(s) failed");
        System.out.println("PASS: " + checks + " transport regressions");
    }

    private static boolean closeStopsWebSocketRead() throws Exception {
        try (WsFixture fixture = new WsFixture(); Socket client = openWebSocket(fixture.core.port())) {
            if (!fixture.opened.await(2, TimeUnit.SECONDS)) return false;
            sendText(client, "before-close");
            if (!readText(client).equals("before-close")) return false;
            fixture.core.close();
            if (!fixture.closed.await(700, TimeUnit.MILLISECONDS)) {
                sendText(client, "after-close");
                throw new AssertionError("old WebSocket survived HttpCore.close(); received=" + readText(client));
            }
            return fixture.finished.await(2, TimeUnit.SECONDS) && fixture.closeCalls.get() == 1 && peerClosed(client)
                    && connectionCount(fixture.core) == 0 && executor(fixture.core).awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static boolean closeStopsHttpReads() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finished = new CountDownLatch(1);
        try (HttpCore core = new HttpCore("127.0.0.1", 0)) {
            core.route("POST", "/hold", (request, response) -> {
                request.socket.setSoTimeout(0);
                entered.countDown();
                try { request.in.read(); }
                finally { finished.countDown(); }
            });
            core.start();
            try (Socket partial = new Socket("127.0.0.1", core.port()); Socket active = new Socket("127.0.0.1", core.port())) {
                partial.getOutputStream().write("GET /partial HTTP/1.1\r\nHost: unfinished".getBytes(StandardCharsets.US_ASCII));
                partial.getOutputStream().flush();
                active.getOutputStream().write("POST /hold HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                active.getOutputStream().flush();
                if (!entered.await(2, TimeUnit.SECONDS) || !waitFor(() -> connectionCount(core) == 2)) return false;
                core.close();
                return peerClosed(partial) && peerClosed(active) && finished.await(2, TimeUnit.SECONDS)
                        && connectionCount(core) == 0 && executor(core).awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    private static boolean peerDisconnectUnregisters() throws Exception {
        try (WsFixture fixture = new WsFixture()) {
            try (Socket client = openWebSocket(fixture.core.port())) {
                if (!fixture.opened.await(2, TimeUnit.SECONDS) || !waitFor(() -> connectionCount(fixture.core) == 1)) return false;
            }
            if (!fixture.closed.await(2, TimeUnit.SECONDS) || !waitFor(() -> connectionCount(fixture.core) == 0)) return false;
            try (Socket next = openWebSocket(fixture.core.port())) {
                sendText(next, "still-serving");
                if (!readText(next).equals("still-serving")) return false;
            }
            return waitFor(() -> fixture.closeCalls.get() == 2 && connectionCount(fixture.core) == 0);
        }
    }

    private static boolean oldCoreDoesNotCloseReplacement() throws Exception {
        try (WsFixture old = new WsFixture(); WsFixture other = new WsFixture();
             Socket oldClient = openWebSocket(old.core.port()); Socket otherClient = openWebSocket(other.core.port())) {
            int port = old.core.port();
            old.core.close();
            if (!old.closed.await(2, TimeUnit.SECONDS) || !peerClosed(oldClient)) return false;
            try (WsFixture replacement = new WsFixture(port); Socket next = openWebSocket(port)) {
                old.core.close();
                old.core.start();
                sendText(otherClient, "other-core");
                sendText(next, "replacement");
                return readText(otherClient).equals("other-core") && readText(next).equals("replacement")
                        && old.closeCalls.get() == 1 && other.closeCalls.get() == 0 && replacement.closeCalls.get() == 0
                        && connectionCount(old.core) == 0 && connectionCount(other.core) == 1 && connectionCount(replacement.core) == 1;
            }
        }
    }

    private static boolean closeFromHandler() throws Exception {
        CountDownLatch returned = new CountDownLatch(1);
        try (HttpCore core = new HttpCore("127.0.0.1", 0)) {
            core.route("GET", "/stop", (request, response) -> {
                core.close();
                core.close();
                returned.countDown();
            });
            core.start();
            try (Socket client = new Socket("127.0.0.1", core.port())) {
                client.getOutputStream().write("GET /stop HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                return returned.await(2, TimeUnit.SECONDS) && peerClosed(client)
                        && waitFor(() -> connectionCount(core) == 0) && executor(core).awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    private static boolean acceptCloseRace() throws Exception {
        for (int round = 0; round < 20; round++) {
            ConcurrentLinkedQueue<Socket> clients = new ConcurrentLinkedQueue<>();
            List<Thread> workers = new ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            try (HttpCore core = new HttpCore("127.0.0.1", 0)) {
                core.start();
                int port = core.port();
                clients.add(new Socket("127.0.0.1", port));
                if (!waitFor(() -> connectionCount(core) == 1)) return false;
                for (int index = 0; index < 6; index++) {
                    boolean closer = index >= 4;
                    Thread worker = new Thread(() -> {
                        try {
                            start.await();
                            if (closer) { core.close(); core.close(); }
                            else {
                                Socket client = new Socket();
                                try {
                                    client.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
                                    clients.add(client);
                                } catch (IOException expected) { client.close(); }
                            }
                        } catch (Throwable error) { failure.compareAndSet(null, error); }
                    }, "transport-accept-close-" + index);
                    worker.setDaemon(true);
                    workers.add(worker);
                    worker.start();
                }
                start.countDown();
                for (Thread worker : workers) { worker.join(2000); if (worker.isAlive()) return false; }
                if (failure.get() != null || connectionCount(core) != 0) return false;
                for (Socket client : clients) if (!peerClosed(client)) return false;
                if (!executor(core).awaitTermination(2, TimeUnit.SECONDS)) return false;
            } finally {
                for (Socket client : clients) client.close();
            }
        }
        return true;
    }

    private static boolean rejectedSubmissionClosesSocket() throws Exception {
        AtomicInteger dispatched = new AtomicInteger();
        try (HttpCore core = new HttpCore("127.0.0.1", 0)) {
            core.route("GET", "/unused", (request, response) -> dispatched.incrementAndGet());
            executor(core).shutdownNow();
            core.start();
            try (Socket client = new Socket("127.0.0.1", core.port())) {
                return peerClosed(client) && waitFor(() -> connectionCount(core) == 0) && dispatched.get() == 0;
            }
        }
    }

    private static boolean waitFor(Check condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.run() && System.nanoTime() < deadline) Thread.sleep(5);
        return condition.run();
    }

    private static int connectionCount(HttpCore core) throws ReflectiveOperationException {
        Field lock = HttpCore.class.getDeclaredField("lifecycleLock"), sockets = HttpCore.class.getDeclaredField("connections");
        lock.setAccessible(true); sockets.setAccessible(true);
        synchronized (lock.get(core)) { return ((Set<?>) sockets.get(core)).size(); }
    }

    private static ExecutorService executor(HttpCore core) throws ReflectiveOperationException {
        Field field = HttpCore.class.getDeclaredField("pool");
        field.setAccessible(true);
        return (ExecutorService) field.get(core);
    }

    private static final class WsFixture implements AutoCloseable {
        final HttpCore core;
        final CountDownLatch opened = new CountDownLatch(1), closed = new CountDownLatch(1), finished = new CountDownLatch(1);
        final AtomicInteger closeCalls = new AtomicInteger();

        WsFixture() throws IOException {
            this(0);
        }

        WsFixture(int port) throws IOException {
            core = new HttpCore("127.0.0.1", port);
            core.route("GET", "/ws", (request, response) -> {
                try {
                    WebSocketConn.run(request, response, new WebSocketConn.Listener() {
                        public void onOpen(WebSocketConn ws) { opened.countDown(); }
                        public void onMessage(WebSocketConn ws, String text) { ws.send(text); }
                        public void onClose(WebSocketConn ws) { closeCalls.incrementAndGet(); closed.countDown(); }
                    });
                } finally { finished.countDown(); }
            });
            core.start();
        }

        public void close() { core.close(); }
    }

    private static Socket openWebSocket(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(2000);
        socket.getOutputStream().write(("GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
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
        byte[] payload = text.getBytes(StandardCharsets.UTF_8), mask = { 3, 1, 4, 1 };
        if (payload.length >= 126) throw new IllegalArgumentException("test frame is too large");
        OutputStream out = socket.getOutputStream();
        out.write(0x81); out.write(0x80 | payload.length); out.write(mask);
        for (int index = 0; index < payload.length; index++) out.write(payload[index] ^ mask[index & 3]);
        out.flush();
    }

    private static String readText(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        if (in.readUnsignedByte() != 0x81) throw new IOException("expected a text frame");
        int length = in.readUnsignedByte();
        if (length >= 126) throw new IOException("unexpected test frame length");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("incomplete text frame");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean peerClosed(Socket socket) throws IOException {
        socket.setSoTimeout(1500);
        try { return socket.getInputStream().read() == -1; }
        catch (SocketTimeoutException e) { return false; }
        catch (IOException e) { return true; }
    }

    private static String wsReply(AgentMode.Mode mode, boolean commands) throws Exception {
        AgentMode.set(mode);
        AgentConfig.get().allowCommands = commands;
        Class<?> type = Class.forName("dev.mcagent.WsHub$ListenerImpl");
        Constructor<?> listenerCtor = type.getDeclaredConstructor(WsHub.class);
        listenerCtor.setAccessible(true);
        WebSocketConn.Listener listener = (WebSocketConn.Listener) listenerCtor.newInstance(McAgent.WS);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        HttpCore.Request request = new HttpCore.Request("GET", "/ws", new Socket(), InputStream.nullInputStream(), output);
        Constructor<WebSocketConn> connectionCtor = WebSocketConn.class.getDeclaredConstructor(HttpCore.Request.class, WebSocketConn.Listener.class);
        connectionCtor.setAccessible(true);
        WebSocketConn connection = connectionCtor.newInstance(request, listener);
        listener.onOpen(connection);
        output.reset(); // Inspect the command reply after the normal hello handshake.
        try {
            listener.onMessage(connection, "{\"type\":\"exec\",\"command\":\"setblock 1 2 3 minecraft:stone\"}");
            DataInputStream frame = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
            frame.readUnsignedByte();
            int length = frame.readUnsignedByte() & 127;
            if (length == 126) length = frame.readUnsignedShort();
            else if (length == 127) length = Math.toIntExact(frame.readLong());
            return Json.parseObject(new String(frame.readNBytes(length), StandardCharsets.UTF_8)).get("type").getAsString();
        } finally {
            listener.onClose(connection);
            connection.abort();
        }
    }

    private static byte[] chunks(int... sizes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int size : sizes) {
            bytes.write((Integer.toHexString(size) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            bytes.write(new byte[size]);
            bytes.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        bytes.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        return bytes.toByteArray();
    }

    private static byte[] decode(String wire) throws Exception { return decode(wire.getBytes(StandardCharsets.US_ASCII)); }

    private static byte[] decode(byte[] wire) throws Exception {
        Method method = HttpCore.class.getDeclaredMethod("readChunked", InputStream.class);
        method.setAccessible(true);
        try {
            return (byte[]) method.invoke(null, new ByteArrayInputStream(wire));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private static boolean rejected(byte[] wire) throws Exception {
        try { decode(wire); return false; }
        catch (IOException expected) { return true; }
    }

    private static void check(String name, Check test) {
        checks++;
        try {
            if (!test.run()) throw new AssertionError(name);
            System.out.println("PASS: " + name);
        } catch (Exception | AssertionError e) {
            failures++;
            System.out.println("FAIL: " + name + " (" + e + ")");
        }
    }

    @FunctionalInterface private interface Check { boolean run() throws Exception; }
}
