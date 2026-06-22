package com.ftbqbridge.backend.ftbq;

import com.ftbqbridge.backend.ApiException;
import com.ftbqbridge.backend.ServerTaskExecutor;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Marshals each backend operation onto the server main thread.
 *
 * <p>API note (verified against ftb-quests 2101.1.27): the server quest file is exposed as the
 * public static field {@code ServerQuestFile.INSTANCE} (there is no {@code getInstance()}/{@code exists()}
 * accessor on this build), and {@code ServerQuestFile.server} is the public {@link MinecraftServer}.
 */
public final class MinecraftServerExecutor implements ServerTaskExecutor {
    private static final Logger LOG = LoggerFactory.getLogger("ftbquests-bridge");
    private final long timeoutMs;
    public MinecraftServerExecutor(long timeoutMs) { this.timeoutMs = timeoutMs; }

    @Override public <T> T call(Callable<T> task) {
        ServerQuestFile instance = ServerQuestFile.INSTANCE;
        if (instance == null) throw ApiException.notLoaded();
        MinecraftServer server = instance.server;
        CompletableFuture<T> fut = server.submit(() -> {
            try { return task.call(); }
            catch (ApiException ae) { throw ae; } // expected control flow (400/404/...) — don't log
            // Throwable (not just Exception) so a stripped client-only method's NoSuchMethodError is
            // caught, logged with a stack trace (diagnosability), and mapped to a clean 500.
            catch (Throwable e) {
                LOG.error("[ftbquests-bridge] backend task failed", e);
                throw ApiException.internal(String.valueOf(e.getMessage()));
            }
        });
        try {
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            throw ApiException.serverBusy();
        } catch (ExecutionException ee) {
            Throwable c = ee.getCause();
            if (c instanceof ApiException ae) throw ae;
            throw ApiException.internal(String.valueOf(c == null ? ee.getMessage() : c.getMessage()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ApiException.internal("interrupted");
        }
    }
}
