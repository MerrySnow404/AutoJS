package com.sighs.autojs.neoforge.rhino;

import com.sighs.autojs.eval.EvalResult;
import com.sighs.autojs.neoforge.eval.ScriptValue;
import com.sighs.autojs.side.EvalSide;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeJSON;
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
 * <p>What is 1902 about this file, and why it is a target file rather than the one in
 * {@code targets/neoforge-1.21.1}: that generation's Rhino has no {@code ContextFactory} at all.
 * {@code Context.enter()} simply constructs a context (it keeps no thread-local of its own), the type
 * wrappers hang off that context instead of off a factory, and {@code TypeWrapperFactory} takes
 * {@code (Context, Object)} — no {@code TypeInfo}. Everything else — {@code initStandardObjects},
 * {@code evaluateString}, {@code addToScope} — is spelled the same way on both generations.
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
            context = Context.enter();
            scope = context.initStandardObjects();
            // AutoJS takes a JsonElement in two places — the area() options and writeJson()'s value — and a
            // KubeJS context is what normally converts a script object into one. The engine's own stringifier
            // does the script half of that and gson does the rest, so no walk over the script object is written
            // by hand. Unlike the KubeJS context, this one is ours alone: each runtime builds its own, so the
            // wrapper is registered exactly once per scope and register()'s refusal to overwrite never bites.
            context.getTypeWrappers().register(JsonElement.class, (rhinoContext, value) ->
                    GSON.fromJson(NativeJSON.stringify(value, null, null, rhinoContext), JsonElement.class));
            RhinoBindings.install(context, scope, side, diagnostics);
        }
        return scope;
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
