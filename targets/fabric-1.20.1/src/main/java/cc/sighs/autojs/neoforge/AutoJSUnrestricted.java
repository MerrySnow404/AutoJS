package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.AutoJS;
import cc.sighs.autojs.AutoJSRuntime;
import cc.sighs.autojs.config.AutoJSConfig;
import cc.sighs.autojs.neoforge.unrestricted.UnrestrictedClassFilter;
import cc.sighs.autojs.neoforge.unrestricted.UnrestrictedPathWrappers;
import dev.latvian.mods.kubejs.script.ScriptManager;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes KubeJS's sandbox for a debugging install.
 *
 * <p>AutoJS exists to let an agent work on a running game, so it has to be able to reach anything the game
 * can reach. Three gates stand in the way and all of them are lifted here:
 *
 * <ul>
 *     <li>the class filter, which KubeJS installs on the context as a {@code ClassShutter} while it loads, and
 *     consults again from {@code ScriptManager.loadJavaClass} — the method behind {@code Java.loadClass};</li>
 *     <li>{@code ScriptManager.canListenEvents}, which forbids registering event handlers after load;</li>
 *     <li>the {@code Path}/{@code File} type wrappers, which confine every path to the game directory.</li>
 * </ul>
 *
 * <p>This is the same three gates as on the 2101 generation, and the 2001 route to them is different, because
 * this generation has no {@code KubeJSContext} to subclass — the context is a plain {@code Context} that
 * {@code ScriptManager.load()} configured itself. So the filter is lifted instead of overridden: see
 * {@link UnrestrictedClassFilter} for the two steps and why each one is needed, and
 * {@link UnrestrictedPathWrappers} for the wrappers.
 *
 * <p>Timing is what the 2101 generation also had to respect, and this one has one less hook to do it with:
 * {@code KubeJSPlugin.registerBindings} runs inside {@code ScriptManager.load()}, after the context exists and
 * before any script file is evaluated, so calling {@link #install(ScriptManager)} from there covers the script
 * files themselves and not only snippets evaluated over the endpoint. It is called again from the evaluator,
 * so a context KubeJS rebuilt later is still lifted.
 *
 * <p>The three gates are not all reachable from that one hook, and the difference is worth stating: the class
 * filter and the event-registration guard are lifted for the side's script files, while the path wrappers are
 * lifted from the evaluator only — see {@link #installPermissivePaths(ScriptManager)} for why this generation
 * cannot do it during the load.
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

    /**
     * Marks a context that has already been lifted, so the two installs below can be called before every
     * snippet without repeating their work or their log lines.
     *
     * <p>The mark lives on the context because a context is what KubeJS rebuilds on a reload: a fresh one
     * carries no mark, so a reloaded side is lifted again, while a snippet in between costs one property read.
     */
    private static final String CLASS_FILTER_MARK = "AutoJS/UnrestrictedClassFilter";
    private static final String PATHS_MARK = "AutoJS/UnrestrictedPaths";

    private static Boolean enabled;

    private AutoJSUnrestricted() {
    }

    /**
     * Resolved lazily and cached: KubeJS can load scripts before AutoJS's own entry point has run, in which
     * case the shared config is not available yet and the file is read directly. The config directory is the
     * loader's own — {@code FabricLoader.getInstance().getConfigDir()} here, {@code FMLPaths.CONFIGDIR} on
     * Forge — which is the one loader-shaped line in this file.
     */
    public static boolean enabled() {
        var cached = enabled;
        if (cached == null) {
            var config = AutoJSRuntime.config();
            cached = config != null
                    ? config.unrestricted
                    : AutoJSConfig.load(FabricLoader.getInstance().getConfigDir().resolve(AutoJS.MOD_ID + ".json")).unrestricted;
            enabled = cached;
        }
        return cached;
    }

    /**
     * Lifts the class filter and the event-registration guard for a side.
     *
     * <p>Called from the KubeJS plugin while a side's scripts are being built, and again from the evaluator
     * before every snippet; the second call is free, because the context is marked once it has been lifted.
     */
    public static void install(ScriptManager manager) {
        if (!enabled() || manager == null) {
            return;
        }
        var context = manager.context;
        if (context == null || context.getProperty(CLASS_FILTER_MARK) != null) {
            return;
        }
        context.setProperty(CLASS_FILTER_MARK, Boolean.TRUE);
        if (UnrestrictedClassFilter.install(manager)) {
            LOGGER.info("KubeJS {} scripts now run without the class filter or its guards", manager.scriptType.name);
        }
    }

    /**
     * Replaces the path wrappers with unconfined ones.
     *
     * <p>Called from the evaluator only, never from the load: this generation has no after-load plugin hook,
     * and the swap cannot be done during the load either, because it removes the entry KubeJS's own builtin
     * plugin registers and then registers its own — while that plugin's registration would throw if it had not
     * run yet. Load order between KubeJS's builtin plugin and a mod's plugin is not something AutoJS can rely
     * on, so the wrappers are lifted once the load is over, which is where the 2101 generation did it from its
     * own after-load hook.
     *
     * <p>The consequence is stated rather than hidden: on this generation the confinement is lifted for every
     * snippet sent over the endpoint, while a side's own script files still run with KubeJS's directory-only
     * {@code Path}/{@code File} wrappers. The class filter, which is the gate that matters for reaching game
     * classes, is lifted for both — see {@link #install(ScriptManager)}.
     */
    public static void installPermissivePaths(ScriptManager manager) {
        if (!enabled() || manager == null || manager.context == null) {
            return;
        }
        var context = manager.context;
        if (context.getProperty(PATHS_MARK) != null) {
            return;
        }
        context.setProperty(PATHS_MARK, Boolean.TRUE);
        UnrestrictedPathWrappers.install(context.getTypeWrappers());
        LOGGER.info("KubeJS {} scripts can now reach paths outside the game directory", manager.scriptType.name);
    }
}
