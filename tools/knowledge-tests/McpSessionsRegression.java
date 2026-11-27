package dev.mcagent;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class McpSessionsRegression {
    private static int checks;
    public static void main(String[] args) {
        AtomicLong clock = new AtomicLong();
        AtomicReference<String> epoch = new AtomicReference<>("world-one");
        McpSessions sessions = new McpSessions(clock::get, epoch::get);
        var a = sessions.open();
        var b = sessions.open();
        check(sessions.lookup(a.id) == a && sessions.lookup(b.id) == b, "two independent clients");
        check(!a.id.equals(b.id) && sessions.lookup(a.taskId) == null, "public task ID cannot address a session");
        sessions.close(a);
        check(sessions.lookup(a.id) == null && !a.valid(epoch.get(), clock.get()), "DELETE invalidates an accepted handle");
        check(sessions.lookup(b.id) == b, "closing a reader leaves the other client available");
        clock.addAndGet(McpSessions.IDLE_MS - 1);
        check(sessions.lookup(b.id) == b, "requests extend idle lifetime");
        clock.addAndGet(McpSessions.IDLE_MS);
        check(sessions.lookup(b.id) == null, "expired session cannot be revived");
        var old = sessions.open();
        epoch.set("world-two");
        check(!old.valid(epoch.get(), clock.get()) && sessions.lookup(old.id) == null, "world or control boundary fences queued requests");
        for (int i = 0; i < McpSessions.LIMIT; i++) sessions.open();
        try { sessions.open(); throw new AssertionError("unbounded session count"); }
        catch (IllegalStateException expected) { check(true, "bounded concurrent sessions"); }
        clock.addAndGet(McpSessions.IDLE_MS);
        check(sessions.open() != null, "expired slots are reclaimed");
        System.out.println("MCP_SESSION_CHECKS=" + checks);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }
}
