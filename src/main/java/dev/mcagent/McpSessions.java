package dev.mcagent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Transport sessions are scoped to one endpoint and one world/control epoch. */
final class McpSessions {
    static final String HEADER = "Mcp-Session-Id";
    static final long IDLE_MS = TimeUnit.MINUTES.toMillis(30);
    static final int LIMIT = 128;

    static final class Session {
        final String id = UUID.randomUUID().toString();
        // Public receipts may identify the task, but must never disclose the HTTP session credential.
        final String taskId = UUID.randomUUID().toString();
        final String epoch;
        private volatile boolean closed;
        private volatile long lastRequest;

        private Session(String epoch, long now) { this.epoch = epoch; lastRequest = now; }
        boolean valid(String currentEpoch, long now) {
            return !closed && epoch.equals(currentEpoch) && now - lastRequest < IDLE_MS;
        }
    }

    private final LongSupplier clock;
    private final Supplier<String> controlEpoch;
    private final Map<String, Session> sessions = new HashMap<>();

    McpSessions(LongSupplier clock, Supplier<String> controlEpoch) {
        this.clock = clock;
        this.controlEpoch = controlEpoch;
    }
    static long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }

    synchronized Session open() {
        String epoch = controlEpoch.get();
        long now = clock.getAsLong();
        sessions.values().removeIf(session -> !session.valid(epoch, now));
        if (sessions.size() >= LIMIT) throw new IllegalStateException("too many active MCP sessions");
        Session session = new Session(epoch, now);
        sessions.put(session.id, session);
        return session;
    }

    synchronized Session lookup(String id) {
        Session session = sessions.get(id);
        long now = clock.getAsLong();
        if (session == null || !session.valid(controlEpoch.get(), now)) return null;
        session.lastRequest = now;
        return session;
    }

    synchronized void close(Session session) {
        session.closed = true;
        sessions.remove(session.id, session);
    }
}
