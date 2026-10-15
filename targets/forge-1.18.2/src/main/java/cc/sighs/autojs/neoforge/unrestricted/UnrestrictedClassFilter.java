package cc.sighs.autojs.neoforge.unrestricted;

import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.util.ClassFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes KubeJS's class filter, which on this generation denies by default and has to be dismantled from both
 * ends.
 *
 * <p>This is the same gate as on the 1902 target and the same way of reaching it — the filter is a private final
 * field of the manager and is emptied through reflection rather than replaced — but what has to be done to it is
 * the opposite, and that difference is the whole file.
 *
 * <p>{@code ClassFilter.isAllowed0} on this generation reads four lists in order and then falls back to a
 * switch:
 *
 * <pre>
 *   denyStrong.contains(name)                     -&gt; deny
 *   allowStrong.contains(name)                    -&gt; allow
 *   any denyWeak prefix matches name              -&gt; deny
 *   any allowWeak prefix matches name             -&gt; allow
 *   otherwise                                     -&gt; CommonProperties.disableClassFilter
 * </pre>
 *
 * <p>On 1902 the fallback was "allow", so emptying the deny lists was enough and that is all the 1902 target
 * does. Here it is {@code disableClassFilter}, false by default, so emptying the denies leaves everything
 * denied unless it matched an allow list — the filter would go from "most things it has rules for" to
 * "everything".
 *
 * <p>Both halves are therefore emptied and filled: the two deny lists are cleared, and one allow rule that
 * matches every name — the empty prefix, since every string starts with it — is added to {@code allowWeak}. With
 * the deny lists empty nothing can be denied, and with that rule present nothing reaches the fallback, so no
 * name is left to the default verdict at all.
 *
 * <p>KubeJS's own {@code disableClassFilter} is deliberately <em>not</em> set, even though it is the switch for
 * the fallback and would be the shorter answer. It is a field of KubeJS's {@code CommonProperties}, and a
 * debugging install flipping it has consequences outside this filter: KubeJS logs {@code "Class filter is
 * disabled!"} into its own console once the flag is on, which would put a warning into the startup side's
 * {@code kubejs_log} output that no other AutoJS target produces. Keeping the lift inside the filter keeps the
 * observable surface identical to 1902's.
 *
 * <p>What is not touched is the shutter. On this generation the class policy is installed on the scope's
 * {@code SharedContextData} — {@code ScriptManager.load()} calls {@code setClassShutter(this::visibleToScripts)}
 * — and the same {@code ClassFilter} is what that shutter and {@code loadJavaClass} consult, so both readers
 * are lifted by this one change. Nothing calls {@code setClassShutter}: the 1902 target's version of this
 * class records why replacing a shutter is a one-shot affair, and here there is no reason to reach for it at
 * all.
 *
 * <p>Emptied rather than swapped on purpose: no second filter object has to be kept in step with the live one.
 */
public final class UnrestrictedClassFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS/Unrestricted");

    /**
     * The allow rule that matches every class name.
     *
     * <p>{@code allowWeak} entries are prefixes matched with {@code String.startsWith}, and every string starts
     * with the empty one, so this single rule turns the filter's answer into "allowed" for anything the (now
     * empty) deny lists do not reject. It is put into the list directly rather than through
     * {@code ClassFilter.allow("")}: that method trims its argument and returns early on an empty string, so the
     * public route cannot express this rule at all.
     */
    private static final String EVERYTHING = "";

    private UnrestrictedClassFilter() {
    }

    /**
     * Lifts the filter, and answers whether this call was the one that did it.
     *
     * <p>The filter is built once, in the {@code ScriptManager} constructor, so this is a one-time change that
     * survives every reload; the answer is what keeps the log line from repeating on each of the per-snippet
     * installs. A filter that is already in the state this method produces — nothing denied, and the rule that
     * matches everything already in place — is recognised as such and left alone.
     */
    @SuppressWarnings("unchecked")
    public static boolean install(ScriptManager manager) {
        try {
            var filter = filterOf(manager);
            if (filter == null) {
                return false;
            }

            var denyStrong = (Set<?>) read(filter, "denyStrong");
            var denyWeak = (List<?>) read(filter, "denyWeak");
            var cache = (Map<?, ?>) read(filter, "cache");
            var allowWeak = (List<String>) read(filter, "allowWeak");

            if (denyStrong.isEmpty() && denyWeak.isEmpty() && allowWeak.contains(EVERYTHING)) {
                return false;
            }

            denyStrong.clear();
            denyWeak.clear();
            cache.clear();
            if (!allowWeak.contains(EVERYTHING)) {
                allowWeak.add(EVERYTHING);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not empty KubeJS's class filter; Java.loadClass stays filtered", e);
            return false;
        }
    }

    /** The manager's filter. Best-effort: a KubeJS that renamed or moved the field answers null. */
    private static ClassFilter filterOf(ScriptManager manager) throws ReflectiveOperationException {
        if (manager == null) {
            return null;
        }
        var field = ScriptManager.class.getDeclaredField("classFilter");
        field.setAccessible(true);
        return (ClassFilter) field.get(manager);
    }

    private static Object read(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
