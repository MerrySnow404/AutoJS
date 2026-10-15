package cc.sighs.autojs.neoforge.unrestricted;

import dev.latvian.mods.kubejs.script.KubeJSContext;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeJavaClass;
import dev.latvian.mods.rhino.NativeJavaObject;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.type.TypeInfo;

import java.lang.reflect.AccessibleObject;

/**
 * Replacements for the three KubeJS checks that a debugging tool cannot live with.
 *
 * <p>KubeJS funnels its whole class policy through {@code ScriptManager.isClassAllowed}, which is called
 * from exactly two places, {@code KubeJSContext.visibleToScripts} and {@code KubeJSContext.loadJavaClass}.
 * Overriding those two therefore removes the filter completely, and the reflection guard lives in a third
 * method. The bodies live here so the client and server contexts stay one-liners.
 *
 * <p>Everything else is deliberately left alone: KubeJS's type wrappers, record defaults and method-handle
 * caches keep working because the contexts are still constructed with KubeJS's own factory.
 */
final class UnrestrictedAccess {
    private UnrestrictedAccess() {
    }

    /** {@code KubeJSContext.loadJavaClass} without the {@code isClassAllowed} gate, and without its cache. */
    static NativeJavaClass loadJavaClass(KubeJSContext context, String name, boolean error) {
        if (name == null || name.equals("null") || name.isEmpty()) {
            if (error) {
                throw Context.reportRuntimeError("Class name can't be empty!", context);
            }
            return null;
        }

        try {
            var type = Class.forName(name);
            context.getConsole().info("Loaded Java class '%s'".formatted(name));
            return new NativeJavaClass(context, context.topLevelScope, type);
        } catch (Exception e) {
            if (error) {
                throw Context.reportRuntimeError(
                        "Failed to load Java class '%s': Class could not be found!".formatted(name), context);
            }
            return null;
        }
    }

    /**
     * Wraps a {@code Method}, {@code Field}, {@code Constructor} or {@code ClassLoader}.
     *
     * <p>KubeJS refuses these outright. They are unreachable without reflection being usable, and this is
     * the generic arm of {@code Context.wrapAsJavaObject}, which is what a normal object takes.
     */
    static Scriptable wrapJavaObject(Scriptable scope, Object javaObject, TypeInfo target, Context context) {
        return new NativeJavaObject(scope, javaObject, target, context);
    }

    /** True for the two kinds of object KubeJS refuses to hand to a script. */
    static boolean isRefused(Object javaObject) {
        return javaObject instanceof AccessibleObject || javaObject instanceof ClassLoader;
    }
}
