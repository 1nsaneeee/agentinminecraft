package dev.mcagent;

public final class CommandReceiptRegression {
    private static int checks;
    public static void main(String[] args) {
        CommandReceipt silent = new CommandReceipt(16);
        check(silent.toJson().get("outcome").getAsString().equals("outcome_unknown"), "silence is not success");
        silent.message("looks successful");
        check(silent.toJson().get("outcome").getAsString().equals("outcome_unknown"), "feedback alone is not success");
        CommandReceipt success = new CommandReceipt(16);
        success.result(true, 0);
        check(success.toJson().get("outcome").getAsString().equals("succeeded"), "successful zero result without output");
        check(success.toJson().get("verificationRequired").getAsBoolean(), "callback does not prove user goal");
        CommandReceipt failure = new CommandReceipt(16);
        failure.result(false, 0);
        check(failure.toJson().get("outcome").getAsString().equals("failed"), "explicit failure");
        failure.result(true, 4);
        check(failure.toJson().get("outcome").getAsString().equals("partial_failure"), "mixed command forks are not complete success");
        check(failure.toJson().get("successfulCallbacks").getAsInt() == 1
                && failure.toJson().get("failedCallbacks").getAsInt() == 1, "mixed effects remain visible");
        failure.result(true, Integer.MAX_VALUE);
        check(failure.toJson().get("resultSum").getAsLong() == 2147483651L, "aggregate does not overflow int");
        CommandReceipt bounded = new CommandReceipt(5);
        bounded.message("abc"); bounded.message("defgh"); bounded.message("x".repeat(100000));
        check(bounded.toJson().get("output").getAsString().equals("abc\nd")
                && bounded.toJson().get("outputTruncated").getAsBoolean(), "feedback bounded while accumulating");
        var snapshot = failure.toJson(); snapshot.addProperty("outcome", "invented");
        check(failure.toJson().get("outcome").getAsString().equals("partial_failure"), "receipt snapshot isolation");
        CommandReceipt rejected = new CommandReceipt(64);
        rejected.rejectBeforeDispatch("invalid command syntax");
        check(!rejected.toJson().get("dispatched").getAsBoolean()
                && rejected.toJson().get("outcome").getAsString().equals("rejected"), "syntax rejection is not command dispatch");
        check(rejected.toJson().get("failedCallbacks").getAsInt() == 0, "syntax rejection does not invent callback evidence");
        System.out.println("COMMAND_RECEIPT_CHECKS=" + checks);
    }
    private static void check(boolean passed, String label) {
        if (!passed) throw new AssertionError(label);
        checks++;
    }
}
