package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.eval.EvalResult;
import cc.sighs.autojs.neoforge.eval.ScriptValue;
import cc.sighs.autojs.neoforge.rhino.RhinoContexts;
import cc.sighs.autojs.side.EvalSide;
import dev.latvian.mods.kubejs.script.ScriptManager;

import java.util.List;

/**
 * Runs a snippet in a KubeJS script context.
 *
 * <p>KubeJS has no public eval API of its own, so this reproduces what its own scripts already did:
 * {@code ScriptManager.load()} builds one context for the whole load, gives every {@code ScriptPack} a scope of
 * its own out of it, and evaluates that pack's script files in it. A snippet goes to the same place — the
 * context and the scope of the pack this side's {@code kubejs/<side>_scripts} was loaded into — which is why a
 * global a snippet defines survives into the next snippet and why it can see everything the side's own scripts
 * defined.
 *
 * <p>That the context and the scope hang off the {@code ScriptPack} rather than off the manager is the one thing
 * this file owes to the 1802 generation: 1902 keeps both on the manager itself, so its evaluator has nothing to
 * look up. See {@link KubeJSScriptManagers#packOf(ScriptManager)}.
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
     * <p>The KubeJS log is the only witness for failures it refuses to raise, so the caller gets them as
     * diagnostics instead of a misleading success. It is read from the log file rather than the in-memory
     * queues, which are only filled while KubeJS has error capturing switched on.
     *
     * <p>{@code unrestricted} is the engine's answer, not this class's: whether KubeJS's sandbox is lifted is
     * the {@link cc.sighs.autojs.engine.ScriptEngine}'s decision. What this method does with it is repeat the
     * two installs per snippet, so that a context KubeJS rebuilt after the load-time install ran is still
     * lifted. Both are free once the scope carries their mark.
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

        var pack = KubeJSScriptManagers.packOf(manager);
        if (pack == null || pack.context == null || pack.scope == null) {
            return EvalResult.failure(side, "KubeJS " + side.id() + " scripts have not been loaded yet",
                    List.of(), elapsed(started));
        }

        if (unrestricted) {
            // The load-time install is what lifts the sandbox for the script files themselves; repeating it
            // here is what survives a KubeJS that rebuilt its context after that install ran. Both calls are
            // free once the scope carries their mark.
            AutoJSUnrestricted.install(manager);
            AutoJSUnrestricted.installPermissivePaths(manager);
        }

        var context = pack.context;
        var scope = pack.scope;
        try {
            // The context is associated with this thread for the duration, because that is what this Rhino
            // generation looks up when it needs a context and the game thread is not inside a KubeJS load.
            return RhinoContexts.with(context, () -> {
                var value = context.evaluateString(scope, code, "autojs-eval", 1, null);
                return EvalResult.ok(side, ScriptValue.typeOf(value), ScriptValue.stringify(value), elapsed(started));
            });
        } catch (Throwable t) {
            return EvalResult.failure(side, ScriptValue.describe(t), ScriptValue.stackOf(t), elapsed(started));
        }
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
