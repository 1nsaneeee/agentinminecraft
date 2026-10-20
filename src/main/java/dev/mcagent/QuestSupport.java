package dev.mcagent;

import dev.mcagent.quests.QuestApi;

/**
 * Reflective entry point for FTB Quests integration.
 *
 * <p>A pack without FTB Quests must not fail to load this mod, so the class that
 * imports FTB types is only touched after proving the mod is present.</p>
 */
public final class QuestSupport {
    private static final QuestApi API = load();

    private QuestSupport() {}

    private static QuestApi load() {
        try {
            Class.forName("dev.ftb.mods.ftbquests.quest.ServerQuestFile");
        } catch (Throwable absent) {
            return null;
        }
        try {
            Class<?> cls = Class.forName("dev.mcagent.quests.QuestApiImpl");
            return (QuestApi) cls.getDeclaredConstructor().newInstance();
        } catch (Throwable broken) {
            McAgent.LOG.warn("[mcagent] FTB Quests is installed but the quest bridge failed to load: {}",
                    broken.toString());
            return null;
        }
    }

    public static QuestApi get() {
        return API;
    }

    public static boolean installed() {
        return API != null;
    }
}
