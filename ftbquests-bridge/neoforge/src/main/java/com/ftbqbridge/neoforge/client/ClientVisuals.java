package com.ftbqbridge.neoforge.client;

import com.ftbqbridge.FtbQuestsBridge;
import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.backend.VisualBackend;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import dev.ftb.mods.ftblibrary.ui.BlankPanel;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.ScreenWrapper;
import dev.ftb.mods.ftblibrary.ui.Widget;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestPositionableButton;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.theme.QuestTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Real FTB GUI inspection. Only PNG encoding and bounded waits occur off the client thread. */
public final class ClientVisuals implements VisualBackend {
    private static final Logger LOG = LoggerFactory.getLogger("ftbquests-bridge");
    private static final long RENDER_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(12);
    private static final long STATE_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(3);
    /** Claude's vision input tops out around this long edge; bigger PNGs are wasted payload. */
    private static final int MAX_IMAGE_EDGE = 1568;
    private static ClientVisuals instance;
    private final Minecraft minecraft = Minecraft.getInstance();
    private final AtomicReference<CaptureOperation> active = new AtomicReference<>();
    private final Semaphore stateReaders = new Semaphore(4);

    private ClientVisuals() {}

    /** Called by client setup enqueueWork, never by dedicated-server class loading. */
    public static void initialize() {
        if (instance != null) return;
        ClientVisuals backend = new ClientVisuals();
        if (!backend.minecraft.isSameThread()) {
            throw new IllegalStateException("ClientVisuals.initialize must run on the client thread");
        }
        NeoForge.EVENT_BUS.addListener(backend::beforeFrame);
        NeoForge.EVENT_BUS.addListener(backend::afterFrame);
        NeoForge.EVENT_BUS.addListener(backend::beforeTick);
        NeoForge.EVENT_BUS.addListener(backend::loggingOut);
        FtbQuestsBridge.installVisualBackend(backend);
        instance = backend;
    }

