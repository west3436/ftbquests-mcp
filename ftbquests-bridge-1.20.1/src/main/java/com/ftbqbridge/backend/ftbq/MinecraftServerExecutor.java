package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.backend.ServerTaskExecutor;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Marshals each backend operation onto the server main thread.
 *
 * <p>FTB Quests is not thread-safe: its id map, chapter lists and team data are mutated from the
 * server tick, so every bridge read and write is submitted to {@link MinecraftServer#submit} and
 * awaited. The timeout keeps a hung server from wedging the HTTP worker thread forever.
 *
 * <p><b>Timeout semantics.</b> On timeout the queued task is interrupted with
 * {@code future.cancel(true)} and the caller gets a 504. Cancellation only helps a task that has
 * not started yet; a write already running on the server thread cannot be safely unwound and will
 * usually still complete. A 504 therefore means "outcome unknown", not "nothing changed" — re-read
 * the object before retrying a mutation.
 */
public final class MinecraftServerExecutor implements ServerTaskExecutor {
    private static final Logger LOG = LoggerFactory.getLogger("ftbquests-bridge");

    private final MinecraftServer server;
    private final long timeoutMs;

    public MinecraftServerExecutor(MinecraftServer server, long timeoutMs) {
        this.server = server;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public <T> T call(Callable<T> task) {
        if (ServerQuestFile.INSTANCE == null) throw ApiException.notLoaded();

        CompletableFuture<T> future = server.submit(() -> {
            try {
                return task.call();
            } catch (ApiException expected) {
                throw expected; // 400/404/503/504 are control flow, not diagnostics
            } catch (Throwable t) {
                // Throwable, not Exception: a client-only method reached on a dedicated server throws
                // NoSuchMethodError, which must still become a clean 500 instead of killing the task.
                LOG.error("[ftbquests-bridge] backend task failed", t);
                throw ApiException.internal(String.valueOf(t.getMessage()));
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw ApiException.serverBusy();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException api) throw api;
            throw ApiException.internal(String.valueOf(cause == null ? e.getMessage() : cause.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.internal("interrupted");
        }
    }
}
