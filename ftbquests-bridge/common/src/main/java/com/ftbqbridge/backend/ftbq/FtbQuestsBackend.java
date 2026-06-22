package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.Protocol;
import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.backend.QuestBackend;
import com.ftbqbridge.backend.ServerTaskExecutor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;
import dev.ftb.mods.ftblibrary.util.NetworkHelper;
import dev.ftb.mods.ftbquests.net.CreateObjectResponseMessage;
import dev.ftb.mods.ftbquests.net.EditObjectResponseMessage;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.ChapterGroup;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.QuestObjectType;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.loot.RewardTable;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

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
    private final boolean saveImmediately;
    public FtbQuestsBackend(ServerTaskExecutor exec) { this(exec, true); }
    public FtbQuestsBackend(ServerTaskExecutor exec, boolean saveImmediately) {
        this.exec = exec; this.saveImmediately = saveImmediately;
    }

    @Override public JsonObject health() {
        // Liveness probe: computed WITHOUT the server-thread executor on purpose. The executor's call()
        // throws notLoaded()/503 when ServerQuestFile.INSTANCE is null and blocks on the server thread —
        // but /health must answer 200 even while quests are still loading (reporting questsLoaded=false)
        // and must stay responsive even if the server thread is busy. Reading the static INSTANCE
        // reference for a null-check is safe enough for a health report.
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("questsLoaded", ServerQuestFile.INSTANCE != null);
        o.addProperty("protocolVersion", Protocol.PROTOCOL_VERSION);
        o.addProperty("loader", loaderName());
        return o;
    }

    @Override public JsonObject questMap() {
        return exec.call(() -> {
            ServerQuestFile f = ServerQuestFile.INSTANCE;
            JsonObject root = new JsonObject();
            JsonArray groups = new JsonArray();
            for (ChapterGroup g : f.getChapterGroups()) {
                JsonObject gj = new JsonObject();
                gj.addProperty("id", QuestSerializer.hex(g.id));
                gj.addProperty("title", g.getRawTitle());
                JsonArray chapters = new JsonArray();
                for (Chapter c : g.getChapters()) {
                    JsonObject cj = new JsonObject();
                    cj.addProperty("id", QuestSerializer.hex(c.id));
                    cj.addProperty("title", c.getRawTitle());
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
                if (q.isEmpty() || quest.getRawTitle().toLowerCase().contains(q.toLowerCase()))
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

    // ---- Task 10: registry + type enumeration ----
    @Override public JsonArray searchRegistry(String kind, String query, int limit, int offset) {
        return exec.call(() -> RegistryReader.search(kind, query, Math.min(Math.max(limit, 1), 500), Math.max(offset, 0)));
    }
    @Override public JsonArray listTaskTypes()   { return exec.call(RegistryReader::taskTypes); }
    @Override public JsonArray listRewardTypes() { return exec.call(RegistryReader::rewardTypes); }

    // ---- Task 11: type schema introspection (writeData defaults; fields client-only -> empty) ----
    @Override public JsonObject typeSchema(String kind, String typeId) {
        if (!"task".equals(kind) && !"reward".equals(kind)) throw ApiException.badRequest("kind must be task|reward");
        return exec.call(() -> TypeSchemas.schema(kind, typeId));
    }

    // ---- Task 12: create / edit / delete / dependency / save (NBT-based, live broadcast) ----
    //
    // NOTE: title/description text is NOT carried here. On ftb-quests 2101.1.27 those live in the
    // TranslationManager (lang files), not in writeData/readData NBT (which hold only icon/tags +
    // type-specific config). Routing localized text through the bridge is deferred (spec spike #3).

    @Override public JsonObject createObject(String type, String parent, JsonObject properties, JsonObject extra) {
        return exec.call(() -> {
            ServerQuestFile f = ServerQuestFile.INSTANCE;
            QuestObjectType objType;
            try { objType = QuestObjectType.valueOf(type.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw ApiException.badRequest("unknown object type: " + type); }
            long parentId = (parent == null || parent.isBlank()) ? 1L : QuestSerializer.parseHex(parent);
            CompoundTag extraNbt = QuestSerializer.jsonToNbt(extra == null ? new JsonObject() : extra);
            QuestObjectBase o;
            try { o = f.create(f.newID(), objType, parentId, extraNbt); }
            catch (IllegalArgumentException e) { throw ApiException.badRequest(String.valueOf(e.getMessage())); }
            o.readData(QuestSerializer.jsonToNbt(properties == null ? new JsonObject() : properties), f.holderLookup());
            o.onCreated();
            f.refreshIDMap(); f.clearCachedData(); f.markDirty();
            NetworkHelper.sendToAll(f.server, CreateObjectResponseMessage.create(o, extraNbt));
            persist(f);
            return QuestSerializer.objectFull(o, f.holderLookup());
        });
    }

    @Override public JsonObject editObject(String id, JsonObject properties) {
        return exec.call(() -> {
            ServerQuestFile f = ServerQuestFile.INSTANCE;
            QuestObjectBase o = f.getBase(QuestSerializer.parseHex(id));
            if (o == null) throw ApiException.notFound(id);
            // Merge the patch onto the object's current writeData NBT, preserving the exact NBT types
            // of untouched fields (only patched keys round-trip through JSON->NBT).
            CompoundTag current = new CompoundTag();
            o.writeData(current, f.holderLookup());
            CompoundTag patch = QuestSerializer.jsonToNbt(properties == null ? new JsonObject() : properties);
            for (String key : patch.getAllKeys()) current.put(key, patch.get(key));
            o.readData(current, f.holderLookup());
            o.editedFromGUIOnServer();
            f.clearCachedData(); f.markDirty();
            NetworkHelper.sendToAll(f.server, new EditObjectResponseMessage(o));
            persist(f);
            return QuestSerializer.objectFull(o, f.holderLookup());
        });
    }

    @Override public void deleteObject(String id) {
        exec.call(() -> {
            ServerQuestFile f = ServerQuestFile.INSTANCE;
            long oid = QuestSerializer.parseHex(id);
            if (f.getBase(oid) == null) throw ApiException.notFound(id);
            f.deleteObject(oid); // removes translations, deletes children/self, refreshes, markDirty, self-broadcasts
            persist(f);
            return null;
        });
    }

    @Override public JsonObject setDependency(String questId, String dependsOnId, boolean add) {
        return exec.call(() -> {
            ServerQuestFile f = ServerQuestFile.INSTANCE;
            Quest quest = f.getQuest(QuestSerializer.parseHex(questId));
            if (quest == null) throw ApiException.notFound("quest " + questId);
            QuestObjectBase dep = f.getBase(QuestSerializer.parseHex(dependsOnId));
            if (!(dep instanceof QuestObject depObj)) throw ApiException.badRequest("dependency must be a quest object: " + dependsOnId);
            if (add) quest.addDependency(depObj); else quest.removeDependency(depObj);
            quest.editedFromGUIOnServer();
            f.clearCachedData(); f.markDirty();
            NetworkHelper.sendToAll(f.server, new EditObjectResponseMessage(quest));
            persist(f);
            JsonObject o = new JsonObject();
            o.addProperty("questId", QuestSerializer.hex(quest.id));
            o.addProperty("dependsOnId", dependsOnId);
            o.addProperty("added", add);
            return o;
        });
    }

    @Override public void save() {
        exec.call(() -> { ServerQuestFile.INSTANCE.saveNow(); return null; });
    }

    /** Persist immediately when saveMode=immediate; otherwise leave the dirty flag for an explicit /save. */
    private void persist(ServerQuestFile f) {
        if (saveImmediately) f.saveNow();
    }

    /** Architectury's Platform exposes isFabric()/isNeoForge()/isForge() (no getModLoader() on 13.0.8). */
    private static String loaderName() {
        if (Platform.isFabric()) return "fabric";
        if (Platform.isNeoForge()) return "neoforge";
        if (Platform.isForge()) return "forge";
        return "unknown";
    }
}
