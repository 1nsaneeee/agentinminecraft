package dev.mcagent;

import java.io.*;
import java.lang.reflect.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class TransportRegression {
    private static int failures;

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
        if (failures > 0) throw new AssertionError(failures + " regression(s) failed");
        System.out.println("PASS: 9 transport regressions");
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
        listener.onMessage(connectionCtor.newInstance(request, listener), "{\"type\":\"exec\",\"command\":\"setblock 1 2 3 minecraft:stone\"}");
        DataInputStream frame = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
        frame.readUnsignedByte();
        int length = frame.readUnsignedByte() & 127;
        if (length == 126) length = frame.readUnsignedShort();
        else if (length == 127) length = Math.toIntExact(frame.readLong());
        return Json.parseObject(new String(frame.readNBytes(length), StandardCharsets.UTF_8)).get("type").getAsString();
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
