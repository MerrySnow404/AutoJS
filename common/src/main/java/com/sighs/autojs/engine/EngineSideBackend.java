package com.sighs.autojs.engine;

import com.sighs.autojs.eval.EvalResult;
import com.sighs.autojs.neoforge.backend.AbstractGameThreadBackend;
import com.sighs.autojs.neoforge.backend.ReloadableSide;

import java.util.List;

/**
 * The half of a side backend that does not depend on which engine runs the scripts.
 *
 * <p>Every side of every target reads the same way: hand the work to the thread that owns the side, wait for
 * it, and turn "the game thread never answered" into the same timeout. Only the engine and the wording of
 * {@code describe()} differ, so those are what a subclass supplies. Keeping this in {@code common} is what
 * stops each new target from carrying its own copy of the dispatch and timeout rules.
 */
public abstract class EngineSideBackend extends AbstractGameThreadBackend implements ReloadableSide {
    /**
     * The engine that evaluates for this side.
     *
     * <p>Called on every request, so it must hand back the same instance every time.
     */
    protected abstract ScriptEngine engine();

    @Override
    public boolean isAvailable() {
        return gameThread() != null && engine().available(side());
    }

    @Override
    public EvalResult eval(String code, long timeoutMs) {
        return submit(() -> engine().eval(side(), code), timeoutMs, EvalResult.failure(side(),
                "Timed out after " + timeoutMs + "ms waiting for the " + side().id()
                        + " thread. The game thread is blocked, paused, or running a task that itself waits on this request.",
                List.of(), timeoutMs));
    }

    @Override
    public ReloadOutcome reload(long timeoutMs) {
        var started = System.nanoTime();
        var timedOut = ReloadOutcome.failed("Timed out after " + timeoutMs + "ms waiting for the " + side().id()
                + " thread; " + reloadTimeoutHint());
        var outcome = submit(this::doReload, timeoutMs, timedOut);
        return new ReloadOutcome(outcome.ok(), outcome.message(), (System.nanoTime() - started) / 1_000_000L);
    }

    /**
     * What a reload timeout means for this engine, so each engine keeps its own wording: a KubeJS side may
     * still be re-running its script files, while the Rhino side may still be rebuilding a scope.
     */
    protected String reloadTimeoutHint() {
        return "the reload may still be running";
    }

    /**
     * Reloads on the game thread.
     *
     * <p>The engine's own reload by default. A side that has to reach past the engine overrides this instead of
     * growing a second reload path: KubeJS's client side additionally re-collects its item tooltip cache, and
     * keeping that call in the client backend is also what keeps the client-only class off a dedicated server.
     */
    protected ReloadOutcome doReload() {
        return engine().reload(side());
    }
}
