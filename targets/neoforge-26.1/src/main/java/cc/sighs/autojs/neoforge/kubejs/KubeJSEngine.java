package cc.sighs.autojs.neoforge.kubejs;

import cc.sighs.autojs.engine.ScriptEngine;
import cc.sighs.autojs.eval.EvalResult;
import cc.sighs.autojs.neoforge.AutoJSUnrestricted;
import cc.sighs.autojs.neoforge.KubeJSEvaluator;
import cc.sighs.autojs.neoforge.KubeJSScriptManagers;
import cc.sighs.autojs.neoforge.backend.ReloadableSide.ReloadOutcome;
import cc.sighs.autojs.side.EvalSide;
import dev.latvian.mods.kubejs.script.ScriptManager;

/**
 * The KubeJS engine for Minecraft 26.1 (KubeJS 8, on the 2101 Rhino line).
 *
 * <p>This is the {@link ScriptEngine} the KubeJS-backed sides run on: it answers whether a side has a script
 * manager, lifts KubeJS's sandbox when the AutoJS config asks for it, evaluates through
 * {@link KubeJSEvaluator}, and re-runs the server side's script files. The KubeJS API the chain is written
 * against ({@code KubeJSContext}, {@code KubeJSContextFactory}, {@code ScriptManager}, the plugin interface)
 * is the same generation the 1.21.1 target uses — 26.1's KubeJS 8 kept it — which is why it lives in this
 * target rather than in {@code common}: a KubeJS on another generation provides its own implementation of the
 * same interface, and the 1802/1902/2001 targets do.
 *
 * <p>Two things this file must not do are worth stating, because both are one call away and both are
 * already settled: it never installs a class shutter (KubeJS has one, and {@code Context.setClassShutter} is
 * one-shot, so a second call throws), and it reloads through the live {@code ScriptManager} rather than through
 * KubeJS's own server singleton, whose {@code server} field is not filled in at the time a reload happens.
 */
public final class KubeJSEngine implements ScriptEngine {
    /** Stateless: everything it reads is a static registry or the live manager looked up per call. */
    public static final KubeJSEngine INSTANCE = new KubeJSEngine();

    private KubeJSEngine() {
    }

    /** The live KubeJS script manager of a side, or null while that side has no scripts loaded. */
    public static ScriptManager managerOf(EvalSide side) {
        return side.isServer() ? KubeJSScriptManagers.server() : KubeJSScriptManagers.client();
    }

    @Override
    public boolean available(EvalSide side) {
        return managerOf(side) != null;
    }

    @Override
    public boolean unrestricted() {
        return AutoJSUnrestricted.enabled();
    }

    @Override
    public EvalResult eval(EvalSide side, String code) {
        return KubeJSEvaluator.evalWithDiagnostics(side, managerOf(side), code, unrestricted());
    }

    /**
     * Re-runs this side's script files.
     *
     * <p>The server half only: KubeJS's client side reloads by a different call, which
     * {@code ClientEvalBackend} supplies. Whatever this returns, the reload itself is the same operation as the
     * game's {@code /kubejs reload server-scripts}.
     */
    @Override
    public ReloadOutcome reload(EvalSide side) {
        var manager = managerOf(side);
        if (manager == null) {
            return ReloadOutcome.failed("KubeJS has no " + side.id() + " script manager in this instance");
        }

        try {
            manager.reload();
            return ReloadOutcome.ok("server scripts reloaded. Recipes, tags and loot tables belong to the datapack, "
                    + "so run /reload as well if the change touched them.");
        } catch (Throwable t) {
            return ReloadOutcome.failed("Reloading " + side.id() + " scripts failed: " + t);
        }
    }
}
