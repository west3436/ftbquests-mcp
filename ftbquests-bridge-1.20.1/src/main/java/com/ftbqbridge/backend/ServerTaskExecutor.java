package com.ftbqbridge.backend;

import java.util.concurrent.Callable;

/** Runs a task on the Minecraft server main thread, blocking the caller until it completes. */
public interface ServerTaskExecutor {
    <T> T call(Callable<T> task);
}
