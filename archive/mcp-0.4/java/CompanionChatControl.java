package dev.mcagent;

/** Explicit conversational shortcuts only; quoted/negative/longer sentences are left to dialogue. */
public final class CompanionChatControl {
    private CompanionChatControl() {}
    public static String actionFor(String message) {
        String text = message.strip().replaceFirst("[。！!]+$", "");
        return switch (text) {
            case "先别动", "停下", "暂停陪伴", "先停一下" -> "pause";
            case "继续", "继续吧", "继续走吧", "继续跟着我", "恢复陪伴" -> "resume";
            default -> null;
        };
    }
}
