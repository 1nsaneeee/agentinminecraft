package dev.mcagent;

public final class CompanionChatRegression {
    public static void main(String[] args) {
        int checks = 0;
        for (String text : new String[]{"先别动", "停下！", "暂停陪伴", " 先停一下。 "}) {
            if (!"pause".equals(CompanionChatControl.actionFor(text))) throw new AssertionError(text);
            checks++;
        }
        for (String text : new String[]{"继续", "继续吧！", "继续走吧", "继续跟着我", "恢复陪伴"}) {
            if (!"resume".equals(CompanionChatControl.actionFor(text))) throw new AssertionError(text);
            checks++;
        }
        for (String text : new String[]{"不要继续", "他说继续吧", "\"继续\"", "如果我说继续呢", "停下来会怎样", ""}) {
            if (CompanionChatControl.actionFor(text) != null) throw new AssertionError(text);
            checks++;
        }
        System.out.println("CHAT_CONTROL_CHECKS=" + checks);
    }
}
