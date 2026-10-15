package cc.sighs.autojs.neoforge.backend;

import cc.sighs.autojs.eval.EvalResult;
import cc.sighs.autojs.neoforge.rhino.RhinoRuntime;
import cc.sighs.autojs.side.EvalSide;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * A side evaluated by AutoJS's own Rhino kernel, used when KubeJS is not installed.
 *
 * <p>One class covers both sides: the only differences are which event loop the work is handed to and whether
 * that loop exists at all. Each side gets its own {@link RhinoRuntime}, so the two scopes never share a global.
 */
public final class RhinoSideBackend extends AbstractGameThreadBackend implements ReloadableSide {
    private final EvalSide side;
    private final Supplier<Executor> gameThread;
    private final RhinoRuntime runtime;

    public RhinoSideBackend(EvalSide side, Supplier<Executor> gameThread) {
        this.side = side;
        this.gameThread = gameThread;
        this.runtime = new RhinoRuntime(side);
    }

    @Override
    public EvalSide side() {
        return side;
    }

    @Override
    protected Executor gameThread() {
        return gameThread.get();
    }

    @Override
    public boolean isAvailable() {
        return gameThread() != null;
    }

    /**
     * Both sides read the same way — there is no script manager to report — so the interesting part of the
     * answer is which engine this is.
     */
    @Override
    public String describe() {
        return side.id() + " side running AutoJS's own Rhino kernel (KubeJS is not installed), so KubeJS globals, "
                + "events and script files are not available here";
    }

    @Override
    public EvalResult eval(String code, long timeoutMs) {
        var timedOut = EvalResult.failure(side,
                "Timed out after " + timeoutMs + "ms waiting for the " + side.id()
                        + " thread. The game thread is blocked, paused, or running a task that itself waits on this request.",
                List.of(), timeoutMs);
        return submit(() -> runtime.eval(code), timeoutMs, timedOut);
    }

    @Override
    public ReloadOutcome reload(long timeoutMs) {
        var started = System.nanoTime();
        var timedOut = ReloadOutcome.failed("Timed out after " + timeoutMs + "ms waiting for the " + side.id()
                + " thread; the reset may still be running");
        var outcome = submit(() -> {
            runtime.reset();
            return ReloadOutcome.ok("Rebuilt the " + side.id() + " Rhino scope: globals defined by earlier 'eval_js' "
                    + "calls are gone. There are no script files to re-run without KubeJS.");
        }, timeoutMs, timedOut);
        return new ReloadOutcome(outcome.ok(), outcome.message(), (System.nanoTime() - started) / 1_000_000L);
    }
}
