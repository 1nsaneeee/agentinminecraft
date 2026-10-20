package dev.mcagent.quests;

/**
 * Quest book access, kept behind an interface so the class that actually
 * references FTB Quests types is only loaded when the mod is present.
 */
public interface QuestApi {
    /** False when the quest file has not been loaded yet (no world, or FTB Quests absent). */
    boolean available();

    /** Chapter/quest tree with completion state for one player's team. */
    String summary(String playerName, String chapterFilter, int limit, String locale);

    /** One quest in detail: tasks, rewards and per-task progress. */
    String questDetail(long questId, String playerName, String locale);
}
