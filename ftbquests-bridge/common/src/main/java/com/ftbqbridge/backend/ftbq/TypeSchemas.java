package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.backend.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.ChapterGroup;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import dev.ftb.mods.ftbquests.quest.reward.RewardTypes;
import dev.ftb.mods.ftbquests.quest.task.TaskType;
import dev.ftb.mods.ftbquests.quest.task.TaskTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * Best-effort type schema introspection for task/reward types.
 *
 * <p>{@code defaults} is captured by instantiating a throwaway instance against a transient,
 * UNREGISTERED parent chain (group -> chapter -> quest, none added to the live file) and calling
 * {@code writeData}. That yields the type's serialized field names and baseline values, which is what
 * an authoring client needs.
 *
 * <p>{@code fields} stays empty: the richer per-field metadata lives in {@code fillConfigGroup}, which is
 * {@code @Environment(EnvType.CLIENT)} and therefore stripped on a dedicated server — calling it throws
 * {@code NoSuchMethodError}.
 */
public final class TypeSchemas {
    private TypeSchemas() {}

    /** Fixed id for throwaway objects: never registered, and avoids newID()'s markDirty side effect. */
    private static final long THROWAWAY_ID = 1L;

    public static JsonObject schema(String kind, String typeId) {
        ServerQuestFile f = ServerQuestFile.INSTANCE;
        if (f == null) throw ApiException.notLoaded();
        // Accept FTB's own short form ("item") as well as the namespaced id ("ftbquests:item").
        // This is not version-specific: FTB registers its types under the ftbquests namespace on both
        // the 2001.x and 2101.x lines, so a bare name has to be defaulted the same way on either tree.
        String fullTypeId = typeId.indexOf(':') < 0 ? "ftbquests:" + typeId : typeId;
        ResourceLocation rl = new ResourceLocation(fullTypeId);

        JsonObject out = new JsonObject();
        out.addProperty("typeId", typeId);
        out.addProperty("kind", kind);

        ChapterGroup group = new ChapterGroup(THROWAWAY_ID, f);
        Chapter chapter = new Chapter(THROWAWAY_ID, f, group);
        Quest quest = new Quest(THROWAWAY_ID, chapter);

        QuestObjectBase instance;
        if ("task".equals(kind)) {
            TaskType t = TaskTypes.TYPES.get(rl);
            if (t == null) throw ApiException.badRequest("unknown task type: " + typeId);
            instance = t.createTask(THROWAWAY_ID, quest);
        } else if ("reward".equals(kind)) {
            RewardType t = RewardTypes.TYPES.get(rl);
            if (t == null) throw ApiException.badRequest("unknown reward type: " + typeId);
            instance = t.createReward(THROWAWAY_ID, quest);
        } else {
            throw ApiException.badRequest("kind must be task|reward");
        }

        JsonObject defaults = new JsonObject();
        try {
            CompoundTag tag = new CompoundTag();
            instance.writeData(tag);
            JsonElement el = QuestSerializer.nbtToJson(tag);
            if (el.isJsonObject()) defaults = el.getAsJsonObject();
        } catch (Throwable ignored) {
            // Some types touch live context in writeData; degrade to empty defaults rather than failing.
        }
        out.add("defaults", defaults);
        out.add("fields", new JsonArray());
        out.addProperty("fieldsNote",
                "per-field config metadata is client-only on a dedicated server; use defaults + curated docs");
        return out;
    }
}
