package dev.mcagent;

public final class ActionJournalRegression {
    private static int checks;
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }
    public static void main(String[] args) {
        ActionJournal.clear();
        String first = ActionJournal.start("body", "walk", 12);
        check(ActionJournal.get(first).get("status").getAsString().equals("running"), "accepted walk is not complete");
        var copy = ActionJournal.get(first);
        copy.addProperty("status", "succeeded");
        check(ActionJournal.get(first).get("status").getAsString().equals("running"), "read cannot mutate action history");
        check(ActionJournal.finish(first, "timed_out", "walk_timeout"), "timeout recorded");
        check(!ActionJournal.finish(first, "succeeded", "arrived"), "later success cannot overwrite timeout");
        check(ActionJournal.get(first).get("reason").getAsString().equals("walk_timeout"), "failure evidence retained");
        for (int i = 0; i < 128; i++) {
            String id = ActionJournal.start("body", "walk", i);
            ActionJournal.finish(id, "cancelled", "stopped");
        }
        check(ActionJournal.get(first).get("status").getAsString().equals("outcome_unknown"), "eviction reports unknown rather than success");
        ActionJournal.clear();
        String active = ActionJournal.start("body", "walk", 1);
        for (int i = 0; i < 140; i++) {
            String id = ActionJournal.start("body2", "walk", i);
            ActionJournal.finish(id, "succeeded", "arrived");
        }
        check(ActionJournal.get(active).get("status").getAsString().equals("running"), "bounded history preserves running action");
        ActionJournal.clear();
        check(ActionJournal.get(active).get("status").getAsString().equals("outcome_unknown"), "world reset forgets old receipts");
        System.out.println("ACTION_CHECKS=" + checks);
    }
}
