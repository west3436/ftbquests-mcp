package com.ftbqbridge.backend;

import com.google.gson.JsonObject;

/**
 * Screenshots of the native FTB Quests GUI. Implemented only on a game client (it needs the client's
 * framebuffer); called from HTTP worker threads, so implementations marshal onto the client thread.
 */
public interface VisualBackend {
    /** Whether the client can capture right now (world loaded, quests synced) plus the current screen and window. */
    JsonObject state();
    /** Captures a chapter or quest view and returns {@code {image:{mimeType,data}, view:{...}, restored:true}}. */
    JsonObject render(JsonObject request);
}
