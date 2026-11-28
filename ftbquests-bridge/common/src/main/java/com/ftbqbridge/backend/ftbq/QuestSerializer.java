package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.backend.ApiException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

/**
 * Serializes FTB Quests objects to Gson for HTTP transport.
 *
 * <p>An object's data is NBT-based: {@code QuestObjectBase.writeData(CompoundTag)}. On 1.20.1 that
 * method takes only the tag (the {@code HolderLookup.Provider} parameter arrived in 1.21.1), which is
 * also why {@link #objectFull} needs no registry access.
 *
 * <p>The NBT <-> JSON hop goes through DataFixerUpper's {@link Dynamic} conversion rather than Gson's
 * tree walker, because DFU preserves the numeric types SNBT depends on (byte/short/int/long) and
 * rejects silently-wrong shapes instead of coercing them.
 */
public final class QuestSerializer {
    private QuestSerializer() {}

    public static String hex(long id) { return String.format("%016X", id); }

    public static long parseHex(String s) {
        String t = s == null ? "" : (s.startsWith("#") ? s.substring(1) : s);
        try {
            return Long.parseUnsignedLong(t, 16);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid id: " + s); // -> 400, not a generic 500
        }
    }

    /** NBT -> Gson via DFU Dynamic conversion. */
    public static JsonElement nbtToJson(Tag tag) {
        return new Dynamic<>(NbtOps.INSTANCE, tag).convert(JsonOps.INSTANCE).getValue();
    }

    /** Gson object -> NBT CompoundTag, the write-side mirror of {@link #nbtToJson}. */
    public static CompoundTag jsonToNbt(JsonObject json) {
        Tag t = new Dynamic<>(JsonOps.INSTANCE, (JsonElement) json).convert(NbtOps.INSTANCE).getValue();
        if (t instanceof CompoundTag c) return c;
        throw ApiException.badRequest("expected a JSON object");
    }

    public static JsonObject objectSummary(QuestObjectBase o) {
        JsonObject j = new JsonObject();
        j.addProperty("id", hex(o.id));
        j.addProperty("type", o.getObjectType().name());
        j.addProperty("title", o.getRawTitle());
        return j;
    }

    /** Full object including its writeData NBT, converted to JSON for transport. */
    public static JsonObject objectFull(QuestObjectBase o) {
        JsonObject j = objectSummary(o);
        CompoundTag tag = new CompoundTag();
        o.writeData(tag);
        j.add("data", nbtToJson(tag));
        return j;
    }
}