    @Override
    public JsonObject state() {
        if (minecraft.isSameThread()) return clientState();
        if (!stateReaders.tryAcquire()) throw error(429, "client_busy", "Too many client state requests");
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        long deadline = System.nanoTime() + STATE_TIMEOUT_NS;
        try {
            minecraft.execute(() -> {
                if (result.isDone() || System.nanoTime() >= deadline) return;
                try { result.complete(clientState()); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            return result.get(STATE_TIMEOUT_NS, TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            result.cancel(false);
            throw error(504, "client_timeout", "Client thread did not answer state request");
        } catch (InterruptedException failure) {
            result.cancel(false);
            Thread.currentThread().interrupt();
            throw error(503, "client_interrupted", "Client state request interrupted");
        } catch (ExecutionException failure) {
            throw apiFailure(failure.getCause());
        } finally {
            stateReaders.release();
        }
    }

    @Override
    public JsonObject render(JsonObject request) {
        if (minecraft.isSameThread()) {
            throw error(409, "render_thread_wait", "Render must be requested from an HTTP worker, not the client thread");
        }
        CaptureOperation operation = new CaptureOperation(Request.parse(request));
        if (!active.compareAndSet(null, operation)) {
            throw error(409, "capture_busy", "Another native capture is still capturing, restoring, or encoding");
        }
        try {
            minecraft.execute(() -> begin(operation));
            long remaining = operation.deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            Capture captured = operation.result.get(remaining, TimeUnit.NANOSECONDS);
            try (NativeImage image = captured.image; NativeImage scaled = downscale(image)) {
                checkWorkerDeadline(operation);
                NativeImage output = scaled != null ? scaled : image;
                captured.view.addProperty("width", output.getWidth());
                captured.view.addProperty("height", output.getHeight());
                if (scaled != null) {
                    captured.view.addProperty("sourceWidth", image.getWidth());
                    captured.view.addProperty("sourceHeight", image.getHeight());
                }
                byte[] png = output.asByteArray();
                checkWorkerDeadline(operation);
                JsonObject imageJson = new JsonObject();
                imageJson.addProperty("mimeType", "image/png");
                imageJson.addProperty("data", Base64.getEncoder().encodeToString(png));
                JsonObject response = new JsonObject();
                response.add("image", imageJson);
                response.add("view", captured.view);
                response.addProperty("restored", true);
                checkWorkerDeadline(operation);
                return response;
            }
        } catch (TimeoutException failure) {
            cancelFromWorker(operation);
            throw error(504, "capture_timeout", "Native capture deadline expired; client-thread cleanup is scheduled");
        } catch (InterruptedException failure) {
            cancelFromWorker(operation);
            Thread.currentThread().interrupt();
            throw error(503, "capture_interrupted", "Capture worker interrupted; client-thread cleanup is scheduled");
        } catch (ExecutionException failure) {
            throw apiFailure(failure.getCause());
        } catch (IOException failure) {
            throw error(500, "png_encoding_failed", "Native PNG encoding failed: " + failure.getMessage());
        } finally {
            operation.workerDone = true;
            releaseIfFinished(operation);
        }
    }

    /**
     * Returns a copy whose long edge is {@link #MAX_IMAGE_EDGE}, or null if the capture already fits.
     * Model clients downsample larger images anyway, so sending full 1440p/4K frames only costs bytes.
     */
    private static NativeImage downscale(NativeImage image) {
        int width = image.getWidth(), height = image.getHeight();
        int longEdge = Math.max(width, height);
        if (longEdge <= MAX_IMAGE_EDGE) return null;
        double scale = (double) MAX_IMAGE_EDGE / longEdge;
        NativeImage scaled = new NativeImage(image.format(),
                Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)), false);
        image.resizeSubRectTo(0, 0, width, height, scaled);
        return scaled;
    }

    private static void checkWorkerDeadline(CaptureOperation operation) throws TimeoutException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (System.nanoTime() >= operation.deadline) throw new TimeoutException();
    }

    private void cancelFromWorker(CaptureOperation operation) {
        operation.cancelled = true;
        // If get() lost a race to timeout, ownership of the completed image still needs releasing.
        operation.result.thenAccept(captured -> captured.image.close());
        minecraft.execute(() -> finish(operation, null,
                error(504, "capture_timeout", "Capture cancelled or expired"), true));
    }

    private JsonObject clientState() {
        JsonObject state = new JsonObject();
        ClientQuestFile file = ClientQuestFile.INSTANCE;
        boolean worldLoaded = minecraft.level != null && minecraft.player != null && minecraft.getConnection() != null;
        boolean synchronizedQuests = file != null && ClientQuestFile.exists() && !file.isLoading() && file.selfTeamData != null;
        state.addProperty("clientAvailable", true);
        state.addProperty("worldLoaded", worldLoaded);
        state.addProperty("questsSynchronized", synchronizedQuests);
        state.addProperty("ready", worldLoaded && synchronizedQuests && !file.isDisableGui() && !file.selfTeamData.isLocked());
        state.addProperty("captureBusy", active.get() != null);
        if (!worldLoaded) state.addProperty("unavailableReason", "no_world");
        else if (!synchronizedQuests) state.addProperty("unavailableReason", "quests_not_synchronized");
        else if (file.isDisableGui()) state.addProperty("unavailableReason", "quest_gui_disabled");
        else if (file.selfTeamData.isLocked()) state.addProperty("unavailableReason", "quests_locked");
        state.addProperty("screen", minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());
        state.addProperty("screenTitle", minecraft.screen == null ? "" : minecraft.screen.getTitle().getString());
        if (minecraft.screen instanceof ScreenWrapper wrapper && wrapper.getGui() instanceof QuestScreen gui) {
            JsonObject view = new JsonObject();
            gui.getSelectedChapter().ifPresent(chapter -> {
                view.addProperty("chapterId", hex(chapter.id));
                view.addProperty("chapterTitle", chapter.getTitle().getString());
            });
            if (gui.getViewedQuest() != null) {
                view.addProperty("questId", hex(gui.getViewedQuest().id));
                view.addProperty("questTitle", gui.getViewedQuest().getTitle().getString());
                Panel content = findContentPanel(gui);
                if (content != null) view.addProperty("scroll", content.getScrollY());
            }
            view.addProperty("zoom", Math.round(effectiveZoom(gui)));
            view.addProperty("scrollX", gui.questPanel.getScrollX());
            view.addProperty("scrollY", gui.questPanel.getScrollY());
            state.add("view", view);
        }
        state.add("window", windowState());
        return state;
    }

    private JsonObject windowState() {
        Window window = minecraft.getWindow();
        long handle = window.getWindow();
        JsonObject result = new JsonObject();
        result.addProperty("width", window.getWidth());
        result.addProperty("height", window.getHeight());
        result.addProperty("guiWidth", window.getGuiScaledWidth());
        result.addProperty("guiHeight", window.getGuiScaledHeight());
        result.addProperty("guiScale", window.getGuiScale());
        result.addProperty("focused", GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) != 0);
        result.addProperty("minimized", GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) != 0);
        result.addProperty("visible", GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_VISIBLE) != 0);
        return result;
    }

    private void begin(CaptureOperation operation) {
        if (operation.cleaned) return;
        try {
            requireLive(operation);
            ClientQuestFile file = ClientQuestFile.INSTANCE;
            if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
                throw error(503, "no_world", "A connected client world is required for quest captures");
            }
            if (file == null || !ClientQuestFile.exists() || file.isLoading() || file.selfTeamData == null) {
                throw error(503, "quests_not_synchronized", "ClientQuestFile is not synchronized with a server");
            }
            if (file.isDisableGui() || file.selfTeamData.isLocked()) {
                throw error(409, "quest_gui_unavailable", "Native quest GUI is disabled or locked");
            }
            if (minecraft.getOverlay() != null) throw error(409, "client_overlay", "A loading overlay is covering the client GUI");
            Request request = operation.request;
            operation.quest = request.kind.equals("quest") ? file.getQuest(request.id) : null;
            operation.chapter = request.kind.equals("chapter") ? file.getChapter(request.id)
                    : operation.quest == null ? null : operation.quest.getChapter();
            if (operation.chapter == null) throw ApiException.notFound("No " + request.kind + " with ID " + hex(request.id));
            if (operation.quest != null) {
                operation.pageCount = Math.max(1, operation.quest.buildDescriptionIndex().size());
                if (request.page >= operation.pageCount) throw ApiException.badRequest("page must be less than " + operation.pageCount);
            }
            requireLive(operation);
            operation.file = file;
            operation.level = minecraft.level;
            operation.connection = minecraft.getConnection();
            operation.previousScreen = minecraft.screen;
            operation.previousNoRender = minecraft.noRender;
            operation.window = minecraft.getWindow().getWindow();
            operation.wasMinimized = GLFW.glfwGetWindowAttrib(operation.window, GLFW.GLFW_ICONIFIED) != 0;
            operation.previousTheme = QuestTheme.setFallbackQuestObject(null);
            QuestTheme.setFallbackQuestObject(operation.previousTheme);
            operation.started = true;
            requireLive(operation);
            operation.gui = new QuestScreen(operation.file, null);
            operation.screen = new InspectionScreen(operation);
            // setScreen() closes the player's FTB page and reinitializes it on return.
            // Preserve that object untouched; the disposable native screen owns any restore-resize.
            minecraft.screen = operation.screen;
            Window window = minecraft.getWindow();
            operation.screen.init(minecraft, window.getGuiScaledWidth(), window.getGuiScaledHeight());
            configure(operation);
            requireLive(operation);
            if (operation.wasMinimized) ClientWindow.restore(operation.window);
            // RenderFrameEvent is suppressed by this public flag, including on some minimized clients.
            minecraft.noRender = false;
        } catch (Throwable failure) {
            finish(operation, null, apiFailure(failure), true);
        }
    }

    private void beforeTick(ClientTickEvent.Pre event) {
        CaptureOperation operation = active.get();
        if (operation != null && operation.started && !operation.cleaned) validateOrFinish(operation);
    }

    private void beforeFrame(RenderFrameEvent.Pre event) {
        CaptureOperation operation = active.get();
        if (operation == null || !operation.started || operation.cleaned || !validateOrFinish(operation)) return;
        try {
            Window window = minecraft.getWindow();
            if (GLFW.glfwGetWindowAttrib(operation.window, GLFW.GLFW_ICONIFIED) != 0
                    || window.getWidth() <= 0 || window.getHeight() <= 0) return;
            if (window.getWidth() != operation.frameWidth || window.getHeight() != operation.frameHeight
                    || window.getGuiScaledWidth() != operation.guiWidth || window.getGuiScaledHeight() != operation.guiHeight) {
                requireLive(operation);
                operation.screen.resize(minecraft, window.getGuiScaledWidth(), window.getGuiScaledHeight());
                configure(operation);
            }
            operation.drawnThisFrame = false;
        } catch (Throwable failure) {
            finish(operation, null, apiFailure(failure), true);
        }
    }

    private void configure(CaptureOperation operation) {
        requireLive(operation);
        QuestScreen gui = operation.gui;
        gui.selectChapter(operation.chapter);
        gui.refreshWidgets();
        gui.updateGui(gui.width + 1, gui.height + 1, 0);
        operation.fitLow = 4;
        operation.fitHigh = 28;
        operation.fitBest = 4;
        operation.fitting = operation.request.fit;
        operation.targetZoom = operation.fitting ? 16 : operation.request.zoom;
        operation.navigationApplied = false;
        operation.settledDraws = 0;
        operation.lastZoom = Double.NaN;
        setZoom(gui, operation.targetZoom);
        Window window = minecraft.getWindow();
        operation.frameWidth = window.getWidth();
        operation.frameHeight = window.getHeight();
        operation.guiWidth = window.getGuiScaledWidth();
        operation.guiHeight = window.getGuiScaledHeight();
        operation.completedDraws = 0;
    }

    private void applyNavigation(CaptureOperation operation) {
        requireLive(operation);
        QuestScreen gui = operation.gui;
        Request request = operation.request;
        if (request.centerX != null) {
            gui.questPanel.scrollTo(request.centerX, request.centerY);
        } else if (request.fit) {
            Bounds bounds = pixelBounds(gui);
            double overlay = Math.max(0, gui.getChapterPanelRight() - gui.questPanel.getX());
            gui.questPanel.setScrollX(bounds.centerX() - (gui.questPanel.width + overlay) / 2.0);
            gui.questPanel.setScrollY(bounds.centerY() - gui.questPanel.height / 2.0);
        } else if (operation.quest != null) {
            gui.questPanel.scrollTo(operation.quest.getX(), operation.quest.getY());
        } else {
            gui.questPanel.resetScroll();
        }
        if (!Double.isFinite(gui.questPanel.getScrollX()) || !Double.isFinite(gui.questPanel.getScrollY())) {
            throw ApiException.badRequest("Requested center exceeds the native viewport's representable range");
        }
        if (operation.quest != null) {
            gui.viewQuestPanel.setCurrentPage(operation.quest.id, request.page);
            gui.viewQuest(operation.quest);
            Panel content = findContentPanel(gui);
            if (content == null) throw error(500, "native_layout_failed", "Native quest description content panel was not created");
            operation.maxScroll = Math.max(0, content.getContentHeight() - content.height);
            content.setScrollY(Math.min(request.scroll, operation.maxScroll));
        }
        gui.updateGui(gui.width + 1, gui.height + 1, 0);
        operation.navigationApplied = true;
        operation.completedDraws = 0;
    }

    /** Addons can animate public zoom changes; never measure an intermediate layout. */
    private boolean viewportReady(CaptureOperation operation) {
        QuestScreen gui = operation.gui;
        double zoom = effectiveZoom(gui);
        if (Math.abs(zoom - operation.targetZoom) > 0.0001 || Double.compare(zoom, operation.lastZoom) != 0) {
            operation.lastZoom = zoom;
            operation.settledDraws = 0;
            return false;
        }
        if (++operation.settledDraws < 2) return false;
        if (operation.fitting) {
            Bounds bounds = pixelBounds(gui);
            if (bounds.width() <= availableWidth(gui) - 16 && bounds.height() <= gui.questPanel.height - 16) {
                operation.fitBest = operation.targetZoom;
                operation.fitLow = operation.targetZoom + 1;
            } else {
                operation.fitHigh = operation.targetZoom - 1;
            }
            int target;
            if (operation.fitLow <= operation.fitHigh) {
                target = (operation.fitLow + operation.fitHigh) >>> 1;
            } else {
                operation.fitting = false;
                target = operation.fitBest;
            }
            if (target != operation.targetZoom) {
                operation.targetZoom = target;
                operation.settledDraws = 0;
                operation.lastZoom = Double.NaN;
                setZoom(gui, target);
                return false;
            }
        }
        if (!operation.navigationApplied) {
            applyNavigation(operation);
            return false;
        }
        return ++operation.completedDraws >= 2;
    }

    private void afterFrame(RenderFrameEvent.Post event) {
        CaptureOperation operation = active.get();
        if (operation == null || operation.cleaned || operation.screen == null || !validateOrFinish(operation)) return;
        if (!operation.drawnThisFrame) return;
        NativeImage image = null;
        try {
            requireLive(operation);
            if (!viewportReady(operation)) return;
            if (operation.gui.getSelectedChapter().orElse(null) != operation.chapter
                    || operation.gui.getViewedQuest() != operation.quest) {
                throw error(409, "view_changed", "Native quest selection changed during capture");
            }
            JsonObject view = viewMetadata(operation);
            image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget());
            requireLive(operation);
            Capture capture = new Capture(image, view);
            image = null;
            finish(operation, capture, null, true);
        } catch (Throwable failure) {
            finish(operation, null, apiFailure(failure), true);
        } finally {
            if (image != null) image.close();
        }
    }

    private JsonObject viewMetadata(CaptureOperation operation) {
        QuestScreen gui = operation.gui;
        JsonObject view = new JsonObject();
        view.addProperty("kind", operation.request.kind);
        view.addProperty("id", hex(operation.request.id));
        view.addProperty("chapterId", hex(operation.chapter.id));
        view.addProperty("chapterTitle", operation.chapter.getTitle().getString());
        view.addProperty("zoom", Math.round(effectiveZoom(gui)));
        view.addProperty("guiWidth", operation.guiWidth);
        view.addProperty("guiHeight", operation.guiHeight);
        view.addProperty("guiScale", minecraft.getWindow().getGuiScale());
        view.addProperty("fit", operation.request.fit);
        view.addProperty("scrollX", gui.questPanel.getScrollX());
        view.addProperty("scrollY", gui.questPanel.getScrollY());
        view.addProperty("temporarilyRestoredWindow", operation.wasMinimized);
        view.addProperty("focused", GLFW.glfwGetWindowAttrib(operation.window, GLFW.GLFW_FOCUSED) != 0);
        Bounds bounds = pixelBounds(gui);
        double left = Math.max(0, gui.getChapterPanelRight() - gui.questPanel.getX());
        view.addProperty("clipped", bounds.minX - gui.questPanel.getScrollX() < left
                || bounds.maxX - gui.questPanel.getScrollX() > gui.questPanel.width
                || bounds.minY - gui.questPanel.getScrollY() < 0
                || bounds.maxY - gui.questPanel.getScrollY() > gui.questPanel.height);
        for (Widget widget : gui.questPanel.getWidgets()) {
            if (widget instanceof QuestPositionableButton positioned) {
                double scale = gui.getQuestButtonSize() + gui.getQuestButtonSpacing();
                var position = positioned.getPosition();
                view.addProperty("centerX", position.x() + (gui.questPanel.getScrollX() + gui.questPanel.width / 2.0
                        - widget.posX - widget.width / 2.0) / scale);
                view.addProperty("centerY", position.y() + (gui.questPanel.getScrollY() + gui.questPanel.height / 2.0
                        - widget.posY - widget.height / 2.0) / scale);
                break;
            }
        }
        if (operation.quest != null) {
            view.addProperty("questId", hex(operation.quest.id));
            view.addProperty("questTitle", operation.quest.getTitle().getString());
            view.addProperty("page", operation.request.page);
            view.addProperty("pageCount", operation.pageCount);
            view.addProperty("scroll", findContentPanel(gui).getScrollY());
            view.addProperty("maxScroll", operation.maxScroll);
            view.addProperty("scrollUnit", "gui_pixels");
        }
        return view;
    }

    private boolean validateOrFinish(CaptureOperation operation) {
        try {
            requireLive(operation);
            if (!sameWorld(operation)) throw error(409, "world_changed", "Client world, connection, or synchronized quests changed");
            Screen expected = operation.screen == null ? operation.previousScreen : operation.screen;
            if (minecraft.screen != expected) throw error(409, "screen_changed", "Another screen interrupted native capture");
            if (minecraft.getOverlay() != null) throw error(409, "client_overlay", "An overlay interrupted native capture");
            if (GLFW.glfwWindowShouldClose(operation.window)) throw error(503, "client_stopping", "Minecraft window is closing");
            return true;
        } catch (ApiException failure) {
            finish(operation, null, failure, true);
            return false;
        }
    }


    private void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        CaptureOperation operation = active.get();
        if (operation != null && operation.started && !operation.cleaned) {
            finish(operation, null, error(409, "world_changed", "Client disconnected during capture"), false);
        }
    }

    private boolean sameWorld(CaptureOperation operation) {
        return minecraft.level == operation.level && minecraft.getConnection() == operation.connection
                && minecraft.player != null && ClientQuestFile.INSTANCE == operation.file && ClientQuestFile.exists();
    }

    /** Only this method gives the PNG to the worker, and only after native state restoration. */
    private void finish(CaptureOperation operation, Capture capture, ApiException failure, boolean restoreScreen) {
        if (operation.cleaned || operation.finishing) {
            if (capture != null) capture.image.close();
            return;
        }
        operation.finishing = true;
        try {
            if (operation.started) {
                boolean ownsScreen = operation.screen != null && minecraft.screen == operation.screen;
                boolean sameWorld = sameWorld(operation);
                if (ownsScreen) {
                    boolean restoring = restoreScreen && sameWorld;
                    minecraft.screen = restoring ? operation.previousScreen : null;
                    QuestTheme.setFallbackQuestObject(restoring ? operation.previousTheme : null);
                    Screen previous = operation.previousScreen;
                    Window window = minecraft.getWindow();
                    if (!restoring && previous != null) {
                        // An accepted replacement must run the displaced screen's normal cleanup.
                        previous.removed();
                    } else if (previous != null && (previous.width != window.getGuiScaledWidth()
                            || previous.height != window.getGuiScaledHeight())) {
                        if (previous instanceof ScreenWrapper wrapper && wrapper.getGui() instanceof QuestScreen priorGui) {
                            Panel content = findContentPanel(priorGui);
                            double scroll = content == null ? Double.NaN : content.getScrollY();
                            priorGui.questPanel.withPreservedPos(panel ->
                                    previous.resize(minecraft, window.getGuiScaledWidth(), window.getGuiScaledHeight()));
                            priorGui.updateGui(priorGui.width + 1, priorGui.height + 1, 0);
                            Panel restoredContent = findContentPanel(priorGui);
                            if (Double.isFinite(scroll) && restoredContent != null) {
                                double max = Math.max(0, restoredContent.getContentHeight() - restoredContent.height);
                                restoredContent.setScrollY(Math.min(scroll, max));
                            }
                        } else {
                            previous.resize(minecraft, window.getGuiScaledWidth(), window.getGuiScaledHeight());
                        }
                    }
                } else if (operation.screen == null && minecraft.screen == operation.previousScreen && sameWorld) {
                    QuestTheme.setFallbackQuestObject(operation.previousTheme);
                }
                minecraft.noRender = operation.previousNoRender;
                if (minecraft.getWindow().getWindow() == operation.window && !GLFW.glfwWindowShouldClose(operation.window)) {
                    if (operation.wasMinimized) ClientWindow.minimize(operation.window);
                }
                if (capture != null && (!ownsScreen || !sameWorld || !restoreScreen
                        || minecraft.screen != operation.previousScreen)) {
                    failure = error(409, "restoration_interrupted", "Prior GUI could not be restored without reviving an obsolete screen");
                }
            }
        } catch (Throwable cleanupFailure) {
            LOG.error("[ftbquests-bridge] Native visual cleanup failed", cleanupFailure);
            failure = error(500, "restoration_failed", "Native GUI restoration failed: " + cleanupFailure.getMessage());
        } finally {
            operation.cleaned = true;
            operation.finishing = false;
            if (failure != null || capture == null || operation.cancelled || System.nanoTime() >= operation.deadline) {
                if (capture != null) capture.image.close();
                operation.result.completeExceptionally(failure != null ? failure
                        : error(504, "capture_timeout", "Capture expired before restoration completed"));
            } else if (!operation.result.complete(capture)) {
                capture.image.close();
            }
            releaseIfFinished(operation);
        }
    }

    private void releaseIfFinished(CaptureOperation operation) {
        if (operation.cleaned && operation.workerDone) active.compareAndSet(operation, null);
    }

    private static void requireLive(CaptureOperation operation) {
        if (operation.cleaned) throw error(409, "capture_cancelled", "Capture has already been cleaned up");
        if (operation.cancelled || System.nanoTime() >= operation.deadline) {
            throw error(504, "capture_timeout", "Capture expired before the next native action");
        }
    }

    private static double effectiveZoom(QuestScreen gui) {
        // Both native FTB and CQA's animated geometry expose actual size as zoom * 3 / 2.
        return gui.getQuestButtonSize() * 2.0 / 3.0;
    }

    private static void setZoom(QuestScreen gui, int zoom) {
        double current = effectiveZoom(gui);
        if (Math.abs(zoom - current) > 0.0001) gui.addZoom((zoom - current) / 4.0);
    }

    private static int availableWidth(QuestScreen gui) {
        return gui.questPanel.width - Math.max(0, gui.getChapterPanelRight() - gui.questPanel.getX());
    }

    private static Bounds pixelBounds(QuestScreen gui) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Widget widget : gui.questPanel.getWidgets()) {
            if (widget instanceof QuestPositionableButton && widget.shouldDraw()) {
                minX = Math.min(minX, widget.posX);
                minY = Math.min(minY, widget.posY);
                maxX = Math.max(maxX, widget.posX + widget.width);
                maxY = Math.max(maxY, widget.posY + widget.height);
            }
        }
        return minX == Double.POSITIVE_INFINITY ? new Bounds(0, 0, 0, 0) : new Bounds(minX, minY, maxX, maxY);
    }

    private static Panel findContentPanel(QuestScreen gui) {
        // ViewQuestPanel names this direct child "ContentPanel" (checked in FTB Quests 2101.1.31 and 2101.1.38).
        // BlankPanel exposes that stable ID via public toString(); no private-field accessor is needed.
        for (Widget widget : gui.viewQuestPanel.getWidgets()) {
            if (widget instanceof BlankPanel panel && "ContentPanel".equals(panel.toString())) return panel;
        }
        return null;
    }

    private static String hex(long id) { return String.format(Locale.ROOT, "%016X", id); }
    private static ApiException error(int status, String type, String message) { return new ApiException(status, type, message); }
    private static ApiException apiFailure(Throwable failure) {
        if (failure instanceof ApiException api) return api;
        LOG.error("[ftbquests-bridge] Native visual operation failed", failure);
        return error(500, "native_capture_failed", String.valueOf(failure == null ? "Unknown native capture failure" : failure.getMessage()));
    }

    private record Bounds(double minX, double minY, double maxX, double maxY) {
        double width() { return maxX - minX; }
        double height() { return maxY - minY; }
        double centerX() { return (minX + maxX) / 2; }
        double centerY() { return (minY + maxY) / 2; }
    }
    private record Capture(NativeImage image, JsonObject view) {}

    private static final class CaptureOperation {
        final Request request;
        final long deadline = System.nanoTime() + RENDER_TIMEOUT_NS;
        final CompletableFuture<Capture> result = new CompletableFuture<>();
        volatile boolean cancelled;
        volatile boolean cleaned;
        volatile boolean workerDone;
        boolean started;
        boolean finishing;
        boolean previousNoRender;
        boolean wasMinimized;
        boolean drawnThisFrame;
        int completedDraws;
        int targetZoom, settledDraws, fitLow, fitHigh, fitBest;
        boolean fitting, navigationApplied;
        double lastZoom;
        int frameWidth, frameHeight, guiWidth, guiHeight, pageCount;
        double maxScroll;
        long window;
        ClientLevel level;
        ClientPacketListener connection;
        ClientQuestFile file;
        Chapter chapter;
        Quest quest;
        QuestObjectBase previousTheme;
        Screen previousScreen;
        QuestScreen gui;
        InspectionScreen screen;
        CaptureOperation(Request request) { this.request = request; }
    }

    /** Native drawing with no route from player input to tasks, rewards, authoring, or hotkeys. */
    private final class InspectionScreen extends ScreenWrapper {
        private final CaptureOperation operation;
        InspectionScreen(CaptureOperation operation) { super(operation.gui); this.operation = operation; }
        @Override public boolean isPauseScreen() {
            return operation.previousScreen != null && operation.previousScreen.isPauseScreen();
        }
        @Override public void tick() {
            if (!validateOrFinish(operation)) return;
            try { super.tick(); }
            catch (Throwable failure) { finish(operation, null, apiFailure(failure), true); }
        }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!validateOrFinish(operation)) return;
            try {
                super.render(graphics, operation.gui.width + 1, operation.gui.height + 1, partialTick);
                operation.drawnThisFrame = true;
            } catch (Throwable failure) {
                finish(operation, null, apiFailure(failure), true);
            }
        }
        // Do not call FTB onClosed(): the disposable screen must never persist navigation.
        @Override public void removed() {
            finish(operation, null, error(409, "screen_changed", "Native capture screen was removed"), false);
        }
        @Override public void onClose() { interrupt(); }
        @Override public boolean shouldCloseOnEsc() { return false; }
        private boolean interrupt() {
            finish(operation, null, error(409, "player_input", "Player input interrupted native capture"), true);
            return true;
        }
        @Override public boolean mouseClicked(double x, double y, int button) { return interrupt(); }
        @Override public boolean mouseReleased(double x, double y, int button) {
            interrupt();
            Screen previous = operation.previousScreen;
            // An unhandled gameplay release lets MouseHandler clear its own button/key state.
            return previous != null && minecraft.screen == previous && previous.mouseReleased(x, y, button);
        }
        @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) { return interrupt(); }
        @Override public boolean mouseScrolled(double x, double y, double dx, double dy) { return interrupt(); }
        @Override public boolean keyPressed(int key, int scanCode, int modifiers) { return interrupt(); }
        @Override public boolean keyReleased(int key, int scanCode, int modifiers) {
            interrupt();
            Screen previous = operation.previousScreen;
            return previous != null && minecraft.screen == previous && previous.keyReleased(key, scanCode, modifiers);
        }
        @Override public boolean charTyped(char character, int modifiers) { return interrupt(); }
        @Override public void onFilesDrop(java.util.List<java.nio.file.Path> paths) { interrupt(); }
    }

    private record Request(String kind, long id, int zoom, Double centerX, Double centerY,
                           boolean fit, int page, double scroll) {
        private static final Set<String> FIELDS = Set.of("kind", "id", "zoom", "centerX", "centerY", "fit", "page", "scroll");
        static Request parse(JsonObject object) {
            if (object == null) throw ApiException.badRequest("Render request must be an object");
            for (String field : object.keySet()) {
                if (!FIELDS.contains(field)) throw ApiException.badRequest("Unknown render field: " + field);
            }
            String kind = string(object, "kind");
            if (!kind.equals("chapter") && !kind.equals("quest")) throw ApiException.badRequest("kind must be chapter or quest");
            String id = string(object, "id");
            if (!id.matches("[0-9a-fA-F]{16}")) throw ApiException.badRequest("id must contain exactly 16 hexadecimal digits");
            int zoom = integer(object, "zoom", 16, 4, 28);
            int page = integer(object, "page", 0, 0, Integer.MAX_VALUE);
            Double centerX = object.has("centerX") ? number(object, "centerX") : null;
            Double centerY = object.has("centerY") ? number(object, "centerY") : null;
            if ((centerX == null) != (centerY == null)) throw ApiException.badRequest("centerX and centerY must be supplied together");
            double scroll = object.has("scroll") ? number(object, "scroll") : 0;
            if (scroll < 0) throw ApiException.badRequest("scroll must be nonnegative");
            // A bare chapter request shows the whole chapter; asking for a zoom or a center opts out.
            boolean fit = kind.equals("chapter") && !object.has("zoom") && !object.has("centerX");
            if (object.has("fit")) {
                JsonElement value = object.get("fit");
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw ApiException.badRequest("fit must be boolean");
                fit = value.getAsBoolean();
            }
            return new Request(kind, Long.parseUnsignedLong(id, 16), zoom, centerX, centerY, fit, page, scroll);
        }
        private static String string(JsonObject object, String field) {
            JsonElement value = object.get(field);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw ApiException.badRequest(field + " must be a string");
            }
            return value.getAsString();
        }
        private static double number(JsonObject object, String field) {
            JsonElement value = object.get(field);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw ApiException.badRequest(field + " must be a finite number");
            }
            double number;
            try { number = value.getAsDouble(); }
            catch (NumberFormatException failure) { throw ApiException.badRequest(field + " must be a finite number"); }
            if (!Double.isFinite(number)) throw ApiException.badRequest(field + " must be a finite number");
            return number;
        }
        private static int integer(JsonObject object, String field, int fallback, int min, int max) {
            if (!object.has(field)) return fallback;
            double value = number(object, field);
            if (value != Math.rint(value) || value < min || value > max) {
                throw ApiException.badRequest(field + " must be an integer in [" + min + "," + max + "]");
            }
            return (int) value;
        }
    }
}
