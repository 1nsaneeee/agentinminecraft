package dev.mcagent;

import com.google.gson.JsonObject;

public final class ExternalTaskLeaseRegression {
    private static int checks;
    public static void main(String[] args) {
        ExternalTaskLease lease = new ExternalTaskLease();
        lease.validate(null, 0); check(!lease.snapshot(0).get("required").getAsBoolean(), "unbound legacy mode remains explicit");
        JsonObject request = request("world", "epoch", "session-A", "task-A");
        JsonObject reply = lease.bind("owner-A", "world", "epoch", request, 0);
        ExternalTaskLease.Stamp stamp = ExternalTaskLease.parse(reply.getAsJsonObject("taskContext"));
        lease.validate(stamp, 1); check(lease.ownedBy("owner-A"), "owner is the actual connection");
        check(reply.getAsJsonObject("state").get("active").getAsBoolean(), "bind creates a live task");
        check(!lease.snapshot(1).toString().contains(stamp.taskToken()), "public state never contains task token");
        rejects(() -> lease.validate(null, 1), "bound mode rejects missing identity");
        rejects(() -> lease.validate(new ExternalTaskLease.Stamp("other", stamp.agentSessionId(), stamp.taskId(), stamp.taskToken()), 1), "wrong world rejected");
        rejects(() -> lease.validate(new ExternalTaskLease.Stamp(stamp.worldSessionId(), "other", stamp.taskId(), stamp.taskToken()), 1), "wrong session rejected");
        rejects(() -> lease.validate(new ExternalTaskLease.Stamp(stamp.worldSessionId(), stamp.agentSessionId(), "other", stamp.taskToken()), 1), "wrong task rejected");
        rejects(() -> lease.validate(new ExternalTaskLease.Stamp(stamp.worldSessionId(), stamp.agentSessionId(), stamp.taskId(), "other"), 1), "wrong token rejected");
        JsonObject repeated = lease.bind("owner-A", "world", "epoch", request, 50);
        check(repeated.getAsJsonObject("taskContext").equals(reply.getAsJsonObject("taskContext")), "lost bind acknowledgement can be recovered");
        check(repeated.getAsJsonObject("state").get("leaseRemainingMs").getAsLong() == ExternalTaskLease.TTL_MS - 50, "repeated bind does not renew");
        rejects(() -> lease.bind("owner-B", "world", "epoch", request, 50), "observer cannot take ownership");
        rejects(() -> lease.bind("owner-A", "world", "epoch", request("world", "epoch", "session-B", "task-B"), 50), "same connection cannot replace an active session");
        rejects(() -> lease.renew("owner-B", stamp, 100), "only owning connection renews");
        lease.renew("owner-A", stamp, 100);
        check(lease.snapshot(100).get("leaseRemainingMs").getAsLong() == ExternalTaskLease.TTL_MS, "owner renews bounded lease");
        check(!lease.expired(15099) && lease.expired(15100), "deadline is exclusive at actual boundary");
        rejects(() -> lease.renew("owner-A", stamp, 15100), "expired lease cannot revive");
        rejects(() -> lease.bind("owner-A", "world", "epoch", request, 15100), "expired bind retry cannot revive");
        lease.revoke();
        check(lease.snapshot(15100).get("required").getAsBoolean() && !lease.snapshot(15100).get("active").getAsBoolean(), "revoke preserves identity requirement");
        rejects(() -> lease.validate(null, 15101), "revocation never falls back to anonymous writes");
        rejects(() -> lease.validate(stamp, 15101), "old stamp stays revoked");
        JsonObject fresh = lease.bind("owner-B", "world", "next", request("world", "next", "session-B", "task-B"), 15101);
        check(!fresh.getAsJsonObject("taskContext").equals(reply.getAsJsonObject("taskContext")), "new assignment has distinct identity");
        rejects(() -> lease.validate(stamp, 15102), "new assignment cannot adopt old stamp");
        lease.release(); lease.validate(null, 15103);
        check(!lease.snapshot(15103).get("required").getAsBoolean(), "explicit release restores legacy calls");
        rejects(() -> lease.validate(stamp, 15103), "release cannot reinterpret a tagged old request as legacy");
        for (String bad : new String[]{"", "a b", "x".repeat(129)}) {
            rejects(() -> lease.bind("owner", "world", "epoch", request("world", "epoch", bad, "task"), 0), "bounded session id rejected");
        }
        rejects(() -> lease.bind("owner", "world", "epoch", request("stale", "epoch", "session", "task"), 0), "stale bind world rejected");
        rejects(() -> lease.bind("owner", "world", "epoch", request("world", "stale", "session", "task"), 0), "stale bind epoch rejected");
        JsonObject malformed = request("world", "epoch", "session", "task"); malformed.addProperty("taskId", 123);
        rejects(() -> lease.bind("owner", "world", "epoch", malformed, 0), "numeric ids cannot be coerced");
        check(ExternalTaskLease.metadata(Json.obj()) == null, "unrelated MCP metadata is not task identity");
        JsonObject meta = Json.obj(); meta.add(ExternalTaskLease.META_KEY, stamp.toJson());
        check(ExternalTaskLease.metadata(meta).equals(stamp), "MCP namespaced metadata decodes exact stamp");
        meta.addProperty(ExternalTaskLease.META_KEY, "bad");
        rejects(() -> ExternalTaskLease.metadata(meta), "malformed metadata rejected");
        check(!lease.snapshot(0).toString().contains("taskToken"), "released state omits secrets");
        System.out.println("EXTERNAL_TASK_LEASE_CHECKS=" + checks);
    }
    private static JsonObject request(String world, String epoch, String session, String task) {
        JsonObject out = Json.obj(); out.addProperty("worldSessionId", world); out.addProperty("controlEpoch", epoch);
        out.addProperty("agentSessionId", session); out.addProperty("taskId", task); return out;
    }
    private static void check(boolean passed, String name) { if (!passed) throw new AssertionError(name); checks++; }
    private static void rejects(Runnable action, String name) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { check(true, name); return; }
        throw new AssertionError(name);
    }
}
