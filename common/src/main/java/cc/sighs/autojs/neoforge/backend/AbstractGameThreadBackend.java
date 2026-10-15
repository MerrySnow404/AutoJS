package cc.sighs.autojs.neoforge.backend;

import cc.sighs.autojs.side.EvalBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Marshals work onto the thread that owns a side.
 *
 * <p>Both the client and the server drive their work through an event loop, so one implementation covers
 * either side and a subclass only supplies that executor. Nothing here mentions an engine: the same
 * scheduling serves a KubeJS context and the standalone Rhino one.
 */
public abstract class AbstractGameThreadBackend implements EvalBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS/GameThread");

    /** The event loop of this side, or null when that side is gone. */
    protected abstract Executor gameThread();

    /** Runs work on the thread that owns this side and waits for it, returning {@code onTimeout} if it does not. */
    public <T> T submit(Callable<T> work, long timeoutMs, T onTimeout) {
        var executor = gameThread();
        if (executor == null) {
            return onTimeout;
        }

        var future = new CompletableFuture<T>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(work.call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RuntimeException e) {
            LOGGER.error("Could not schedule work on the {} thread", side().id(), e);
            return onTimeout;
        }

        try {
            return future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return onTimeout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return onTimeout;
        } catch (ExecutionException e) {
            LOGGER.error("Work on the {} thread failed", side().id(), e.getCause());
            return onTimeout;
        }
    }
}
