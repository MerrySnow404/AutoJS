package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.eval.EvalResult;
import cc.sighs.autojs.side.EvalSide;
import dev.latvian.mods.kubejs.script.KubeJSContext;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.MemberType;
import dev.latvian.mods.rhino.NativeJavaArray;
import dev.latvian.mods.rhino.ScriptRuntime;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 */
public final class KubeJSEvaluator {
    private static final int MAX_STACK_FRAMES = 16;
    private static final int MAX_ARRAY_ELEMENTS = 64;

    private KubeJSEvaluator() {
    }

    /**
     * Evaluates, and attaches whatever the side logged while doing so.
     *
     * <p>The KubeJS log is the only witness for failures it refuses to raise, such as an event
     * registration attempted outside the loading phase, so the caller gets them as diagnostics instead
     * of a misleading success. It is read from the log file rather than the in-memory queues, which are
     * only filled while KubeJS has error capturing switched on.
     */
    public static EvalResult evalWithDiagnostics(EvalSide side, ScriptManager manager, String code) {
        if (manager == null) {
            return eval(side, manager, code);
        }
        var mark = KubeJSConsole.mark(manager);
        var result = eval(side, manager, code);
        var added = KubeJSConsole.since(manager, mark);
        return added == null ? result : result.withDiagnostics(added);
    }

    public static EvalResult eval(EvalSide side, ScriptManager manager, String code) {
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
            if (AutoJSUnrestricted.enabled()) {
                // KubeJS refuses event registration outside its loading phase. For a debugging tool that
                // refusal is only a silent no-op, so it is lifted for the duration of the snippet.
                manager.canListenEvents = true;
            }
            try {
                var value = context.evaluateString(context.topLevelScope, code, "autojs-eval", 1, null);
                return EvalResult.ok(side, typeOf(context, value), stringify(context, value), elapsed(started));
            } finally {
                manager.canListenEvents = previousListen;
            }
        } catch (Throwable t) {
            return EvalResult.failure(side, describe(t), stackOf(t), elapsed(started));
        }
    }

    private static String typeOf(Context context, Object value) {
        try {
            return MemberType.get(value, context).name().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "object";
        }
    }

    private static String stringify(Context context, Object value) {
        var array = asArray(value);
        if (array != null) {
            return arrayToString(array);
        }
        try {
            return ScriptRuntime.toString(context, value);
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }

    /** A Java array, whether it arrived raw or wrapped by the engine. */
    private static Object asArray(Object value) {
        if (value == null) {
            return null;
        }
        if (value.getClass().isArray()) {
            return value;
        }
        return value instanceof NativeJavaArray wrapped ? wrapped.unwrap() : null;
    }

    /**
     * Java arrays print as {@code [Ljava.lang.String;@1a2b3c}, which tells an agent nothing, and
     * {@code JSON.stringify} does not understand them either. Print the elements, capped so a large byte
     * array cannot flood the result.
     */
    private static String arrayToString(Object array) {
        var length = Array.getLength(array);
        var shown = Math.min(length, MAX_ARRAY_ELEMENTS);
        var builder = new StringBuilder("[");

        for (var index = 0; index < shown; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            var element = Array.get(array, index);
            builder.append(element instanceof CharSequence text ? "'" + text + "'" : String.valueOf(element));
        }

        if (length > shown) {
            builder.append(", ... ").append(length - shown).append(" more");
        }
        return builder.append(']').toString();
    }

    private static String describe(Throwable t) {
        var message = t.getMessage();
        if (message == null || message.isBlank()) {
            message = t.getClass().getName();
        }
        return t.getClass().getSimpleName() + ": " + message;
    }

    private static List<String> stackOf(Throwable t) {
        var frames = new ArrayList<String>();
        var stack = t.getStackTrace();
        for (var i = 0; i < stack.length && i < MAX_STACK_FRAMES; i++) {
            frames.add(stack[i].toString());
        }
        if (stack.length > MAX_STACK_FRAMES) {
            frames.add("... " + (stack.length - MAX_STACK_FRAMES) + " more frames");
        }
        return frames;
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
