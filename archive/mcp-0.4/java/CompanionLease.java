package dev.mcagent;

import java.util.Objects;
import java.util.UUID;

/** Single-body control state. Caller owns thread confinement and stopping the physical body. */
public final class CompanionLease {
    public static final long LEASE_MS = 15_000;
    private final String worldSessionId;
    private String epoch = UUID.randomUUID().toString();
    private String bodyId;
    private String targetId;
    private String owner;
    private long deadline;
    private boolean paused;
    private String reason = "unbound";

    public CompanionLease(String worldSessionId) { this.worldSessionId = worldSessionId; }

    public void bind(String bodyId, String targetId) {
        if (bodyId == null || targetId == null || bodyId.equals(targetId))
            throw new IllegalArgumentException("distinct body and human target are required");
        this.bodyId = bodyId;
        this.targetId = targetId;
        paused = false;
        invalidate("awaiting_controller");
    }

    public void pause(String reason) {
        if (!bound()) return;
        paused = true;
        invalidate(reason);
    }

    public void resume() {
        if (!bound()) throw new IllegalStateException("no companion bound");
        paused = false;
        invalidate("awaiting_controller");
    }

    public void release() {
        bodyId = targetId = null;
        paused = false;
        invalidate("unbound");
    }

    private void invalidate(String reason) {
        epoch = UUID.randomUUID().toString();
        owner = null;
        deadline = 0;
        this.reason = reason;
    }

    public boolean expire(long now) {
        if (owner == null || now < deadline) return false;
        pause("lease_expired");
        return true;
    }

    public void claim(String world, String epoch, String owner, long now) {
        if (owner == null || owner.isBlank() || owner.length() > 128)
            throw new IllegalArgumentException("controllerId must contain 1-128 characters");
        checkGeneration(world, epoch);
        if (!bound() || paused) throw new IllegalStateException("companion is unbound or paused");
        if (this.owner != null && now >= deadline) throw new IllegalStateException("lease expired; resume required");
        if (this.owner != null && !this.owner.equals(owner)) throw new IllegalStateException("body is owned by another controller");
        this.owner = owner;
        deadline = now + LEASE_MS;
        reason = "active";
    }

    public void validate(String world, String epoch, String owner, long now) {
        checkGeneration(world, epoch);
        if (!bound() || paused || this.owner == null || !this.owner.equals(owner) || now >= deadline)
            throw new IllegalStateException("companion control is paused, expired or owned by another controller");
    }

    private void checkGeneration(String world, String epoch) {
        if (!Objects.equals(worldSessionId, world) || !Objects.equals(this.epoch, epoch))
            throw new IllegalStateException("stale companion worldSessionId/controlEpoch; refresh state without replaying the old action");
    }

    public boolean bound() { return bodyId != null; }
    public boolean active(long now) { return bound() && !paused && owner != null && now < deadline; }
    public String worldSessionId() { return worldSessionId; }
    public String epoch() { return epoch; }
    public String bodyId() { return bodyId; }
    public String targetId() { return targetId; }
    public String owner() { return owner; }
    public String reason() { return reason; }
    public boolean paused() { return paused; }
    public long remainingMs(long now) { return owner == null ? 0 : Math.max(0, deadline - now); }
}
