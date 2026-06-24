package com.ftbqbridge.backend.ftbq;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Splits a create/edit {@code properties} object into its translatable text fields and the
 * remaining NBT-bound properties.
 *
 * <p>Background (ftb-quests 2101.x): title/subtitle/description text is <em>not</em> part of an
 * object's {@code writeData}/{@code readData} NBT — it lives in the server-side
 * {@code TranslationManager}, keyed by object id, written via
 * {@code setRawTitle}/{@code setRawSubtitle}/{@code setRawDescription}. Passing those keys through
 * {@code readData} is a no-op, which is why title writes were silently discarded (issue #12).
 *
 * <p>This helper isolates the pure partition/coercion so the Minecraft-coupled apply step in
 * {@code FtbQuestsBackend} stays thin. {@link #title} is the text to route through
 * {@code setRawTitle}; {@link #remaining} is what's safe to feed to {@code readData}; and
 * {@link #unsupported} lists translation keys this bridge does not yet persist (surfaced as
 * response warnings so callers know they were dropped rather than silently lost).
 */
public final class TranslationFields {
    public static final String TITLE = "title";
    public static final String SUBTITLE = "subtitle";
    public static final String DESCRIPTION = "description";

    /** Title text to apply via {@code setRawTitle}, or {@code null} when absent (or explicitly JSON null). */
    public final String title;
    /** Recognized translation keys present in the input that this bridge does not persist yet. */
    public final List<String> unsupported;
    /** The input with every recognized translation key removed — safe to pass to {@code readData}. */
    public final JsonObject remaining;

    private TranslationFields(String title, List<String> unsupported, JsonObject remaining) {
        this.title = title;
        this.unsupported = unsupported;
        this.remaining = remaining;
    }

    public static TranslationFields from(JsonObject properties) {
        String title = null;
        List<String> unsupported = new ArrayList<>();
        JsonObject remaining = new JsonObject();
        if (properties != null) {
            for (Map.Entry<String, JsonElement> e : properties.entrySet()) {
                switch (e.getKey()) {
                    // An explicit JSON null is treated as "field absent" rather than "clear the title".
                    case TITLE -> { if (!e.getValue().isJsonNull()) title = asString(e.getValue()); }
                    case SUBTITLE, DESCRIPTION -> unsupported.add(e.getKey());
                    default -> remaining.add(e.getKey(), e.getValue());
                }
            }
        }
        return new TranslationFields(title, List.copyOf(unsupported), remaining);
    }

    /**
     * Coerce a JSON value to a single string: a JSON array (FTB stores multi-line text as a list of
     * lines) is newline-joined; {@code null}/JSON-null becomes {@code ""}; anything else uses its
     * string form.
     */
    public static String asString(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            var arr = el.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                if (i > 0) sb.append('\n');
                JsonElement item = arr.get(i);
                if (!item.isJsonNull()) sb.append(item.getAsString());
            }
            return sb.toString();
        }
        return el.getAsString();
    }
}
