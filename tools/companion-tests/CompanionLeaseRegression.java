package dev.mcagent;

/** Real control-state transitions with an injected monotonic time, no game or sleep. */
public final class CompanionLeaseRegression {
    private static int checks;
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }
    private static void rejects(Runnable action, String name) {
        try { action.run(); }
        catch (IllegalStateException | IllegalArgumentException expected) { check(true, name); return; }
        throw new AssertionError(name);
    }
    public static void main(String[] args) {
        CompanionLease state = new CompanionLease("world-a");
        check(!state.bound() && !state.active(0), "starts unbound");
        rejects(() -> state.claim("world-a", state.epoch(), "owner", 0), "cannot claim before human binding");
        state.bind("body", "human");
        String first = state.epoch();
        check(state.bound() && !state.active(0), "binding waits for controller");
        rejects(() -> state.claim("world-b", first, "owner", 0), "rejects other world");
        rejects(() -> state.claim("world-a", first, "", 0), "rejects empty controller");
        state.claim("world-a", first, "owner", 100);
        state.validate("world-a", first, "owner", 101);
        check(state.active(101), "matching controller can act");
        rejects(() -> state.claim("world-a", first, "other", 102), "another controller cannot steal lease");
        rejects(() -> state.validate("world-a", first, null, 102), "legacy request cannot bypass managed body");
        state.claim("world-a", first, "owner", 1000);
        check(state.remainingMs(1000) == CompanionLease.LEASE_MS, "renew extends deadline");
        check(!state.expire(15999), "lease stays active before deadline");
        check(state.expire(16000) && state.paused(), "expiry pauses at exact deadline");
        rejects(() -> state.validate("world-a", first, "owner", 16001), "expired action rejected");
        rejects(() -> state.claim("world-a", state.epoch(), "owner", 16001), "expired lease cannot silently reacquire");
        state.resume();
        String resumed = state.epoch();
        rejects(() -> state.claim("world-a", first, "owner", 16002), "resume does not revive old generation");
        state.claim("world-a", resumed, "owner", 16002);
        state.pause("human_paused");
        rejects(() -> state.validate("world-a", resumed, "owner", 16003), "human pause invalidates in-flight generation");
        check(state.paused() && state.owner() == null, "pause clears ownership");
        state.release();
        check(!state.bound(), "release removes binding");
        rejects(() -> state.claim("world-a", resumed, "owner", 16004), "release rejects stale lease");
        state.bind("body2", "human2");
        check(state.bodyId().equals("body2") && state.targetId().equals("human2"), "rebind pins distinct identities");
        rejects(() -> state.validate("world-a", first, "owner", 16005), "rebind rejects previous body generation");
        rejects(() -> state.bind("human", "human"), "cannot bind body to itself");
        check(state.bodyId().equals("body2"), "invalid bind leaves existing binding intact");
        System.out.println("COMPANION_CHECKS=" + checks);
    }
}
