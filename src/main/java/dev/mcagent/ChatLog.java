package dev.mcagent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rolling in-memory buffer of chat lines, so the agent can ask "what did the
 * player just say" without tailing {@code latest.log}.
 */
public final class ChatLog {
    public record Line(long seq, String player, String text, boolean system, long time) {}

    private static final int CAPACITY = 500;
    private static final ArrayDeque<Line> LINES = new ArrayDeque<>();
    private static long seq;

    private ChatLog() {}

    public static synchronized void add(String player, String text, boolean system) {
        LINES.addLast(new Line(++seq, player == null ? "" : player, text == null ? "" : text, system, System.currentTimeMillis()));
        while (LINES.size() > CAPACITY) LINES.removeFirst();
    }

    public static synchronized long lastSeq() {
        return seq;
    }

    public static synchronized List<Line> since(long afterSeq, int max) {
        List<Line> out = new ArrayList<>();
        for (Line l : LINES) {
            if (l.seq() > afterSeq) out.add(l);
        }
        if (out.size() > max) out = out.subList(out.size() - max, out.size());
        return out;
    }

    public static synchronized List<Line> tail(int max, String regex) {
        Pattern p = regex == null || regex.isBlank() ? null : Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        List<Line> out = new ArrayList<>();
        for (Line l : LINES) {
            if (p == null || p.matcher(l.text()).find()) out.add(l);
        }
        if (out.size() > max) out = out.subList(out.size() - max, out.size());
        return out;
    }
}
