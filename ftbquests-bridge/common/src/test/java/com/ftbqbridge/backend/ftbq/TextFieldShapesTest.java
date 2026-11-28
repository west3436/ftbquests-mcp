package com.ftbqbridge.backend.ftbq;

import com.google.gson.JsonArray;
import dev.ftb.mods.ftbquests.quest.QuestObjectType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TextFieldShapes} exists because 2001.x {@code readData} ignores a mistyped text tag instead of
 * rejecting it: a chapter subtitle sent as a string whittles down to an empty list, wiping it. These
 * tests pin the per-type shapes so a "harmless" payload fix cannot reintroduce the data loss.
 */
class TextFieldShapesTest {

    private static ListTag strings(String... values) {
        ListTag list = new ListTag();
        for (String v : values) list.add(StringTag.valueOf(v));
        return list;
    }

    @Test
    void chapterSubtitleStringBecomesSingleElementList() {
        CompoundTag tag = new CompoundTag();
        tag.putString("subtitle", "All the basics");

        JsonArray warnings = TextFieldShapes.normalize(QuestObjectType.CHAPTER, tag);

        assertEquals(1, tag.getList("subtitle", 8).size());
        assertEquals("All the basics", tag.getList("subtitle", 8).getString(0));
        assertEquals(1, warnings.size());
    }

    @Test
    void chapterSubtitleListIsLeftAlone() {
        CompoundTag tag = new CompoundTag();
        tag.put("subtitle", strings("line one", "line two"));

        JsonArray warnings = TextFieldShapes.normalize(QuestObjectType.CHAPTER, tag);

        assertEquals(2, tag.getList("subtitle", 8).size());
        assertEquals(0, warnings.size());
    }

    @Test
    void chapterDescriptionIsDroppedAndReported() {
        CompoundTag tag = new CompoundTag();
        tag.putString("description", "chapters cannot hold this");

        JsonArray warnings = TextFieldShapes.normalize(QuestObjectType.CHAPTER, tag);

        assertFalse(tag.contains("description"));
        assertEquals(1, warnings.size());
    }

    @Test
    void questSubtitleListIsJoinedIntoOneString() {
        CompoundTag tag = new CompoundTag();
        tag.put("subtitle", strings("first", "second"));

        JsonArray warnings = TextFieldShapes.normalize(QuestObjectType.QUEST, tag);

        assertEquals("first\nsecond", tag.getString("subtitle"));
        assertEquals(1, warnings.size());
    }

    @Test
    void questStringFieldsAreLeftAlone() {
        CompoundTag tag = new CompoundTag();
        tag.putString("subtitle", "a subtitle");
        tag.put("description", strings("a", "b"));

        JsonArray warnings = TextFieldShapes.normalize(QuestObjectType.QUEST, tag);

        assertEquals("a subtitle", tag.getString("subtitle"));
        assertEquals(2, tag.getList("description", 8).size());
        assertEquals(0, warnings.size());
    }

    @Test
    void questDescriptionStringBecomesSingleElementList() {
        CompoundTag tag = new CompoundTag();
        tag.putString("description", "one paragraph");

        JsonArray warnings = TextFieldShapes.normalize(QuestObjectType.QUEST, tag);

        assertEquals(1, tag.getList("description", 8).size());
        assertEquals(1, warnings.size());
    }

    @Test
    void otherTypesAreUntouched() {
        CompoundTag tag = new CompoundTag();
        tag.putString("subtitle", "ignored by a task");
        tag.putString("description", "ignored too");

        JsonArray warnings = TextFieldShapes.normalize(QuestObjectType.TASK, tag);

        assertEquals(0, warnings.size());
        assertEquals("ignored by a task", tag.getString("subtitle"));
    }
}
