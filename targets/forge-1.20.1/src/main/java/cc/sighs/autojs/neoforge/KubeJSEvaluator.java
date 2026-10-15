package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.eval.EvalResult;
import cc.sighs.autojs.neoforge.eval.ScriptValue;
import cc.sighs.autojs.side.EvalSide;
import dev.latvian.mods.kubejs.script.ScriptManager;

import java.util.List;

/**
 * Runs a snippet in a KubeJS script context.
 *
 * <p>KubeJS has no public eval API of its own. On the 2101 generation this mirrors what its {@code /kubejs
 * eval} command does with {@code ScriptManager#contextFactory} and {@code KubeJSContext#topLevelScope}; on
 * this one it is even more direct, because {@code ScriptManager} keeps the context and the scope itself:
 * {@code ScriptManager#context} is the {@code Context} that {@code load()} built, and
 * {@code ScriptManager#topLevelScope} is the {@code initStandardObjects()} scope that goes with it. Since
 * {@code Context.enter()} here just constructs a context (it keeps no thread-local of its own), the one the
 * manager holds is the one every script of that side already ran in.
 *
 * <p>Must be called on the thread that owns the side, which is why the callers hand the work to the
 * server or client event loop first. Evaluating from any other thread would write into a scope that thread
 * does not own, and KubeJS's own state is read and written without synchronisation.
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
     * the {@link cc.sighs.autojs.engine.ScriptEngine}'s decision. What this method does with it is the two
     * things that have to happen per snippet either way: the sandbox install is repeated — it marks the context
     * it lifted, so repeating it on an already-lifted one costs a property read — and KubeJS's
     * {@code canListenEvents}, which forbids event registration outside its loading phase and is reset by the
     * load, is set again for the duration of the eval.
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
        if (manager.context == null) {
            return EvalResult.failure(side, "KubeJS " + side.id() + " scripts have not been loaded yet",
                    List.of(), elapsed(started));
        }

        try {
            var context = manager.context;
            if (unrestricted) {
                // The load-time install is what lifts the sandbox for the script files themselves; repeating it
                // here is what survives a KubeJS that rebuilt its context after that install ran. Both calls are
                // free once the context carries their mark.
                AutoJSUnrestricted.install(manager);
                AutoJSUnrestricted.installPermissivePaths(manager);
            }
            var previousListen = manager.canListenEvents;
            if (unrestricted) {
                // KubeJS refuses event registration outside its loading phase. For a debugging tool that
                // refusal is only a silent no-op, so it is lifted for the duration of the snippet.
                manager.canListenEvents = true;
            }
            try {
                var value = context.evaluateString(manager.topLevelScope, code, "autojs-eval", 1, null);
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
