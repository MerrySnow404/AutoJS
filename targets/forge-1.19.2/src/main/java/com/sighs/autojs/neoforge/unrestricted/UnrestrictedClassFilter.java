package com.sighs.autojs.neoforge.unrestricted;

import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.util.ClassFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
/**
 * Removes KubeJS's class filter, which on this generation cannot be overridden and has to be dismantled.
 *
 * <p>The 2101 generation funnels its whole class policy through {@code KubeJSContext.visibleToScripts} and
 * {@code KubeJSContext.loadJavaClass}, so a subclass that answers "yes" replaces it. This generation has no
 * such context class: {@code ScriptManager.load()} hand-builds a base context and calls
 * {@code context.setClassShutter(this)} on it, and the same manager is what {@code loadJavaClass} consults.
 *
 * <p>That shutter cannot be replaced. {@code Context.setClassShutter} is one-shot — it throws
 * {@code SecurityException} if a shutter is already installed — and by the time a plugin sees the manager,
 * KubeJS's own load has already installed its. It does not need to be replaced either: the shutter it installs is
 * {@code ScriptManager} itself, which implements {@code ClassShutter} by asking the very {@code ClassFilter} this
 * class empties. Emptying that filter therefore lifts both readers at once, and with no shutter at all Rhino
 * filters nothing in the first place, so this is sufficient in every case.
 *
 * <p>The deny lists are cleared through reflection rather than replaced: they are ordinary mutable collections
 * behind final fields, which makes emptying them a plain mutation instead of the field rewrite that Java 17
 * forbids. Everything else about the filter is left alone, and it keeps working.
 *
 * <p>Emptied rather than swapped on purpose: {@code ClassFilter.isAllowed} answers yes for anything that is not
 * denied, so an empty filter is a permissive one, and no second filter object has to be kept in step with the
 * live one.
 */
public final class UnrestrictedClassFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS/Unrestricted");

    private UnrestrictedClassFilter() {
    }

    /** Lifts the filter, and answers whether the context was there to lift it on. */
    public static boolean install(ScriptManager manager) {
        if (manager.context == null) {
            return false;
        }
        clearDenials(manager);
        return true;
    }

    /**
     * Empties the manager's filter.
     *
     * <p>Best-effort: a KubeJS that renamed or moved these fields would leave the filter in place, and a failure
     * here is logged and not raised.
     */
    private static void clearDenials(ScriptManager manager) {
        try {
            var field = ScriptManager.class.getDeclaredField("classFilter");
            field.setAccessible(true);
            var filter = (ClassFilter) field.get(manager);
            clearEntries(read(filter, "denyStrong"));
            clearEntries(read(filter, "denyWeak"));
            clearEntries(read(filter, "cache"));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not empty KubeJS's class filter; Java.loadClass stays filtered", e);
        }
    }

    private static Object read(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /** The filter stores its rules in a set, a list and a cache, so both collection shapes are cleared. */
    private static void clearEntries(Object holder) {
        if (holder instanceof Collection<?> collection) {
            collection.clear();
        } else if (holder instanceof Map<?, ?> map) {
            map.clear();
        }
    }
}
