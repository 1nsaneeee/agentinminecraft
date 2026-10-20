package dev.mcagent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal HTTP/1.1 server on a plain {@link ServerSocket}.
 *
 * <p>Deliberately dependency-free: Minecraft's runtime classpath has Netty core
 * but not {@code netty-codec-http}, and the JDK's {@code com.sun.net.httpserver}
 * module is not guaranteed to exist in a jlinked runtime. A hand-rolled loop
 * also makes WebSocket hijacking trivial.</p>
 */
public final class HttpCore implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    public interface Handler {
        void handle(Request req, Response res) throws Exception;
    }

    public static final class Request {
        public final String method;
        public final String target;
        public final String path;
        public final Map<String, String> query = new LinkedHashMap<>();
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];

        final Socket socket;
        final InputStream in;
        final OutputStream out;

        Request(String method, String target, Socket socket, InputStream in, OutputStream out) {
            this.method = method;
            this.target = target;
            this.socket = socket;
            this.in = in;
            this.out = out;
            int q = target.indexOf('?');
            this.path = q < 0 ? target : target.substring(0, q);
            if (q >= 0) {
                for (String pair : target.substring(q + 1).split("&")) {
                    if (pair.isEmpty()) continue;
                    int eq = pair.indexOf('=');
                    String k = eq < 0 ? pair : pair.substring(0, eq);
                    String v = eq < 0 ? "" : pair.substring(eq + 1);
                    query.put(urlDecode(k), urlDecode(v));
                }
            }
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonObject bodyJson() {
            String text = bodyText().trim();
            if (text.isEmpty()) return Json.obj();
            return JsonParser.parseString(text).getAsJsonObject();
        }

        public String param(String name, String def) {
            String v = query.get(name);
            return v == null ? def : v;
        }

        public String remote() {
            return socket.getRemoteSocketAddress() == null ? "?" : socket.getRemoteSocketAddress().toString();
        }

        private static String urlDecode(String s) {
            try {
                return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                return s;
            }
        }
    }

    public static final class Response {
        public int status = 200;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];
        /** Set by a handler that took over the socket (WebSocket upgrade). */
        public boolean hijacked;

        public Response status(int code) {
            this.status = code;
            return this;
        }

        public Response header(String k, String v) {
            headers.put(k, v);
            return this;
        }

        public Response text(int code, String text) {
            this.status = code;
            this.body = text.getBytes(StandardCharsets.UTF_8);
            headers.put("Content-Type", "text/plain; charset=utf-8");
            return this;
        }

        public Response json(int code, JsonObject o) {
            this.status = code;
            this.body = o.toString().getBytes(StandardCharsets.UTF_8);
            headers.put("Content-Type", "application/json; charset=utf-8");
            return this;
        }
    }

    private record Route(String method, String path, Handler handler) {}

    private final ServerSocket serverSocket;
    private final List<Route> routes = new ArrayList<>();
    private final AtomicInteger threadSeq = new AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcagent-http-" + threadSeq.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;

    public HttpCore(String host, int port) throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(host, port), 64);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void route(String method, String path, Handler handler) {
        routes.add(new Route(method.toUpperCase(Locale.ROOT), path, handler));
    }

    public void start() {
        Thread acceptor = new Thread(this::acceptLoop, "mcagent-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(() -> serve(socket));
            } catch (IOException e) {
                if (!closed) McAgent.LOG.warn("[mcagent] accept failed: {}", e.toString());
                if (serverSocket.isClosed()) return;
            }
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setSoTimeout(120_000);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16384);
            try {
                while (true) {
                    Request req = readRequest(socket, in, out);
                    if (req == null) return;
                    Response res = new Response();
                    try {
                        dispatch(req, res);
                    } catch (Exception e) {
                        McAgent.LOG.warn("[mcagent] handler error {} {}", req.method, req.path, e);
                        res = new Response().text(500, "internal error: " + e);
                    }
                    if (res.hijacked) return;
                    boolean closeAfter = wantsClose(req);
                    writeResponse(out, res, closeAfter);
                    if (closeAfter) return;
                }
            } finally {
                try {
                    out.flush();
                } catch (IOException ignored) {
                }
                socket.close();
            }
        } catch (SocketTimeoutException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean wantsClose(Request req) {
        String conn = req.header("connection");
        if (conn != null && conn.toLowerCase(Locale.ROOT).contains("close")) return true;
        return "HTTP/1.0".equals(req.header("x-http-version"));
    }

    private void dispatch(Request req, Response res) throws Exception {
        for (Route r : routes) {
            if (!r.method.equals(req.method) && !"*".equals(r.method)) continue;
            if (!r.path.equals(req.path)) continue;
            r.handler.handle(req, res);
            return;
        }
        res.text(404, "no route: " + req.method + " " + req.path);
    }

    private Request readRequest(Socket socket, InputStream in, OutputStream out) throws IOException {
        String line;
        do {
            line = readLine(in, 8192);
            if (line == null) return null;
        } while (line.isEmpty());

        String[] parts = line.split(" ");
        if (parts.length < 2) throw new IOException("bad request line: " + line);
        Request req = new Request(parts[0], parts[1], socket, in, out);
        if (parts.length >= 3) req.headers.put("x-http-version", parts[2]);

        while (true) {
            String h = readLine(in, 8192);
            if (h == null) throw new IOException("eof in headers");
            if (h.isEmpty()) break;
            int colon = h.indexOf(':');
            if (colon <= 0) continue;
            req.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }

        String te = req.header("transfer-encoding");
        String cl = req.header("content-length");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            req.body = readChunked(in);
        } else if (cl != null) {
            int len;
            try {
                len = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                throw new IOException("bad content-length: " + cl);
            }
            if (len < 0 || len > MAX_BODY_BYTES) throw new IOException("body too large: " + len);
            req.body = readFully(in, len);
        }
        return req;
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in, 1024);
            if (sizeLine == null) throw new IOException("eof in chunked body");
            int semi = sizeLine.indexOf(';');
            String hex = (semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size: " + hex);
            }
            // Check before allocating either the chunk or the accumulated body.
            if (size < 0 || size > MAX_BODY_BYTES - out.size()) {
                throw new IOException("chunked body too large or invalid chunk size: " + size);
            }
            if (size == 0) {
                // trailer
                while (true) {
                    String t = readLine(in, 1024);
                    if (t == null || t.isEmpty()) break;
                }
                break;
            }
            out.write(readFully(in, size));
            if (!"".equals(readLine(in, 1024))) throw new IOException("missing CRLF after chunk");
        }
        return out.toByteArray();
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("eof in body");
            off += n;
        }
        return buf;
    }

    static String readLine(InputStream in, int max) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > max) throw new IOException("line too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void writeResponse(OutputStream out, Response res, boolean close) throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(res.status).append(' ').append(reason(res.status)).append("\r\n");
        res.headers.putIfAbsent("Content-Type", "text/plain; charset=utf-8");
        res.headers.putIfAbsent("Content-Length", Integer.toString(res.body.length));
        res.headers.putIfAbsent("Connection", close ? "close" : "keep-alive");
        for (Map.Entry<String, String> e : res.headers.entrySet()) {
            head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(res.body);
        out.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            default -> "Status";
        };
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }
}
