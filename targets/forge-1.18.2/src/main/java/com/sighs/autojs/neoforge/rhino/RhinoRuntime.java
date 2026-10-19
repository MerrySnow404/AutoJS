package com.sighs.autojs.neoforge.rhino;

import com.sighs.autojs.eval.EvalResult;
import com.sighs.autojs.neoforge.eval.ScriptValue;
import com.sighs.autojs.side.EvalSide;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeJSON;
import dev.latvian.mods.rhino.SharedContextData;
import dev.latvian.mods.rhino.ScriptableObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One side's Rhino context, standing in for KubeJS when KubeJS is not installed.
 *
 * <p>A bare engine is enough for what AutoJS does with a snippet, and it needs no sandbox work: the base
 * context filters nothing, so there is nothing to lift the way there is in a KubeJS one. What it does need is
 * a conversion KubeJS would otherwise supply, which is what {@link #scope()} adds.
 *
 * <p>Thread-affine, like every Rhino context: it is built on first use, which is always the game thread of the
 * side that owns this runtime, and thrown away together with its scope by {@link #reset()}.
 *
 * <p>What is 1802 about this file, and why it is a target file rather than the one in
 * {@code targets/neoforge-1.21.1}:
 *
 * <ul>
 *     <li>a context is associated with a thread rather than built per call, so this file enters one to build
 *     the scope and lets {@link RhinoContexts} re-enter it for each eval — leaving the association behind
 *     would hand KubeJS's next {@code Context.enter} this kernel's context instead of a fresh one;</li>
 *     <li>the type wrappers are reached through the scope's {@link SharedContextData} rather than through the
 *     context, and only that route is tied to the scope being asked about: the context carries a
 *     {@code sharedContextData} field too, but {@code initStandardObjects} overwrites it with the last scope
 *     it built;</li>
 *     <li>{@code SharedContextData.get(scope)} does not create anything — it reads the value
 *     {@code initStandardObjects} associated with the scope, and throws without one — which is why it is
 *     called after the scope exists and never before; and</li>
 *     <li>the JSON stringifier takes the scope's context data instead of a context.</li>
 * </ul>
 */
public final class RhinoRuntime {
    private static final Gson GSON = new Gson();

    private final EvalSide side;

    /**
     * What the snippet printed during the current eval. Unlike a KubeJS side there is no log file to read
     * afterwards, so the console global and {@code AutoJS.log} both append here and the eval result carries the
     * lines back to the caller.
     */
    private final List<String> diagnostics = new ArrayList<>();

    private Context context;
    private ScriptableObject scope;

    public RhinoRuntime(EvalSide side) {
        this.side = side;
    }

    /** Evaluates on the game thread. */
    public EvalResult eval(String code) {
        var started = System.nanoTime();
        diagnostics.clear();

        try {
            var scope = scope();
            return RhinoContexts.with(context, () -> {
                var value = context.evaluateString(scope, code, "autojs-eval", 1, null);
                return EvalResult.ok(side, ScriptValue.typeOf(value), ScriptValue.stringify(value),
                        List.copyOf(diagnostics), elapsed(started));
            });
        } catch (Throwable t) {
            return EvalResult.failure(side, ScriptValue.describe(t), ScriptValue.stackOf(t), elapsed(started))
                    .withDiagnostics(List.copyOf(diagnostics));
        }
    }

    /** Drops the scope, and with it every global a snippet defined. Runs on the game thread. */
    public void reset() {
        context = null;
        scope = null;
        diagnostics.clear();
    }

    /**
     * Builds the scope on first use, with the context associated with this thread for the duration.
     *
     * <p>Building one is not just {@code initStandardObjects}: registering the JSON conversion below and
     * installing the globals both run Rhino code that looks the current context up by thread, which is the same
     * reason a KubeJS load is wrapped in an enter/exit pair.
     *
     * <p>A context that is already associated — the game thread being inside some other Rhino call — is used as
     * it is rather than entered again, because {@code Context.enter} would refuse it and exiting a context this
     * method did not enter would unbalance the other caller's bookkeeping.
     */
    private ScriptableObject scope() {
        if (scope != null) {
            return scope;
        }

        var own = Context.getCurrentContext();
        var entered = own == null;
        var rhinoContext = entered ? Context.enter() : own;
        try {
            var built = rhinoContext.initStandardObjects();
            var data = SharedContextData.get(built);
            // AutoJS takes a JsonElement in two places — the area() options and writeJson()'s value — and a
            // KubeJS context is what normally converts a script object into one. The engine's own stringifier
            // does the script half of that and gson does the rest, so no walk over the script object is written
            // by hand. Unlike the KubeJS context, this one is ours alone: each runtime builds its own scope, so
            // the wrapper is registered exactly once per scope.
            data.getTypeWrappers().register(JsonElement.class, value ->
                    GSON.fromJson(NativeJSON.stringify(data, value, null, null), JsonElement.class));
            RhinoBindings.install(rhinoContext, built, side, diagnostics);

            context = rhinoContext;
            scope = built;
        } finally {
            if (entered) {
                Context.exit();
            }
        }
        return scope;
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
