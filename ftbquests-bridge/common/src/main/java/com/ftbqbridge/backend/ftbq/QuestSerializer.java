package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.backend.ApiException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

/**
 * Serializes FTB Quests objects to Gson for HTTP transport.
 *
 * <p>Serialization spike (resolved against ftb-quests 2101.1.27): an object's data is written via
 * {@code QuestObjectBase.writeData(CompoundTag, HolderLookup.Provider)} — it is NBT-based, NOT the
 * Json5Object the plan speculated. We bridge NBT to Gson with a DataFixerUpper {@link Dynamic}
 * conversion ({@link NbtOps} -> {@link JsonOps}); this same bridge is reused on the write side.
 */
public final class QuestSerializer {
    private QuestSerializer() {}

    public static String hex(long id) { return String.format("%016X", id); }
    public static long parseHex(String s) {
        String t = s.startsWith("#") ? s.substring(1) : s;
        return Long.parseUnsignedLong(t, 16);
    }

    /** NBT -> Gson via DFU Dynamic conversion. */
    public static JsonElement nbtToJson(Tag tag) {
        return new Dynamic<>(NbtOps.INSTANCE, tag).convert(JsonOps.INSTANCE).getValue();
    }

    /** Gson object -> NBT CompoundTag via DFU Dynamic conversion (the write-side mirror of nbtToJson). */
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
    public static JsonObject objectFull(QuestObjectBase o, HolderLookup.Provider provider) {
        JsonObject j = objectSummary(o);
        CompoundTag tag = new CompoundTag();
        o.writeData(tag, provider);
        j.add("data", nbtToJson(tag));
        return j;
    }
}
