package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.backend.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import dev.ftb.mods.ftbquests.quest.reward.RewardTypes;
import dev.ftb.mods.ftbquests.quest.task.TaskType;
import dev.ftb.mods.ftbquests.quest.task.TaskTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Map;

/**
 * Enumerates Minecraft registries and FTB Quests task/reward types.
 *
 * <p>API verified against ftb-quests 2101.1.27: {@code TaskTypes.TYPES} / {@code RewardTypes.TYPES}
 * are {@code Map<ResourceLocation, TaskType/RewardType>}; {@code getDisplayName()} returns a Component.
 * Built-in registries come from {@link BuiltInRegistries}; dynamic registries (biomes, structures, …)
 * would need {@code server.registryAccess()} and can be added later.
 */
public final class RegistryReader {
    private RegistryReader() {}

    public static JsonArray search(String kind, String query, int limit, int offset) {
        Registry<?> reg = switch (kind) {
            case "items"        -> BuiltInRegistries.ITEM;
            case "blocks"       -> BuiltInRegistries.BLOCK;
            case "fluids"       -> BuiltInRegistries.FLUID;
            case "entity_types" -> BuiltInRegistries.ENTITY_TYPE;
            case "mob_effects"  -> BuiltInRegistries.MOB_EFFECT;
            default -> throw ApiException.badRequest("unknown registry kind: " + kind);
        };
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        JsonArray out = new JsonArray();
        int seen = 0;
        for (ResourceLocation rl : reg.keySet()) {
            String id = rl.toString();
            if (!q.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (seen++ < offset) continue;
            if (out.size() >= limit) break;
            JsonObject e = new JsonObject();
            e.addProperty("id", id);
            e.addProperty("displayName", id); // display-name resolution refined later if needed
            out.add(e);
        }
        return out;
    }

    public static JsonArray taskTypes() {
        JsonArray a = new JsonArray();
        for (Map.Entry<ResourceLocation, TaskType> e : TaskTypes.TYPES.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("typeId", e.getKey().toString());
            o.addProperty("namespace", e.getKey().getNamespace());
            o.addProperty("displayName", e.getValue().getDisplayName().getString());
            a.add(o);
        }
        return a;
    }

    public static JsonArray rewardTypes() {
        JsonArray a = new JsonArray();
        for (Map.Entry<ResourceLocation, RewardType> e : RewardTypes.TYPES.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("typeId", e.getKey().toString());
            o.addProperty("namespace", e.getKey().getNamespace());
            o.addProperty("displayName", e.getValue().getDisplayName().getString());
            a.add(o);
        }
        return a;
    }
}
