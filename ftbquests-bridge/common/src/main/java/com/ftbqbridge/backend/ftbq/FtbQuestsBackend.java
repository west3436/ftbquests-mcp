package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.Protocol;
import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.backend.QuestBackend;
import com.ftbqbridge.backend.ServerTaskExecutor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.ChapterGroup;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.loot.RewardTable;

/**
 * The single Minecraft-coupled {@link QuestBackend}. Every operation runs on the server thread via
 * {@link ServerTaskExecutor}. This task implements the read side; mutating/registry/schema methods
 * are filled in by Tasks 10-12.
 *
 * <p>API verified against ftb-quests 2101.1.27: instance is {@code ServerQuestFile.INSTANCE};
 * {@code ChapterGroup.getChapters()}, {@code Chapter.getQuests()}, {@code Quest.getX()/getY()/getShape()}
 * are getters (the underlying fields are private).
 */
public final class FtbQuestsBackend implements QuestBackend {
    private final ServerTaskExecutor exec;
    public FtbQuestsBackend(ServerTaskExecutor exec) { this.exec = exec; }

    @Override public JsonObject health() {
        return exec.call(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("questsLoaded", ServerQuestFile.INSTANCE != null);
            o.addProperty("protocolVersion", Protocol.PROTOCOL_VERSION);
            o.addProperty("loader", loaderName());
            return o;
        });
    }

    @Override public JsonObject questMap() {
        return exec.call(() -> {
            ServerQuestFile f = ServerQuestFile.INSTANCE;
            JsonObject root = new JsonObject();
            JsonArray groups = new JsonArray();
            for (ChapterGroup g : f.getChapterGroups()) {
                JsonObject gj = new JsonObject();
                gj.addProperty("id", QuestSerializer.hex(g.id));
                gj.addProperty("title", g.getTitle().getString());
                JsonArray chapters = new JsonArray();
                for (Chapter c : g.getChapters()) {
                    JsonObject cj = new JsonObject();
                    cj.addProperty("id", QuestSerializer.hex(c.id));
                    cj.addProperty("title", c.getTitle().getString());
                    cj.addProperty("filename", c.getFilename());
                    cj.addProperty("questCount", c.getQuests().size());
                    chapters.add(cj);
                }
                gj.add("chapters", chapters);
                groups.add(gj);
            }
            root.add("chapterGroups", groups);
            JsonArray tables = new JsonArray();
            for (RewardTable t : f.getRewardTables()) tables.add(QuestSerializer.objectSummary(t));
            root.add("rewardTables", tables);
            return root;
        });
    }

    @Override public JsonObject getChapter(String id) {
        return exec.call(() -> {
            Chapter c = ServerQuestFile.INSTANCE.getChapter(QuestSerializer.parseHex(id));
            if (c == null) throw ApiException.notFound("chapter " + id);
            JsonObject j = QuestSerializer.objectSummary(c);
            JsonArray quests = new JsonArray();
            for (Quest q : c.getQuests()) {
                JsonObject qj = QuestSerializer.objectSummary(q);
                qj.addProperty("x", q.getX()); qj.addProperty("y", q.getY()); qj.addProperty("shape", q.getShape());
                quests.add(qj);
            }
            j.add("quests", quests);
            return j;
        });
    }

    @Override public JsonObject getObject(String id) {
        return exec.call(() -> {
            QuestObjectBase o = ServerQuestFile.INSTANCE.getBase(QuestSerializer.parseHex(id));
            if (o == null) throw ApiException.notFound(id);
            return QuestSerializer.objectFull(o, ServerQuestFile.INSTANCE.holderLookup());
        });
    }

    @Override public JsonArray searchQuests(String q, String type) {
        return exec.call(() -> {
            JsonArray out = new JsonArray();
            ServerQuestFile.INSTANCE.forAllQuests(quest -> {
                if (q.isEmpty() || quest.getTitle().getString().toLowerCase().contains(q.toLowerCase()))
                    out.add(QuestSerializer.objectSummary(quest));
            });
            return out;
        });
    }

    @Override public JsonObject getRewardTable(String id) {
        return exec.call(() -> {
            RewardTable t = ServerQuestFile.INSTANCE.getRewardTable(QuestSerializer.parseHex(id));
            if (t == null) throw ApiException.notFound("reward table " + id);
            return QuestSerializer.objectSummary(t);
        });
    }

    // ---- Implemented in Tasks 10-12 ----
    @Override public JsonArray searchRegistry(String k, String q, int l, int o) { throw ApiException.internal("not yet implemented"); }
    @Override public JsonArray listTaskTypes()   { throw ApiException.internal("not yet implemented"); }
    @Override public JsonArray listRewardTypes() { throw ApiException.internal("not yet implemented"); }
    @Override public JsonObject typeSchema(String k, String t) { throw ApiException.internal("not yet implemented"); }
    @Override public JsonObject createObject(String t, String p, JsonObject props, JsonObject extra) { throw ApiException.internal("not yet implemented"); }
    @Override public JsonObject editObject(String id, JsonObject props) { throw ApiException.internal("not yet implemented"); }
    @Override public void deleteObject(String id) { throw ApiException.internal("not yet implemented"); }
    @Override public JsonObject setDependency(String a, String b, boolean add) { throw ApiException.internal("not yet implemented"); }
    @Override public void save() { throw ApiException.internal("not yet implemented"); }

    /** Architectury's Platform exposes isFabric()/isNeoForge()/isForge() (no getModLoader() on 13.0.8). */
    private static String loaderName() {
        if (Platform.isFabric()) return "fabric";
        if (Platform.isNeoForge()) return "neoforge";
        if (Platform.isForge()) return "forge";
        return "unknown";
    }
}
