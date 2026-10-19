package com.sighs.autojs.neoforge;

import com.sighs.autojs.eval.EvalResult;
import com.sighs.autojs.neoforge.eval.ScriptValue;
import com.sighs.autojs.side.EvalSide;
import dev.latvian.mods.kubejs.script.KubeJSContext;
import dev.latvian.mods.kubejs.script.ScriptManager;

import java.util.List;

/**
 * Runs a snippet in a KubeJS script context.
 *
 * <p>KubeJS has no public eval API of its own; this mirrors what its own {@code /kubejs eval} command
 * does with the two public pieces it exposes, {@link ScriptManager#contextFactory} and
 * {@link KubeJSContext#topLevelScope}.
 *
 * <p>Must be called on the thread that owns the side, which is why the callers hand the work to the
 * server or client event loop first. The context is thread local, so evaluating from any other thread
 * would silently get a fresh, empty scope.
 *
 * <p>What the value is and how it prints is {@link ScriptValue}'s business, because the Rhino kernel needs
 * exactly the same answers.
 */
public final class KubeJSEvaluator {
    private KubeJSEvaluator() {
    }

    /**
     * Evaluates, and attaches whatever the side logged while doing so.
     *
     * <p>The KubeJS log is the only witness for failures it refuses to raise, such as an event
     * registration attempted outside the loading phase, so the caller gets them as diagnostics instead
     * of a misleading success. It is read from the log file rather than the in-memory queues, which are
     * only filled while KubeJS has error capturing switched on.
     *
     * <p>{@code unrestricted} is the engine's answer, not this class's: whether KubeJS's sandbox is lifted is
     * the {@link com.sighs.autojs.engine.ScriptEngine}'s decision.
     */
    public static EvalResult evalWithDiagnostics(EvalSide side, ScriptManager manager, String code, boolean unrestricted) {
        if (manager == null) {
            return eval(side, manager, code, unrestricted);
        }
        var mark = KubeJSConsole.mark(manager);
        var result = eval(side, manager, code, unrestricted);
        var added = KubeJSConsole.since(manager, mark);
        return added == null ? result : result.withDiagnostics(added);
    }

    public static EvalResult eval(EvalSide side, ScriptManager manager, String code, boolean unrestricted) {
        var started = System.nanoTime();
        if (manager == null) {
            return EvalResult.failure(side, "KubeJS has no " + side.id() + " script manager in this instance yet",
                    List.of(), elapsed(started));
        }
        if (manager.contextFactory == null) {
            return EvalResult.failure(side, "KubeJS " + side.id() + " scripts have not been loaded yet",
                    List.of(), elapsed(started));
        }

        try {
            var context = (KubeJSContext) manager.contextFactory.enter();
            var previousListen = manager.canListenEvents;
            if (unrestricted) {
                // KubeJS refuses event registration outside its loading phase. For a debugging tool that
                // refusal is only a silent no-op, so it is lifted for the duration of the snippet.
                manager.canListenEvents = true;
            }
            try {
                var value = context.evaluateString(context.topLevelScope, code, "autojs-eval", 1, null);
                return EvalResult.ok(side, ScriptValue.typeOf(context, value), ScriptValue.stringify(context, value),
                        elapsed(started));
            } finally {
                manager.canListenEvents = previousListen;
            }
        } catch (Throwable t) {
            return EvalResult.failure(side, ScriptValue.describe(t), ScriptValue.stackOf(t), elapsed(started));
        }
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
