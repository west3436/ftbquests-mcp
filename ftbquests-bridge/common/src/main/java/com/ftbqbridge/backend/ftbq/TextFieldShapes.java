package com.ftbqbridge.backend.ftbq;

import com.google.gson.JsonArray;
import dev.ftb.mods.ftbquests.quest.QuestObjectType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Coerces the JSON-provided text fields into the NBT shape each object type actually reads.
 *
 * <p>FTB Quests 2001.x stores text as plain NBT, but the shapes differ per object type, and
 * {@code readData} silently ignores a mistyped tag instead of rejecting it:
 * <ul>
 *   <li>{@code Chapter}: {@code subtitle} is a <b>list</b> of strings (one entry per line);
 *       there is no {@code description} field at all.
 *   <li>{@code Quest}: {@code subtitle} is a single string, {@code description} is a list of strings.
 * </ul>
 * On an edit this is destructive rather than merely lossy — the object keeps whatever the patch
 * left behind, so a string sent for a chapter subtitle replaces the existing list with nothing.
 * Normalizing here (and reporting it as a warning) keeps a wrong-but-plausible payload working.
 */
final class TextFieldShapes {
    private TextFieldShapes() {}

    /** Rewrites {@code tag} in place and returns one warning per adjusted field. */
    static JsonArray normalize(QuestObjectType type, CompoundTag tag) {
        JsonArray warnings = new JsonArray();
        if (type == QuestObjectType.CHAPTER) {
            if (wrapStringIntoList(tag, "subtitle")) {
                warnings.add("chapters store 'subtitle' as a list of strings; wrapped it in a"
                        + " single-element list");
            }
        } else if (type == QuestObjectType.QUEST) {
            if (joinListIntoString(tag, "subtitle")) {
                warnings.add("quests store 'subtitle' as a single string; joined the list with newlines");
            }
            if (wrapStringIntoList(tag, "description")) {
                warnings.add("quests store 'description' as a list of strings; wrapped it in a"
                        + " single-element list");
            }
        }
        if (type == QuestObjectType.CHAPTER && tag.contains("description")) {
            // Chapter has no description field: leaving it in would look accepted and then be dropped,
            // so drop it here where we can say so.
            tag.remove("description");
            warnings.add("chapters have no 'description' field; the value was ignored");
        }
        return warnings;
    }

    /** {@code key}: string -> one-element list. Returns true when it changed something. */
    private static boolean wrapStringIntoList(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_STRING)) return false;
        ListTag list = new ListTag();
        list.add(StringTag.valueOf(tag.getString(key)));
        tag.put(key, list);
        return true;
    }

    /** {@code key}: list -> strings joined with newlines. Returns true when it changed something. */
    private static boolean joinListIntoString(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) return false;
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(list.getString(i));
        }
        tag.putString(key, sb.toString());
        return true;
    }
}
