package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.backend.ApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ftb.mods.ftbquests.quest.reward.RewardType;
import dev.ftb.mods.ftbquests.quest.reward.RewardTypes;
import dev.ftb.mods.ftbquests.quest.task.TaskType;
import dev.ftb.mods.ftbquests.quest.task.TaskTypes;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enumerates Minecraft registries and FTB Quests task/reward types for the companion's id picker.
 *
 * <p>The id forms mirror what the matching FTB task accepts: biome/structure ids come from
 * {@code Registries.BIOME}/{@code STRUCTURE} via the server's registry access, stats from
 * {@code BuiltInRegistries.CUSTOM_STAT}, dimensions from {@code server.getAllLevels()}.
 *
 * <p>Advancements are not a registry but are still enumerable from the loaded server state:
 * {@code ServerAdvancementManager.getAllAdvancements()} returns the parsed {@link Advancement}s and
 * {@code Advancement.getId()} carries the {@code ResourceLocation} (1.20.1 has the id on the
 * advancement itself; 1.20.2+ moved it into {@code AdvancementHolder}). Reading the server state
 * rather than the datapack files means only advancements that actually loaded are listed.
 */
public final class RegistryReader {
    private RegistryReader() {}

    /** Resolves a registry value (an {@code Item}, {@code Block}, ...) to a display name, or null. */
    @FunctionalInterface
    interface NameResolver {
        String resolve(Object value);
    }

    public static JsonArray search(MinecraftServer server, String kind, String query, int limit, int offset) {
        Iterable<Map.Entry<String, Object>> entries = switch (kind) {
            case "items"        -> entries(BuiltInRegistries.ITEM);
            case "blocks"       -> entries(BuiltInRegistries.BLOCK);
            case "fluids"       -> entries(BuiltInRegistries.FLUID);
            case "entity_types" -> entries(BuiltInRegistries.ENTITY_TYPE);
            case "mob_effects"  -> entries(BuiltInRegistries.MOB_EFFECT);
            case "stats"        -> entries(BuiltInRegistries.CUSTOM_STAT);
            case "biomes"       -> entries(server.registryAccess().registryOrThrow(Registries.BIOME));
            case "structures"   -> entries(server.registryAccess().registryOrThrow(Registries.STRUCTURE));
            case "dimensions"   -> dimensionEntries(server);
            case "advancements" -> advancementEntries(server);
            default -> throw ApiException.badRequest("unknown registry kind: " + kind);
        };
        NameResolver names = "advancements".equals(kind)
                ? RegistryReader::advancementName
                : RegistryReader::localizedName;
        return assemble(entries, query, limit, offset, names);
    }

    /** Filter by case-insensitive id substring, paginate, and emit {@code {id, displayName}}. */
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

    /** Resolver result if usable, else the id. Name resolution must never break a listing. */
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
        for (var e : reg.entrySet()) {
            list.add(Map.entry(e.getKey().location().toString(), (Object) e.getValue()));
        }
        return list;
    }

    /** Live dimension ids — exactly the values a {@code ftbquests:dimension} task accepts. */
    private static Iterable<Map.Entry<String, Object>> dimensionEntries(MinecraftServer server) {
        List<Map.Entry<String, Object>> list = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String id = level.dimension().location().toString();
            list.add(Map.entry(id, (Object) id)); // no server-safe display name -> id fallback
        }
        return list;
    }

    /**
     * All loaded advancement ids, paired with their {@link Advancement} for the display title.
     *
     * <p>{@code Advancement.getId()} is the fully qualified id with {@code /} separators
     * ({@code minecraft:story/root}) — the same form an FTB advancement task and
     * {@code /advancement grant} accept.
     */
    private static Iterable<Map.Entry<String, Object>> advancementEntries(MinecraftServer server) {
        List<Map.Entry<String, Object>> list = new ArrayList<>();
        for (Advancement advancement : server.getAdvancements().getAllAdvancements()) {
            list.add(Map.entry(advancement.getId().toString(), (Object) advancement));
        }
        return list;
    }

    /** An advancement's localizable title if it has a display, else null (hidden/recipe advancements). */
    private static String advancementName(Object value) {
        if (!(value instanceof Advancement a)) return null;
        // 1.20.1: getDisplay() is a nullable DisplayInfo. Optional<DisplayInfo> arrived in 1.20.2.
        DisplayInfo display = a.getDisplay();
        return display == null ? null : display.getTitle().getString();
    }

    /**
     * Server-safe display name for a registry value, or null to fall back to the id.
     *
     * <p>These accessors route through {@code Component}, which resolves server-side; FTB's own GUI name
     * helpers are {@code @Environment(CLIENT)} and would throw on a dedicated server.
     */
    private static String localizedName(Object value) {
        // instanceof chain, not a type-pattern switch: Java 17 has no pattern matching for switch
        // (that is a preview feature there and final only in 21), and this project targets 17.
        if (value instanceof Item it) return it.getDescription().getString();
        if (value instanceof Block bl) return bl.getName().getString();
        if (value instanceof EntityType<?> et) return et.getDescription().getString();
        if (value instanceof MobEffect me) return me.getDisplayName().getString();
        return null;
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
