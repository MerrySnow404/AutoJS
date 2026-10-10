package cc.sighs.autojs.side;

import cc.sighs.autojs.eval.EvalResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One game side that can evaluate JavaScript.
 *
 * <p>Implemented by the target, because reaching the KubeJS script manager and the thread that owns
 * it is loader and version specific.
 */
public interface EvalBackend {
    EvalSide side();

    /** Whether this side can evaluate right now. Cheap; called on every request. */
    boolean isAvailable();

    /** Human readable state, surfaced by the status tool. */
    String describe();

    /** Evaluates on the game thread. Blocking; must not be called from the game thread itself. */
    EvalResult eval(String code, long timeoutMs);

    /**
     * Waits for work that was handed to the game thread. Kept here so both sides report timeouts and
     * interrupts identically.
     */
    default EvalResult await(CompletableFuture<EvalResult> future, long timeoutMs) {
        try {
            return future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return EvalResult.failure(side(),
                    "Timed out after " + timeoutMs + "ms waiting for the " + side().id()
                            + " thread. The game thread is blocked, paused, or waiting on a task that itself waits on this request.",
                    List.of(), timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EvalResult.failure(side(), "Interrupted while waiting for the " + side().id() + " thread", List.of(), 0L);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            return EvalResult.failure(side(),
                    "Evaluating on the " + side().id() + " thread failed: " + (cause == null ? e.toString() : cause.toString()),
                    List.of(), 0L);
        }
    }
}
