package dev.mcagent.util;

/** Rolling TPS estimate from server tick arrival times. */
public final class TpsTracker {
    private static final long[] TIMES = new long[120];
    private static int next;
    private static int count;

    private TpsTracker() {}

    public static synchronized void onTick() {
        TIMES[next] = System.nanoTime();
        next = (next + 1) % TIMES.length;
        if (count < TIMES.length) count++;
    }

    public static synchronized double tps() {
        if (count < 20) return 20.0;
        int newest = (next - 1 + TIMES.length) % TIMES.length;
        int oldest = (next - count + TIMES.length) % TIMES.length;
        long span = TIMES[newest] - TIMES[oldest];
        if (span <= 0) return 20.0;
        double seconds = span / 1_000_000_000.0;
        double tps = (count - 1) / seconds;
        return Math.round(Math.min(20.0, tps) * 100.0) / 100.0;
    }
}
