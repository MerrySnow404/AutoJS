package cc.sighs.autojs.neoforge.unrestricted;

import dev.latvian.mods.rhino.util.wrap.TypeWrapper;
import dev.latvian.mods.rhino.util.wrap.TypeWrapperValidator;
import dev.latvian.mods.rhino.util.wrap.TypeWrappers;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.nio.file.Path;

/**
 * Replaces KubeJS's {@code Path} and {@code File} type wrappers with versions that do not confine to the
 * game directory.
 *
 * <p>This is not the class filter: KubeJS funnels every JavaScript string that needs to become a path
 * through two type wrappers, and both end in {@code KubeJSPaths.verifyFilePath}, which rejects anything
 * outside the game directory. Lifting the filter alone therefore still leaves {@code JsonIO}, {@code NBTIO}
 * and every mod API that takes a path unable to leave the game directory.
 *
 * <p>The replacements keep KubeJS's shape exactly, including resolving a relative string against the game
 * directory, and drop only the confinement check.
 */
public final class UnrestrictedPathWrappers {
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

    /**
     * Overwrites the two entries rather than calling {@code register}, which refuses to replace an existing
     * wrapper and would break the builtin plugin if it ran afterwards.
     */
    public static void install(TypeWrappers wrappers) {
        wrappers.wrappers.put(Path.class, new TypeWrapper<>(Path.class, TypeWrapperValidator.ALWAYS_VALID,
                (cx, from, target) -> path(from)));
        wrappers.wrappers.put(File.class, new TypeWrapper<>(File.class, TypeWrapperValidator.ALWAYS_VALID,
                (cx, from, target) -> file(from)));
    }

    /** Matches what KubeJS captures as its game directory. */
    private static Path gameDir() {
        return FMLPaths.GAMEDIR.get().normalize().toAbsolutePath();
    }
}
