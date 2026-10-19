package com.sighs.autojs.neoforge.rhino;

import com.sighs.autojs.eval.EvalResult;
import com.sighs.autojs.neoforge.eval.ScriptValue;
import com.sighs.autojs.side.EvalSide;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.NativeJSON;
import dev.latvian.mods.rhino.ScriptableObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One side's Rhino context, standing in for KubeJS when KubeJS is not installed.
 *
 * <p>A bare engine is enough for what AutoJS does with a snippet, and it needs no sandbox work: the base
 * context filters nothing, so there is nothing to lift the way there is in a KubeJS one. What it does need is
 * a conversion KubeJS would otherwise supply, which is what the constructor adds.
 *
 * <p>Thread-affine, like every Rhino context: it is built on first use, which is always the game thread of the
 * side that owns this runtime, and thrown away together with its scope by {@link #reset()}.
 */
public final class RhinoRuntime {
    private static final Gson GSON = new Gson();

    private final EvalSide side;
    private final ContextFactory factory = new ContextFactory();

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
        // AutoJS takes a JsonElement in two places — the area() options and writeJson()'s value — and a KubeJS
        // context is what normally converts a script object into one. The engine's own stringifier does the
        // script half of that and gson does the rest, so no walk over the script object is written by hand.
        factory.getTypeWrappers().register(JsonElement.class, (context, value, type) ->
                GSON.fromJson(NativeJSON.stringify(value, null, null, context), JsonElement.class));
    }

    /** Evaluates on the game thread. */
    public EvalResult eval(String code) {
        var started = System.nanoTime();
        diagnostics.clear();

        try {
            var scope = scope();
            var value = context.evaluateString(scope, code, "autojs-eval", 1, null);
            return EvalResult.ok(side, ScriptValue.typeOf(context, value), ScriptValue.stringify(context, value),
                    List.copyOf(diagnostics), elapsed(started));
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

    private ScriptableObject scope() {
        if (scope == null) {
            context = factory.enter();
            scope = context.initStandardObjects();
            RhinoBindings.install(context, scope, side, diagnostics);
        }
        return scope;
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
