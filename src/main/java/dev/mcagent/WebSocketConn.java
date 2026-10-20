package dev.mcagent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/**
 * RFC 6455 WebSocket, server side: handshake plus framing.
 *
 * <p>Used for the live channel between the agent and the game. The read loop
 * runs on the HTTP connection thread; writes are synchronized because game
 * threads push events concurrently.</p>
 */
public final class WebSocketConn {

    public interface Listener {
        void onOpen(WebSocketConn ws);

        void onMessage(WebSocketConn ws, String text);

        void onClose(WebSocketConn ws);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME = 4 * 1024 * 1024;

    private final HttpCore.Request req;
    private final Listener listener;
    private final Object writeLock = new Object();
    private volatile boolean open = true;
    private volatile String name = "anon";

    private WebSocketConn(HttpCore.Request req, Listener listener) {
        this.req = req;
        this.listener = listener;
    }

    public static boolean isUpgrade(HttpCore.Request req) {
        String upgrade = req.header("upgrade");
        return upgrade != null && "websocket".equalsIgnoreCase(upgrade.trim());
    }

    /** Performs the handshake then blocks in the read loop until the peer goes away. */
    public static void run(HttpCore.Request req, HttpCore.Response res, Listener listener) throws IOException {
        String key = req.header("sec-websocket-key");
        if (key == null || key.isBlank()) {
            res.text(400, "missing sec-websocket-key");
            return;
        }
        if (!isUpgrade(req)) {
            res.text(400, "not a websocket upgrade");
            return;
        }
        String accept;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            accept = Base64.getEncoder().encodeToString(sha1.digest((key.trim() + GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (Exception e) {
            throw new IOException("sha1 unavailable", e);
        }
        String handshake = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        OutputStream out = req.out;
        synchronized (out) {
            out.write(handshake.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
        res.hijacked = true;

        WebSocketConn ws = new WebSocketConn(req, listener);
        try {
            ws.req.socket.setSoTimeout(0);
        } catch (IOException ignored) {
        }
        try {
            listener.onOpen(ws);
            ws.readLoop();
        } finally {
            ws.open = false;
            try {
                listener.onClose(ws);
            } catch (RuntimeException e) {
                McAgent.LOG.warn("[mcagent] ws close handler failed", e);
            }
            try {
                req.socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isOpen() {
        return open;
    }

    public void send(String text) {
        if (!open) return;
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_FRAME) {
            throw new IllegalArgumentException("outbound frame too large: " + payload.length);
        }
        try {
            synchronized (writeLock) {
                writeFrame(0x1, payload);
            }
        } catch (IOException e) {
            open = false;
        }
    }

    public void sendJson(com.google.gson.JsonObject o) {
        send(o.toString());
    }

    public void close() {
        if (!open) return;
        open = false;
        try {
            synchronized (writeLock) {
                writeFrame(0x8, new byte[0]);
            }
        } catch (IOException ignored) {
        }
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        OutputStream out = req.out;
        int len = payload.length;
        byte[] header;
        if (len < 126) {
            header = new byte[] {(byte) (0x80 | opcode), (byte) len};
        } else if (len < 65536) {
            header = new byte[] {(byte) (0x80 | opcode), 126, (byte) (len >> 8), (byte) len};
        } else {
            header = new byte[10];
            header[0] = (byte) (0x80 | opcode);
            header[1] = 127;
            long l = len;
            for (int i = 0; i < 8; i++) {
                header[2 + i] = (byte) (l >> (56 - 8 * i));
            }
        }
        out.write(header);
        out.write(payload);
        out.flush();
    }

    private void readLoop() throws IOException {
        InputStream in = req.in;
        ByteArrayAccumulator fragments = new ByteArrayAccumulator();
        int fragmentOpcode = -1;
        while (open) {
            int b0 = in.read();
            if (b0 < 0) return;
            int b1 = in.read();
            if (b1 < 0) return;

            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte(in);
                }
            }
            if (len < 0 || len > MAX_FRAME) throw new IOException("frame too large: " + len);
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                for (int i = 0; i < 4; i++) mask[i] = (byte) readByte(in);
            }
            byte[] payload = new byte[(int) len];
            int off = 0;
            while (off < payload.length) {
                int n = in.read(payload, off, payload.length - off);
                if (n < 0) return;
                off += n;
            }
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }

            switch (opcode) {
                case 0x0 -> { // continuation
                    if (fragmentOpcode < 0) throw new IOException("continuation without start");
                    fragments.write(payload);
                    if (fin) {
                        emit(fragmentOpcode, fragments.toByteArray());
                        fragments.reset();
                        fragmentOpcode = -1;
                    }
                }
                case 0x1, 0x2 -> {
                    if (fin) {
                        emit(opcode, payload);
                    } else {
                        fragmentOpcode = opcode;
                        fragments.write(payload);
                    }
                }
                case 0x8 -> { // close
                    close();
                    return;
                }
                case 0x9 -> { // ping -> pong
                    synchronized (writeLock) {
                        writeFrame(0xA, payload);
                    }
                }
                case 0xA -> { // pong, ignore
                }
                default -> {
                    // reserved opcodes: ignore
                }
            }
        }
    }

    private void emit(int opcode, byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        try {
            listener.onMessage(this, text);
        } catch (RuntimeException e) {
            McAgent.LOG.warn("[mcagent] ws message handler failed", e);
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("eof in frame");
        return b;
    }

    private static final class ByteArrayAccumulator {
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

        void write(byte[] b) {
            buf.write(b, 0, b.length);
        }

        byte[] toByteArray() {
            return buf.toByteArray();
        }

        void reset() {
            buf.reset();
        }
    }

    /** Human readable remote description, for logs. */
    public String remote() {
        return req.remote();
    }

    @Override
    public String toString() {
        return "ws{" + name + "@" + remote() + "}";
    }

    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
