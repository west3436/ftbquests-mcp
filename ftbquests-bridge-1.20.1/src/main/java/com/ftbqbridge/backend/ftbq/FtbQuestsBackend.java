package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.Protocol;
import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.backend.QuestBackend;
import com.ftbqbridge.backend.ServerTaskExecutor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import dev.ftb.mods.ftbquests.quest.loot.WeightedReward;
import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fml.ModList;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The single Minecraft-coupled {@link QuestBackend}. Every operation runs on the server thread via
 * {@link ServerTaskExecutor}.
 *
 * <p>API differences this port has to absorb on the 1.20.1 / ftb-quests 2001.x line:
 * <ul>
 *   <li>{@code QuestObjectBase.writeData/readData} take only a {@link CompoundTag} — no
 *       {@code HolderLookup.Provider}, no server-side TranslationManager, so title/subtitle/description
 *       are ordinary NBT and need no translation splitting. The flip side is that text shapes vary per
 *       object type and a mistyped tag is silently ignored, which is what
 *       {@link TextFieldShapes} compensates for.
 *   <li>{@code ServerQuestFile.deleteObjects(List)} does not exist, so {@link #deleteObject} walks the
 *       subtree descendants-first and calls {@code deleteObject(long)} per id; that call deletes the
 *       .snbt file and broadcasts the removal itself.
 *   <li>The response messages reach {@code sendToAll(MinecraftServer)} directly through Architectury's
 *       {@code BaseS2CMessage} superclass, so no network helper is involved.
 * </ul>
 */
public final class FtbQuestsBackend implements QuestBackend {
    private final ServerTaskExecutor exec;
    private final boolean saveImmediately;

    public FtbQuestsBackend(ServerTaskExecutor exec) {
        this(exec, true);
    }

    public FtbQuestsBackend(ServerTaskExecutor exec, boolean saveImmediately) {
        this.exec = exec;
        this.saveImmediately = saveImmediately;
    }

    // ---- read side ----

    @Override
    public JsonObject health() {
        // Deliberately NOT through the executor: /health must answer while quests are still loading
        // (reporting questsLoaded=false) and while the server thread is busy. Reading a static
        // reference for a null check is safe.
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("questsLoaded", ServerQuestFile.INSTANCE != null);
        o.addProperty("protocolVersion", Protocol.PROTOCOL_VERSION);
        o.addProperty("loader", "forge");
        o.addProperty("minecraftVersion", "1.20.1");
        o.addProperty("ftbQuestsVersion", ModList.get()
                .getModContainerById("ftbquests")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown"));
        return o;
    }

    @Override
    public JsonObject questMap() {
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
                    // getPath() is chapters/<filename>.snbt; getFilename() is the bare name.
                    cj.addProperty("filename", c.getPath().map(QuestTree::chapterFilename).orElse(""));
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

    @Override
    public JsonObject getChapter(String id) {
        return exec.call(() -> {
            Chapter c = ServerQuestFile.INSTANCE.getChapter(QuestSerializer.parseHex(id));
            if (c == null) throw ApiException.notFound("chapter " + id);
            JsonObject j = QuestSerializer.objectSummary(c);
            JsonArray quests = new JsonArray();
            for (Quest q : c.getQuests()) {
                JsonObject qj = QuestSerializer.objectSummary(q);
                qj.addProperty("x", q.getX());
                qj.addProperty("y", q.getY());
                qj.addProperty("shape", q.getShape());
                quests.add(qj);
            }
            j.add("quests", quests);
            return j;
        });
    }

    @Override
    public JsonObject getObject(String id) {
        return exec.call(() -> {
            QuestObjectBase o = ServerQuestFile.INSTANCE.getBase(QuestSerializer.parseHex(id));
            if (o == null) throw ApiException.notFound(id);
            return QuestSerializer.objectFull(o);
        });
    }

    @Override
    public JsonArray searchQuests(String q, String type) {
        return exec.call(() -> {
            JsonArray out = new JsonArray();
            String needle = q == null ? "" : q.toLowerCase(Locale.ROOT);
            ServerQuestFile.INSTANCE.forAllQuests(quest -> {
                if (type != null && !type.isBlank()
                        && !quest.getObjectType().name().equalsIgnoreCase(type)) return;
                if (needle.isEmpty()
                        || quest.getRawTitle().toLowerCase(Locale.ROOT).contains(needle)) {
                    out.add(QuestSerializer.objectSummary(quest));
                }
            });
            return out;
        });
    }

    @Override
    public JsonObject getRewardTable(String id) {
        return exec.call(() -> {
            RewardTable t = ServerQuestFile.INSTANCE.getRewardTable(QuestSerializer.parseHex(id));
            if (t == null) throw ApiException.notFound("reward table " + id);
            JsonObject j = QuestSerializer.objectFull(t);
            JsonArray rewards = new JsonArray();
            // getWeightedRewards() yields WeightedReward wrappers (reward + drop weight), not Reward.
            for (WeightedReward wr : t.getWeightedRewards()) {
                JsonObject rj = QuestSerializer.objectSummary(wr.getReward());
                rj.addProperty("weight", wr.getWeight());
                rewards.add(rj);
            }
            j.add("rewards", rewards);
            return j;
        });
    }

    // ---- registry + type enumeration ----

    @Override
    public JsonArray searchRegistry(String kind, String query, int limit, int offset) {
        return exec.call(() -> RegistryReader.search(ServerQuestFile.INSTANCE.server, kind, query,
                Math.min(Math.max(limit, 1), 500), Math.max(offset, 0)));
    }

    @Override
    public JsonArray listTaskTypes() { return exec.call(RegistryReader::taskTypes); }

    @Override
    public JsonArray listRewardTypes() { return exec.call(RegistryReader::rewardTypes); }

    @Override
    public JsonObject typeSchema(String kind, String typeId) {
        if (!"task".equals(kind) && !"reward".equals(kind)) {
            throw ApiException.badRequest("kind must be task|reward");
        }
        return exec.call(() -> TypeSchemas.schema(kind, typeId));
    }

    // ---- mutation ----

    @Override
    public JsonObject createObject(String type, String parent, JsonObject properties, JsonObject extra) {
        return exec.call(() -> {
            ServerQuestFile f = ServerQuestFile.INSTANCE;
            QuestObjectType objType;
            try {
                objType = QuestObjectType.valueOf(type.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("unknown object type: " + type);
            }
            long parentId = (parent == null || parent.isBlank()) ? 1L : QuestSerializer.parseHex(parent);

            JsonObject extraJson = extra == null ? new JsonObject() : extra;
            CompoundTag extraNbt = QuestSerializer.jsonToNbt(extraJson);
            // BaseQuestFile.create() reads a CHAPTER's group with extra.getLong("group"), but a hex id
            // arriving as JSON string lands in NBT as a string that getLong() ignores — the chapter would
            // silently fall into the default group. Re-encode it as the long FTB expects.
            String groupHex = chapterGroupHex(objType, extraJson);
            long requestedGroup = groupHex.isBlank() ? 0L : QuestSerializer.parseHex(groupHex);
            if (!groupHex.isBlank()) extraNbt.putLong("group", requestedGroup);

            QuestObjectBase o;
            try {
                o = f.create(f.newID(), objType, parentId, extraNbt);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(String.valueOf(e.getMessage()));
            }

            // Normalize before readData: the object's reader ignores mistyped text tags, so a JSON
            // string where 2001.x wants a string list would be dropped without a trace.
            CompoundTag propsNbt = QuestSerializer.jsonToNbt(properties == null ? new JsonObject() : properties);
            JsonArray warnings = TextFieldShapes.normalize(objType, propsNbt);

            o.readData(propsNbt);
            // create() builds the object but does not link it into its parent or the id map.
            o.onCreated();
            f.refreshIDMap();
            f.clearCachedData();
            f.markDirty();

            // Broadcast so an open editor sees the new object without relogging; same path FTB's own
            // CreateObjectMessage.handle() takes.
            new CreateObjectResponseMessage(o, extraNbt).sendToAll(f.server);
            persist(f);

            JsonObject result = QuestSerializer.objectFull(o);
            if (!groupHex.isBlank() && o instanceof Chapter chapter
                    && chapter.getGroup().id != requestedGroup) {
                warnings.add("chapter group " + groupHex + " was not found; chapter placed in the default group");
            }
            if (warnings.size() > 0) result.add("warnings", warnings);
            return result;
        });
    }

    @Override
    public JsonObject editObject(String id, JsonObject properties) {
        return exec.call(() -> {
            ServerQuestFile f = ServerQuestFile.INSTANCE;
            QuestObjectBase o = f.getBase(QuestSerializer.parseHex(id));
            if (o == null) throw ApiException.notFound(id);

            // Merge the patch onto the object's current writeData NBT so untouched fields keep their
            // exact NBT numeric types (only patched keys round-trip through JSON -> NBT).
            CompoundTag current = new CompoundTag();
            o.writeData(current);
            CompoundTag patch = QuestSerializer.jsonToNbt(properties == null ? new JsonObject() : properties);
            for (String key : patch.getAllKeys()) current.put(key, patch.get(key));

            // The type-specific text shapes apply to edits too — and matter more here, because an
            // object that is already populated loses its existing value rather than merely a new one.
            JsonArray warnings = TextFieldShapes.normalize(o.getObjectType(), current);

            o.readData(current);
            o.editedFromGUIOnServer();
            f.clearCachedData();
            f.markDirty();
            new EditObjectResponseMessage(o).sendToAll(f.server);
            persist(f);

            JsonObject result = QuestSerializer.objectFull(o);
            if (warnings.size() > 0) result.add("warnings", warnings);
            return result;
        });
    }

    @Override
    public void deleteObject(String id) {
        exec.call(() -> {
            ServerQuestFile f = ServerQuestFile.INSTANCE;
            long oid = QuestSerializer.parseHex(id);
            QuestObjectBase obj = f.getBase(oid);
            if (obj == null) throw ApiException.notFound(id);
            if (obj == f) throw ApiException.badRequest("the quest file itself cannot be deleted");
            if (obj instanceof ChapterGroup group && group.isDefaultGroup()) {
                throw ApiException.badRequest("the default chapter group cannot be deleted");
            }

            // Descendants first, then the object itself: that is the order deleteChildren()/deleteSelf()
            // expect, and it is what removes each chapter's .snbt file. Deleting a chapter group only
            // re-homes its chapters, so they have to be removed as objects in their own right or their
            // files are reloaded on the next restart.
            List<Long> ids = QuestTree.childrenFirst(obj, FtbQuestsBackend::children).stream()
                    .map(o -> o.id)
                    .toList();
            for (long target : ids) {
                // deleteObject() removes the object, deletes its .snbt file, and broadcasts the removal.
                if (f.getBase(target) != null) f.deleteObject(target);
            }
            f.refreshIDMap();
            f.clearCachedData();
            f.markDirty();
            persist(f);
            return null;
        });
    }

    /**
     * Children that must be deleted along with their parent.
     *
     * <p>A quest's {@code getChildren()} returns only its tasks; rewards are siblings in the file's
     * view, so they are appended explicitly — otherwise deleting a quest leaves its rewards behind as
     * orphans that no chapter lists.
     */
    private static Collection<? extends QuestObjectBase> children(QuestObjectBase o) {
        if (o instanceof Quest quest) {
            List<QuestObjectBase> out = new java.util.ArrayList<>(quest.getTasks());
            out.addAll(quest.getRewards());
            return out;
        }
        if (o instanceof Chapter chapter) return chapter.getQuests();
        if (o instanceof ChapterGroup group) return group.getChapters();
        return List.of();
    }

    @Override
    public JsonObject setDependency(String questId, String dependsOnId, boolean add) {
        return exec.call(() -> {
            ServerQuestFile f = ServerQuestFile.INSTANCE;
            Quest quest = f.getQuest(QuestSerializer.parseHex(questId));
            if (quest == null) throw ApiException.notFound("quest " + questId);
            QuestObjectBase dep = f.getBase(QuestSerializer.parseHex(dependsOnId));
            if (!(dep instanceof QuestObject depObj)) {
                throw ApiException.badRequest("dependency must be a quest object: " + dependsOnId);
            }
            if (add) quest.addDependency(depObj);
            else quest.removeDependency(depObj);
            quest.editedFromGUIOnServer();
            f.clearCachedData();
            f.markDirty();
            new EditObjectResponseMessage(quest).sendToAll(f.server);
            persist(f);

            JsonObject o = new JsonObject();
            o.addProperty("questId", QuestSerializer.hex(quest.id));
            o.addProperty("dependsOnId", dependsOnId);
            o.addProperty("added", add);
            return o;
        });
    }

    @Override
    public void save() {
        exec.call(() -> {
            ServerQuestFile.INSTANCE.saveNow();
            return null;
        });
    }

    /** Persist immediately when saveMode=immediate; otherwise leave the dirty flag for an explicit save. */
    private void persist(ServerQuestFile f) {
        if (saveImmediately) f.saveNow();
    }

    /** The requested chapter-group hex from {@code extra.group}, or "" when absent/blank/not a CHAPTER. */
    private static String chapterGroupHex(QuestObjectType type, JsonObject extra) {
        if (type != QuestObjectType.CHAPTER || extra == null || !extra.has("group")) return "";
        JsonElement g = extra.get("group");
        return g.isJsonPrimitive() ? g.getAsString().trim() : "";
    }
}
