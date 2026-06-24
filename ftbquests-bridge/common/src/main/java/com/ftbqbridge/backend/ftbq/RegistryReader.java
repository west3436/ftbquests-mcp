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
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
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

    /** Resolves a registry value (an {@code Item}, {@code Block}, ...) to a display name, or null if unknown. */
    @FunctionalInterface
    interface NameResolver { String resolve(Object value); }

    public static JsonArray search(String kind, String query, int limit, int offset) {
        Registry<?> reg = switch (kind) {
            case "items"        -> BuiltInRegistries.ITEM;
            case "blocks"       -> BuiltInRegistries.BLOCK;
            case "fluids"       -> BuiltInRegistries.FLUID;
            case "entity_types" -> BuiltInRegistries.ENTITY_TYPE;
            case "mob_effects"  -> BuiltInRegistries.MOB_EFFECT;
            default -> throw ApiException.badRequest("unknown registry kind: " + kind);
        };
        return assemble(entries(reg), query, limit, offset, RegistryReader::localizedName);
    }

    /** Filter by case-insensitive id substring, paginate (offset/limit), and emit {@code {id, displayName}}. */
    static JsonArray assemble(Iterable<Map.Entry<String, Object>> entries,
                              String query, int limit, int offset, NameResolver names) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        JsonArray out = new JsonArray();
        int seen = 0;
        for (Map.Entry<String, Object> entry : entries) {
            String id = entry.getKey();
            if (!q.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (seen++ < offset) continue;
            if (out.size() >= limit) break;
            JsonObject e = new JsonObject();
            e.addProperty("id", id);
            e.addProperty("displayName", safeName(names, entry.getValue(), id));
            out.add(e);
        }
        return out;
    }

    /** Resolver result if usable, else the id. Name resolution must never break a registry listing. */
    private static String safeName(NameResolver names, Object value, String id) {
        try {
            String n = names.resolve(value);
            return (n == null || n.isBlank()) ? id : n;
        } catch (Throwable t) {
            return id;
        }
    }

    /** Adapt a Minecraft registry to (id, value) entries. */
    private static Iterable<Map.Entry<String, Object>> entries(Registry<?> reg) {
        List<Map.Entry<String, Object>> list = new ArrayList<>();
        for (var e : reg.entrySet()) list.add(Map.entry(e.getKey().location().toString(), (Object) e.getValue()));
        return list;
    }

    /**
     * Server-safe localized name for a registry value, or null to fall back to the id. The vanilla name
     * accessors below route through {@code Component}/{@code Language}, which (unlike FTB's GUI helpers)
     * are NOT {@code @Environment(CLIENT)} and resolve on a dedicated server. Fluids and any other kind
     * have no server-safe display name here and fall back to the id.
     */
    private static String localizedName(Object value) {
        return switch (value) {
            case Item it          -> it.getDescription().getString();
            case Block bl         -> bl.getName().getString();
            case EntityType<?> et -> et.getDescription().getString();
            case MobEffect me     -> me.getDisplayName().getString();
            default               -> null;
        };
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
