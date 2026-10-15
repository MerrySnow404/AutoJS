package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.AutoJS;
import cc.sighs.autojs.AutoJSRuntime;
import cc.sighs.autojs.config.AutoJSConfig;
import cc.sighs.autojs.neoforge.unrestricted.UnrestrictedClassFilter;
import cc.sighs.autojs.neoforge.unrestricted.UnrestrictedPathWrappers;
import dev.latvian.mods.kubejs.script.ScriptManager;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes KubeJS's sandbox for a debugging install.
 *
 * <p>AutoJS exists to let an agent work on a running game, so it has to be able to reach anything the game
 * can reach. Two gates stand in the way here and both are lifted:
 *
 * <ul>
 *     <li>the class filter, which KubeJS consults from the {@code ClassShutter} it installs on each script
 *     scope and again from {@code ScriptManager.loadJavaClass} — the method behind {@code Java.loadClass};</li>
 *     <li>the {@code Path}/{@code File} type wrappers, which confine every path to the game directory.</li>
 * </ul>
 *
 * <p>That list is shorter than the 1902 target's, and not because something was skipped: 1802's
 * {@code ScriptManager} has no {@code canListenEvents} and no equivalent guard on registering an event handler
 * after the load, so there is no third gate to lift. Everything else about the 1902 problem is here, with the
 * same two answers — see {@link UnrestrictedClassFilter} for why the filter's default verdict has to be turned
 * over on this generation, and {@link UnrestrictedPathWrappers} for the wrappers.
 *
 * <p>Timing is the one place this generation is easier. {@code KubeJSPlugin.addBindings} runs inside
 * {@code ScriptManager.load()}, after the context, the scope and every plugin's type wrappers exist and before
 * any script file is evaluated, so calling both installs from there covers the side's script files as well as
 * snippets sent over the endpoint. The 1902 target could only lift the class filter at that point and had to
 * defer the path wrappers to the first snippet, because it could not rely on KubeJS's builtin plugin having
 * registered them yet; here the wrappers are already in place by the time a plugin's bindings are added, so
 * both are lifted during the load. Both are called again from the evaluator, so a context KubeJS rebuilt later
 * is still lifted.
 *
 * <p>What this cannot lift is structural rather than guarded: registry objects cannot be created after the
 * game's registration phase, KubeJS event groups marked startup-only still reject a server or client script
 * type, and reflection stays awkward because KubeJS's Rhino hides {@code getClass()} and refuses to look up
 * caller-sensitive methods.
 *
 * <p>This class is the KubeJS engine's implementation detail, not a knob callers read: the engine answers
 * whether its sandbox is lifted through {@link cc.sighs.autojs.engine.ScriptEngine#unrestricted()}.
 */
public final class AutoJSUnrestricted {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS/Unrestricted");

    private static Boolean enabled;

    private AutoJSUnrestricted() {
    }

    /**
     * Resolved lazily and cached: KubeJS can load scripts before AutoJS's own constructor has run, in which
     * case the shared config is not available yet and the file is read directly.
     */
    public static boolean enabled() {
        var cached = enabled;
        if (cached == null) {
            var config = AutoJSRuntime.config();
            cached = config != null
                    ? config.unrestricted
                    : AutoJSConfig.load(FMLPaths.CONFIGDIR.get().resolve(AutoJS.MOD_ID + ".json")).unrestricted;
            enabled = cached;
        }
        return cached;
    }

    /**
     * Lifts the class filter for a side.
     *
     * <p>Called from the KubeJS plugin while a side's scripts are being built, and again from the evaluator
     * before every snippet; the second call is free, because the filter itself records that it has been lifted.
     */
    public static void install(ScriptManager manager) {
        if (!enabled() || manager == null) {
            return;
        }
        if (UnrestrictedClassFilter.install(manager)) {
            LOGGER.info("KubeJS {} scripts now run without the class filter", manager.type.name);
        }
    }

    /**
     * Replaces the path wrappers with unconfined ones.
     *
     * <p>Called from both places for the same reason as {@link #install(ScriptManager)}, and unlike on 1902 the
     * load-time call works: this generation's plugin hook runs after every type wrapper has been registered, so
     * the side's own script files are no longer confined to the game directory either. The mark that keeps the
     * swap from repeating lives on the scope's context data, which a reload replaces.
     */
    public static void installPermissivePaths(ScriptManager manager) {
        if (!enabled() || manager == null) {
            return;
        }
        if (UnrestrictedPathWrappers.install(manager)) {
            LOGGER.info("KubeJS {} scripts can now reach paths outside the game directory", manager.type.name);
        }
    }
}
