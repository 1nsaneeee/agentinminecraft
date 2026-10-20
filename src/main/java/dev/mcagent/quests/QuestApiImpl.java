package dev.mcagent.quests;

import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.mcagent.GameBridge;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FTB Quests implementation. Only loaded reflectively, so a pack without
 * FTB Quests never resolves these classes.
 */
public final class QuestApiImpl implements QuestApi {

    @Override
    public boolean available() {
        return ServerQuestFile.INSTANCE != null;
    }

    @Override
    public String summary(String playerName, String chapterFilter, int limit, String locale) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null) return "quest file not loaded";
        ServerPlayer player = GameBridge.player(playerName);
        TeamData data = file.getOrCreateTeamData(player);
        java.util.Map<String, String> lang = loadLangTitles(locale == null || locale.isBlank() ? "zh_cn" : locale);

        List<Chapter> chapters = new ArrayList<>();
        file.forAllChapters(chapters::add);

        Map<Long, List<Quest>> byChapter = new LinkedHashMap<>();
        file.forAllQuests(quest -> byChapter
                .computeIfAbsent(quest.getQuestChapter().getId(), k -> new ArrayList<>())
                .add(quest));

        String filter = chapterFilter == null || chapterFilter.isBlank() ? null : chapterFilter.toLowerCase();
        StringBuilder sb = new StringBuilder();
        sb.append("team: ").append(data.getName()).append(" (").append(data.getTeamId()).append(")\n");

        int printed = 0;
        int chaptersShown = 0;
        for (Chapter chapter : chapters) {
            String title = title(chapter, lang);
            if (filter != null && !title.toLowerCase().contains(filter)
                    && !String.valueOf(chapter.getId()).equals(chapterFilter)) {
                continue;
            }
            List<Quest> quests = byChapter.getOrDefault(chapter.getId(), List.of());
            long done = quests.stream().filter(q -> data.getCompletedTime(q.getId()).isPresent()).count();
            sb.append("\n== ").append(title).append("  [id=").append(chapter.getId()).append("]  ")
                    .append(done).append('/').append(quests.size()).append(" completed ==\n");
            chaptersShown++;
            for (Quest quest : quests) {
                if (printed >= limit) {
                    sb.append("  … limit reached, narrow with the chapter filter\n");
                    return sb.toString();
                }
                boolean completed = data.getCompletedTime(quest.getId()).isPresent();
                sb.append(completed ? "  [x] " : "  [ ] ")
                        .append(title(quest, lang))
                        .append("  id=").append(quest.getId())
                        .append("  tasks=").append(quest.getTasks().size());
                if (!completed && data.getStartedTime(quest.getId()).isPresent()) {
                    sb.append(" (started)");
                }
                sb.append('\n');
                printed++;
            }
        }
        if (chaptersShown == 0) {
            sb.append("(no chapter matched '").append(chapterFilter).append("')\n");
        }
        sb.append("\n").append(printed).append(" quests listed, ").append(chaptersShown).append(" chapters shown");
        return sb.toString();
    }

    @Override
    public String questDetail(long questId, String playerName, String locale) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null) return "quest file not loaded";
        QuestObjectBase base = file.getBase(questId);
        if (base == null) return "no quest object with id " + questId;
        ServerPlayer player = GameBridge.player(playerName);
        TeamData data = file.getOrCreateTeamData(player);
        java.util.Map<String, String> lang = loadLangTitles(locale == null || locale.isBlank() ? "zh_cn" : locale);

        StringBuilder sb = new StringBuilder();
        sb.append("type: ").append(base.getObjectType()).append('\n');
        sb.append("id: ").append(base.getId()).append('\n');
        sb.append("title: ").append(title(base, lang)).append('\n');
        boolean completed = data.getCompletedTime(base.getId()).isPresent();
        sb.append("completed: ").append(completed);
        data.getCompletedTime(base.getId()).ifPresent(d -> sb.append(" at ").append(d));
        sb.append('\n');
        data.getStartedTime(base.getId()).ifPresent(d -> sb.append("started: ").append(d).append('\n'));
        sb.append("progress: ").append(data.getProgress(base.getId())).append('\n');

        if (base instanceof Quest quest) {
            sb.append("chapter: ").append(title(quest.getQuestChapter(), lang)).append('\n');
            List<Task> tasks = new ArrayList<>(quest.getTasks());
            sb.append("tasks (").append(tasks.size()).append("):\n");
            for (Task task : tasks) {
                sb.append("  - ").append(task.getClass().getSimpleName())
                        .append(" id=").append(task.getId())
                        .append(" progress=").append(data.getProgress(task))
                        .append('\n');
            }
            sb.append("rewards (").append(quest.getRewards().size()).append(")\n");
        }
        return sb.toString();
    }

    private static String safeTitle(QuestObjectBase object) {
        try {
            String raw = object.getRawTitle();
            return raw == null || raw.isBlank() ? "(untitled)" : raw;
        } catch (RuntimeException e) {
            return "(title unavailable)";
        }
    }

    /**
     * Quest titles are stored in the quest data as raw strings, but the pack
     * overrides them in {@code config/ftbquests/quests/lang/<locale>.snbt} using
     * hex object codes. Reading that file is the only way to show the copy the
     * player actually sees.
     */
    private static java.util.Map<String, String> loadLangTitles(String locale) {
        java.nio.file.Path file = dev.mcagent.util.Dist2.gameDir()
                .resolve("config").resolve("ftbquests").resolve("quests")
                .resolve("lang").resolve(locale + ".snbt");
        if (!java.nio.file.Files.isRegularFile(file)) return java.util.Map.of();
        java.util.Map<String, String> out = new java.util.HashMap<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "^\\s*(?:quest|chapter)\\.([0-9A-Fa-f]{16})\\.title:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        try {
            for (String line : java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                java.util.regex.Matcher m = pattern.matcher(line);
                if (m.find()) {
                    out.put(m.group(1).toUpperCase(java.util.Locale.ROOT), m.group(2));
                }
            }
        } catch (java.io.IOException e) {
            dev.mcagent.McAgent.LOG.warn("[mcagent] could not read quest lang file {}", file, e);
        }
        return out;
    }

    private static String title(QuestObjectBase object, java.util.Map<String, String> lang) {
        String localized = lang.get(QuestObjectBase.getCodeString(object.getId()));
        return localized != null && !localized.isBlank() ? localized : safeTitle(object);
    }
}
