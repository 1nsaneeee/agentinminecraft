package dev.mcagent;

import com.google.gson.JsonObject;

/** Bounded feedback from an actual command dispatch; silence never implies success. */
public final class CommandReceipt {
    private final int limit;
    private final StringBuilder output = new StringBuilder();
    private boolean truncated;
    private boolean dispatched = true;
    private long succeeded;
    private long failed;
    private long resultSum;

    public CommandReceipt(int limit) {
        if (limit < 1) throw new IllegalArgumentException("output limit must be positive");
        this.limit = limit;
    }

    public void message(String text) {
        if (output.length() > 0 && output.length() < limit) output.append('\n');
        int room = limit - output.length();
        output.append(text, 0, Math.min(room, text.length()));
        truncated |= text.length() > room;
    }

    public void result(boolean success, int value) {
        if (success) succeeded++; else failed++;
        resultSum += value;
    }

    public void rejectBeforeDispatch(String reason) {
        dispatched = false;
        message(reason);
    }

    public JsonObject toJson() {
        JsonObject out = Json.obj();
        out.addProperty("dispatched", dispatched);
        out.addProperty("outcome", !dispatched ? "rejected" : failed > 0 ? (succeeded > 0 ? "partial_failure" : "failed")
                : succeeded > 0 ? "succeeded" : "outcome_unknown");
        out.addProperty("successfulCallbacks", succeeded);
        out.addProperty("failedCallbacks", failed);
        out.addProperty("resultSum", resultSum);
        out.addProperty("output", output.toString());
        out.addProperty("outputTruncated", truncated);
        out.addProperty("verificationRequired", true);
        return out;
    }
}
