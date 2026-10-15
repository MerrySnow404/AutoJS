package cc.sighs.autojs.neoforge.unrestricted;

import cc.sighs.autojs.neoforge.KubeJSScriptManagers;
import dev.latvian.mods.kubejs.KubeJS;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.rhino.SharedContextData;
import dev.latvian.mods.rhino.util.wrap.TypeWrapper;
import dev.latvian.mods.rhino.util.wrap.TypeWrapperFactory;
import dev.latvian.mods.rhino.util.wrap.TypeWrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

/**
 * Replaces KubeJS's {@code Path} and {@code File} type wrappers with versions that do not confine to the game
 * directory.
 *
 * <p>This is not the class filter: KubeJS funnels every JavaScript string that needs to become a path
 * through two type wrappers, and both end in {@code KubeJS.verifyFilePath}, which rejects anything
 * outside the game directory. Lifting the filter alone therefore still leaves {@code JsonIO}, {@code NBTIO}
 * and every mod API that takes a path unable to leave the game directory.
 *
 * <p>The replacements keep KubeJS's shape exactly — {@code UtilsJS.getPath} and {@code UtilsJS.getFileFromPath},
 * which is what the builtin plugin registers — including resolving a relative string against the game directory
 * and answering null for null and for the empty string, and drop only the confinement check.
 *
 * <p>Where the wrappers live is the one thing this generation changes: they hang off the scope's
 * {@link SharedContextData}, not off the context, and there is one such registry per script scope. That is also
 * why the {@code TypeWrapperFactory} below takes a bare value rather than a {@code (Context, Object)} pair, and
 * why the swap is marked on the context data — the thing KubeJS rebuilds on every reload, which makes the mark
 * expire exactly when the wrappers it described are gone.
 *
 * <p>Swapped rather than registered, because {@code TypeWrappers.register} refuses a target that already has a
 * wrapper: the existing entry is dropped from the registry first. Its map is private, so the entry is removed
 * through reflection while the new wrapper goes in through the public API — one reflected field read, with no
 * shadow copy of Rhino's wrapper type to construct by hand.
 */
public final class UnrestrictedPathWrappers {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS/Unrestricted");

    private static final String PATHS_MARK = "AutoJS/UnrestrictedPaths";

    private UnrestrictedPathWrappers() {
    }

    public static Path path(Object from) {
        if (from instanceof Path path) {
            return path;
        }
        if (from == null || from.toString().isEmpty()) {
            return null;
        }
        return KubeJS.getGameDirectory().resolve(from.toString());
    }

    public static File file(Object from) {
        if (from instanceof File file) {
            return file;
        }
        var path = path(from);
        return path == null ? null : path.toFile();
    }

    /**
     * Replaces both entries on a side's script scope, and answers whether this call was the one that did it.
     *
     * <p>A side with no scripts loaded yet has no scope to reach them through, and answers false rather than
     * failing: the load-time install runs again from the evaluator once there is one.
     */
    public static boolean install(ScriptManager manager) {
        var pack = KubeJSScriptManagers.packOf(manager);
        if (pack == null || pack.scope == null) {
            return false;
        }

        try {
            var data = SharedContextData.get(pack.scope);
            if (data.getExtraProperty(PATHS_MARK) != null) {
                return false;
            }
            data.setExtraProperty(PATHS_MARK, Boolean.TRUE);

            var wrappers = data.getTypeWrappers();
            replace(wrappers, Path.class, UnrestrictedPathWrappers::path);
            replace(wrappers, File.class, UnrestrictedPathWrappers::file);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not lift KubeJS's path confinement", e);
            return false;
        }
    }

    /** Replaces one entry, so a relative string still resolves against the game directory and nothing else changes. */
    private static <T> void replace(TypeWrappers wrappers, Class<T> target, TypeWrapperFactory<T> factory)
            throws ReflectiveOperationException {
        entries(wrappers).remove(target);
        wrappers.register(target, TypeWrapper.ALWAYS_VALID, factory);
    }

    /** The registry's own map. Read, never written to the field: what changes is the map's contents. */
    @SuppressWarnings("unchecked")
    private static Map<Class<?>, TypeWrapper<?>> entries(TypeWrappers wrappers) throws ReflectiveOperationException {
        var field = TypeWrappers.class.getDeclaredField("wrappers");
        field.setAccessible(true);
        return (Map<Class<?>, TypeWrapper<?>>) field.get(wrappers);
    }
}
