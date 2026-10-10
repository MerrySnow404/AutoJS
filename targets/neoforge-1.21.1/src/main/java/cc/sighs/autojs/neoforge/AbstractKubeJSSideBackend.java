package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.eval.EvalResult;
import cc.sighs.autojs.side.EvalSide;
import dev.latvian.mods.kubejs.script.ScriptManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Marshals work onto the thread that owns a side.
 *
 * <p>Both the client and the server drive their work through an event loop, so one implementation
 * covers either side; a subclass only supplies the executor, the manager and the side's own way of
 * reloading.
 */
public abstract class AbstractKubeJSSideBackend implements KubeJSSideBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS/GameThread");

    /** The event loop of this side, or null when that side is gone. */
    protected abstract Executor gameThread();

    /** Re-runs this side's scripts. Runs on the game thread. */
    protected abstract ReloadOutcome doReload(ScriptManager manager);

    @Override
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

    @Override
    public EvalResult eval(String code, long timeoutMs) {
        var manager = manager();
        if (manager == null) {
            return EvalResult.failure(side(), "KubeJS has no " + side().id() + " script manager in this instance",
                    List.of(), 0L);
        }

        var timedOut = EvalResult.failure(side(),
                "Timed out after " + timeoutMs + "ms waiting for the " + side().id()
                        + " thread. The game thread is blocked, paused, or running a task that itself waits on this request.",
                List.of(), timeoutMs);
        return submit(() -> KubeJSEvaluator.evalWithDiagnostics(side(), manager, code), timeoutMs, timedOut);
    }

    @Override
    public ReloadOutcome reload(long timeoutMs) {
        var manager = manager();
        if (manager == null) {
            return ReloadOutcome.failed("KubeJS has no " + side().id() + " script manager in this instance");
        }

        var started = System.nanoTime();
        var timedOut = ReloadOutcome.failed("Timed out after " + timeoutMs + "ms waiting for the " + side().id()
                + " thread; the reload may still be running");
        var outcome = submit(() -> doReload(manager), timeoutMs, timedOut);
        return new ReloadOutcome(outcome.ok(), outcome.message(), (System.nanoTime() - started) / 1_000_000L);
    }
}
