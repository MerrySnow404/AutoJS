package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.eval.EvalResult;
import cc.sighs.autojs.neoforge.backend.AbstractGameThreadBackend;
import dev.latvian.mods.kubejs.script.ScriptManager;

import java.util.List;

/**
 * The KubeJS half of a side backend: the manager to evaluate in, and KubeJS's own way of reloading.
 *
 * <p>Scheduling is not KubeJS specific, so it lives in {@link AbstractGameThreadBackend}; what is left here is
 * what makes a side a KubeJS one.
 */
public abstract class AbstractKubeJSSideBackend extends AbstractGameThreadBackend implements KubeJSSideBackend {
    /** Re-runs this side's scripts. Runs on the game thread. */
    protected abstract ReloadOutcome doReload(ScriptManager manager);

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
