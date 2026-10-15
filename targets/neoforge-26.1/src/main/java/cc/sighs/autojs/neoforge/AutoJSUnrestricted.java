package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.AutoJS;
import cc.sighs.autojs.AutoJSRuntime;
import cc.sighs.autojs.config.AutoJSConfig;
import cc.sighs.autojs.neoforge.unrestricted.UnrestrictedContextFactory;
import cc.sighs.autojs.neoforge.unrestricted.UnrestrictedPathWrappers;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;

/**
 * Removes KubeJS's sandbox for a debugging install.
 *
 * <p>AutoJS exists to let an agent work on a running game, so it has to be able to reach anything the game
 * can reach. Three gates stand in the way and all of them are lifted here:
 *
 * <ul>
 *     <li>the class filter, which KubeJS routes through {@code ScriptManager.isClassAllowed};</li>
 *     <li>{@code ScriptManager.canListenEvents}, which forbids registering event handlers after load;</li>
 *     <li>the {@code Path}/{@code File} type wrappers, which confine every path to the game directory.</li>
 * </ul>
 *
 * <p>The filter is lifted by swapping in {@link UnrestrictedContextFactory} while KubeJS is loading a side,
 * which has to happen at that exact moment: KubeJS builds its factory at the top of the load and creates
 * the context a few lines later, so a swap in between means the scripts themselves land in the unrestricted
 * context and keep sharing their scope with everything evaluated afterwards.
 *
 * <p>What this cannot lift is structural rather than guarded: registry objects cannot be created after the
 * game's registration phase, KubeJS event groups marked startup-only still reject a server or client script
 * type, and reflection stays awkward because KubeJS's Rhino hides {@code getClass()} and refuses to look up
 * caller-sensitive methods.
 *
 * <p>This class is the KubeJS engine's implementation detail, not a knob callers read: the engine answers
 * whether its sandbox is lifted through {@link cc.sighs.autojs.engine.ScriptEngine#unrestricted()}, and the
 * KubeJS plugin is what calls {@link #install(dev.latvian.mods.kubejs.script.ScriptType)} and
 * {@link #installPermissivePaths(dev.latvian.mods.kubejs.script.ScriptManager)} at the two moments below.
 * Nothing outside this package needs to know which of the three gates exists.
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

    /** Called from the KubeJS plugin while a side's scripts are being loaded. */
    static void install(ScriptType type) {
        if (!enabled()) {
            return;
        }

        var manager = KubeJSScriptManagers.byType(type);
        if (manager == null || manager.contextFactory instanceof UnrestrictedContextFactory) {
            return;
        }

        var stock = manager.contextFactory;
        if (stock == null) {
            return;
        }

        var unrestricted = new UnrestrictedContextFactory(stock);
        manager.contextFactory = unrestricted;
        type.console.contextFactory = new WeakReference<>(unrestricted);
        LOGGER.info("KubeJS {} scripts now run without the class filter or its guards", type.name);
    }

    /**
     * Called after a side's scripts have loaded.
     *
     * <p>Deliberately not done earlier: the wrappers are registered by every plugin during the load, and
     * overwriting them first would make the builtin plugin's own registration throw.
     */
    static void installPermissivePaths(ScriptManager manager) {
        if (!enabled() || manager.contextFactory == null) {
            return;
        }

        UnrestrictedPathWrappers.install(manager.contextFactory.getTypeWrappers());
        LOGGER.info("KubeJS {} scripts can now reach paths outside the game directory", manager.scriptType.name);
    }
}
