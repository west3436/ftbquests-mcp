package com.ftbqbridge.backend.ftbq;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link TranslationFields}, the part of the title/subtitle/description write
 * path (issue #12) that needs no Minecraft runtime. The actual setRawTitle apply step is a thin
 * MC adapter in {@code FtbQuestsBackend}, verified at compile/live time.
 */
class TranslationFieldsTest {

    private static JsonObject props(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        return o;
    }

    @Test void extractsTitleAndDropsItFromRemaining() {
        TranslationFields tf = TranslationFields.from(props("title", "Day One", "icon", "minecraft:stone"));
        assertEquals("Day One", tf.title);
        assertFalse(tf.remaining.has("title"), "title must not reach readData");
        assertTrue(tf.remaining.has("icon"), "non-translation props must survive");
        assertTrue(tf.unsupported.isEmpty());
    }

    @Test void absentTitleIsNull() {
        TranslationFields tf = TranslationFields.from(props("icon", "minecraft:stone"));
        assertNull(tf.title);
    }

    @Test void jsonNullTitleIsTreatedAsAbsent() {
        JsonObject o = new JsonObject();
        o.add("title", JsonNull.INSTANCE);
        TranslationFields tf = TranslationFields.from(o);
        assertNull(tf.title, "an explicit null title should not clear the title");
        assertFalse(tf.remaining.has("title"));
    }

    @Test void emptyStringTitleIsAppliedNotIgnored() {
        TranslationFields tf = TranslationFields.from(props("title", ""));
        assertEquals("", tf.title);
    }

    @Test void subtitleAndDescriptionAreReportedUnsupportedNotPersisted() {
        TranslationFields tf = TranslationFields.from(props("subtitle", "x", "description", "y", "icon", "z"));
        assertNull(tf.title);
        assertTrue(tf.unsupported.contains("subtitle"));
        assertTrue(tf.unsupported.contains("description"));
        assertFalse(tf.remaining.has("subtitle"), "unsupported translation fields must not reach readData");
        assertFalse(tf.remaining.has("description"));
        assertTrue(tf.remaining.has("icon"));
    }

    @Test void nullPropertiesYieldEmptyResult() {
        TranslationFields tf = TranslationFields.from(null);
        assertNull(tf.title);
        assertTrue(tf.unsupported.isEmpty());
        assertEquals(0, tf.remaining.size());
    }

    @Test void asStringJoinsArrayWithNewlines() {
        JsonArray a = new JsonArray();
        a.add("line one");
        a.add("line two");
        assertEquals("line one\nline two", TranslationFields.asString(a));
    }

    @Test void asStringOfNullOrJsonNullIsEmpty() {
        assertEquals("", TranslationFields.asString(null));
        assertEquals("", TranslationFields.asString(JsonNull.INSTANCE));
    }
}
