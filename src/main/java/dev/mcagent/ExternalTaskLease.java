package dev.mcagent;

import com.google.gson.JsonObject;
import java.util.UUID;

/** Pure task lease. Tokens stay out of public snapshots, events and persisted game records. */
public final class ExternalTaskLease {
    public static final String META_KEY = "dev.mcagent/task";
    public static final long TTL_MS = 15000;

    public record Stamp(String worldSessionId, String agentSessionId, String taskId, String taskToken) {
        public JsonObject toJson() {
            JsonObject out = Json.obj();
            out.addProperty("worldSessionId", worldSessionId); out.addProperty("agentSessionId", agentSessionId);
            out.addProperty("taskId", taskId); out.addProperty("taskToken", taskToken); return out;
        }
    }
    private static final class Binding {
        final String id = UUID.randomUUID().toString(), owner, epoch;
        final Stamp stamp;
        final long ttl;
        long deadline;
        Binding(String owner, String epoch, Stamp stamp, long now, long ttl) {
            this.owner = owner; this.epoch = epoch; this.stamp = stamp; this.ttl = ttl; deadline = now + ttl;
        }
    }
    private Binding binding;
    private boolean required;

    public static String string(JsonObject args, String name, int max) {
        if (args == null || !args.has(name) || !args.get(name).isJsonPrimitive()
                || !args.getAsJsonPrimitive(name).isString()) throw new IllegalArgumentException("invalid task field: " + name);
        String value = args.get(name).getAsString();
        if (value.isEmpty() || value.length() > max || !value.matches("[A-Za-z0-9._:-]+")) throw new IllegalArgumentException("invalid task field: " + name);
        return value;
    }

    public static Stamp parse(JsonObject args) {
        return new Stamp(string(args, "worldSessionId", 96), string(args, "agentSessionId", 128),
                string(args, "taskId", 96), string(args, "taskToken", 96));
    }

    public static Stamp metadata(JsonObject metadata) {
        if (metadata == null || !metadata.has(META_KEY)) return null;
        if (!metadata.get(META_KEY).isJsonObject()) throw new IllegalArgumentException("invalid external task metadata");
        return parse(metadata.getAsJsonObject(META_KEY));
    }

    public JsonObject bind(String owner, String world, String epoch, JsonObject args, long now) {
        return bind(owner, world, epoch, args, now, TTL_MS);
    }

    JsonObject bind(String owner, String world, String epoch, JsonObject args, long now, long ttl) {
        if (!world.equals(string(args, "worldSessionId", 96)) || !epoch.equals(string(args, "controlEpoch", 96)))
            throw new IllegalArgumentException("task world or control changed; do not rebind the old request");
        String session = string(args, "agentSessionId", 128), task = string(args, "taskId", 96);
        if (binding != null) {
            if (!binding.owner.equals(owner) || !binding.epoch.equals(epoch) || !binding.stamp.agentSessionId.equals(session)
                    || !binding.stamp.taskId.equals(task) || expired(now)) throw new IllegalStateException("another task is bound or this lease expired");
            // An acknowledgement lost in transit may be queried again; this does not renew the lease.
            return acknowledgement(now);
        }
        binding = new Binding(owner, epoch, new Stamp(world, session, task, UUID.randomUUID().toString()), now, ttl);
        required = true; return acknowledgement(now);
    }

    public void validate(Stamp stamp, long now) {
        if (stamp == null) {
            if (required) throw new IllegalStateException("external task binding required; unbound calls cannot operate this world");
            return;
        }
        if (binding == null || expired(now) || !binding.stamp.equals(stamp))
            throw new IllegalStateException("external task revoked, expired or mismatched; do not adopt a new token for old work");
    }

    public JsonObject renew(String owner, Stamp stamp, long now) {
        validate(stamp, now);
        if (binding == null || !binding.owner.equals(owner)) throw new IllegalStateException("task lease belongs to another connection");
        binding.deadline = now + binding.ttl; return acknowledgement(now);
    }

    public String owner() { return binding == null ? null : binding.owner; }
    public boolean ownedBy(String owner) { return binding != null && binding.owner.equals(owner); }
    public boolean expired(long now) { return binding != null && now >= binding.deadline; }
    public void revoke() { binding = null; }
    public void release() { binding = null; required = false; }

    private JsonObject acknowledgement(long now) {
        JsonObject out = Json.obj(); out.add("state", snapshot(now)); out.add("taskContext", binding.stamp.toJson()); return out;
    }

    public JsonObject snapshot(long now) {
        JsonObject out = Json.obj(); out.addProperty("protocolVersion", 1); out.addProperty("required", required);
        out.addProperty("active", binding != null && !expired(now)); out.addProperty("executionResumable", false);
        if (binding != null) {
            out.addProperty("bindingId", binding.id); out.addProperty("agentSessionId", binding.stamp.agentSessionId);
            out.addProperty("taskId", binding.stamp.taskId); out.addProperty("worldSessionId", binding.stamp.worldSessionId);
            out.addProperty("leaseRemainingMs", Math.max(0, binding.deadline - now));
        }
        return out;
    }
}
