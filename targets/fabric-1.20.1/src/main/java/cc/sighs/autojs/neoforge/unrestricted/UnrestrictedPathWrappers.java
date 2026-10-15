package cc.sighs.autojs.neoforge.unrestricted;

import dev.latvian.mods.rhino.util.wrap.TypeWrapper;
import dev.latvian.mods.rhino.util.wrap.TypeWrapperFactory;
import dev.latvian.mods.rhino.util.wrap.TypeWrappers;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

/**
 * Replaces KubeJS's {@code Path} and {@code File} type wrappers with versions that do not confine to the
 * game directory.
 *
 * <p>This is not the class filter: KubeJS funnels every JavaScript string that needs to become a path
 * through two type wrappers, and both end in {@code KubeJS.verifyFilePath}, which rejects anything
 * outside the game directory. Lifting the filter alone therefore still leaves {@code JsonIO}, {@code NBTIO}
 * and every mod API that takes a path unable to leave the game directory.
 *
 * <p>The replacements keep KubeJS's shape exactly, including resolving a relative string against the game
 * directory, and drop only the confinement check.
 *
 * <p>Swapped rather than registered, and that is the one thing this generation makes awkward:
 * {@code TypeWrappers.register} refuses a target that already has a wrapper, so the existing entry is dropped
 * from the registry first. Its map is private, so the entry is removed through reflection while the new
 * wrapper goes in through the public API — which keeps this file to a single reflected field read, with no
 * shadow copy of Rhino's wrapper type to construct by hand.
 */
public final class UnrestrictedPathWrappers {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS/Unrestricted");

    private UnrestrictedPathWrappers() {
    }

    public static Path path(Object from) {
        if (from instanceof Path path) {
            return path;
        }
        if (from == null || from.toString().isEmpty()) {
            return null;
        }
        return gameDir().resolve(from.toString());
    }

    public static File file(Object from) {
        if (from instanceof File file) {
            return file;
        }
        var path = path(from);
        return path == null ? null : path.toFile();
    }

    /** Replaces both entries, so a relative string still resolves against the game directory and nothing else changes. */
    public static void install(TypeWrappers wrappers) {
        if (wrappers == null) {
            return;
        }
        replace(wrappers, Path.class, (context, from) -> path(from));
        replace(wrappers, File.class, (context, from) -> file(from));
    }

    private static <T> void replace(TypeWrappers wrappers, Class<T> target, TypeWrapperFactory<T> factory) {
        try {
            entries(wrappers).remove(target);
            wrappers.register(target, TypeWrapper.ALWAYS_VALID, factory);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Could not lift KubeJS's {} path confinement", target.getSimpleName(), e);
        }
    }

    /** The registry's own map. Read, never written to the field: what changes is the map's contents. */
    @SuppressWarnings("unchecked")
    private static Map<Class<?>, TypeWrapper<?>> entries(TypeWrappers wrappers) throws ReflectiveOperationException {
        var field = TypeWrappers.class.getDeclaredField("wrappers");
        field.setAccessible(true);
        return (Map<Class<?>, TypeWrapper<?>>) field.get(wrappers);
    }

    /** Matches what KubeJS captures as its game directory: the loader's own answer on either loader. */
    private static Path gameDir() {
        return FabricLoader.getInstance().getGameDir().normalize().toAbsolutePath();
    }
}
