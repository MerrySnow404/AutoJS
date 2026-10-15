package cc.sighs.autojs.neoforge.eval;

import dev.latvian.mods.rhino.MemberType;
import dev.latvian.mods.rhino.NativeJavaArray;
import dev.latvian.mods.rhino.ScriptRuntime;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns what a script produced into the strings an {@link cc.sighs.autojs.eval.EvalResult} carries.
 *
 * <p>Shared by both evaluators — the KubeJS context and AutoJS's own Rhino one — because the question is the
 * same for either: what is this value, and how do you print it so that an agent can act on it. Only the engine
 * is involved, so nothing here needs KubeJS.
 *
 * <p>What is 1802 about this file is that both Rhino helpers it asks lose their {@code Context} argument on this
 * generation: {@code MemberType.get(Object)} and {@code ScriptRuntime.toString(Object)} are static and read
 * nothing off a context, where the 1902 target passes one in. They answer exactly the same question, which is
 * why the two lines that call them are all that differs.
 */
public final class ScriptValue {
    private static final int MAX_STACK_FRAMES = 16;
    private static final int MAX_ARRAY_ELEMENTS = 64;

    private ScriptValue() {
    }

    /** The engine's own name for the value, in the lowercase an eval result reports. */
    public static String typeOf(Object value) {
        try {
            return MemberType.get(value).name().toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "object";
        }
    }

    /** How the value prints in a script, with arrays spelled out. */
    public static String stringify(Object value) {
        var array = asArray(value);
        if (array != null) {
            return arrayToString(array);
        }
        try {
            return ScriptRuntime.toString(value);
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }

    /** One line for a thrown thing: its type and its message. */
    public static String describe(Throwable t) {
        var message = t.getMessage();
        if (message == null || message.isBlank()) {
            message = t.getClass().getName();
        }
        return t.getClass().getSimpleName() + ": " + message;
    }

    /** The first frames of a throwable's stack, capped so a deep engine stack cannot flood a result. */
    public static List<String> stackOf(Throwable t) {
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
}
