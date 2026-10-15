package cc.sighs.autojs.neoforge.kubejs;

import cc.sighs.autojs.engine.ScriptEngine;
import cc.sighs.autojs.eval.EvalResult;
import cc.sighs.autojs.mc.McApi;
import cc.sighs.autojs.neoforge.AutoJSUnrestricted;
import cc.sighs.autojs.neoforge.KubeJSEvaluator;
import cc.sighs.autojs.neoforge.KubeJSScriptManagers;
import cc.sighs.autojs.neoforge.backend.ReloadableSide.ReloadOutcome;
import cc.sighs.autojs.side.EvalSide;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.server.ServerScriptManager;

/**
 * The KubeJS engine for Minecraft 1.18.2 (KubeJS 1802).
 *
 * <p>This is the {@link ScriptEngine} the KubeJS-backed sides run on: it answers whether a side has a script
 * manager, lifts KubeJS's sandbox when the AutoJS config asks for it, evaluates through
 * {@link KubeJSEvaluator}, and re-runs the server side's script files. The KubeJS API the chain is written
 * against is the 1802 generation, which is why it lives in this target rather than in {@code common}: a KubeJS
 * on another generation provides its own implementation of the same interface.
 *
 * <p>{@code reload} is the method this generation changed most. 1802's {@code ScriptManager} has no reload at
 * all — only {@code unload()}, {@code loadFromDirectory()} and {@code load()} — so re-running a side's scripts is
 * KubeJS's own reload entry point, and that is what it calls here.
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
     * {@code ClientEvalBackend} supplies.
     *
     * <p>{@code ServerScriptManager.reloadScriptManager} is KubeJS's own server reload — the one behind its
     * {@code /kubejs reload server_scripts} — and it is used instead of the three steps a client reload takes
     * because it does the rest of the same job: it re-reads the scripts a datapack ships as well as the ones in
     * {@code kubejs/server_scripts}, and it rebuilds the recipe handlers those scripts registered into. It takes
     * the resource manager to read both of those from.
     *
     * <p>The manager is taken from the live server through {@link McApi}, not from a field of the KubeJS object
     * that owns it: the server is the only thing that can answer for the resources that exist right now.
     * {@code ServerScriptManager.instance} is only asked whether there is a KubeJS server reloader at all — it
     * is what {@code ScriptType.SERVER.manager} itself reads, so a non-null manager above already implies it —
     * and when either half is missing the three steps are run directly rather than failing. Those reload the
     * local directory only, which is what the client path does on this generation too.
     */
    @Override
    public ReloadOutcome reload(EvalSide side) {
        var manager = managerOf(side);
        if (manager == null) {
            return ReloadOutcome.failed("KubeJS has no " + side.id() + " script manager in this instance");
        }

        try {
            var server = side.isServer() ? McApi.loader().currentServer() : null;
            var serverReloader = side.isServer() ? ServerScriptManager.instance : null;
            if (server != null && serverReloader != null) {
                serverReloader.reloadScriptManager(server.getResourceManager());
            } else {
                manager.unload();
                manager.loadFromDirectory();
                manager.load();
            }
            return ReloadOutcome.ok("server scripts reloaded. Recipes, tags and loot tables belong to the datapack, "
                    + "so run /reload as well if the change touched them.");
        } catch (Throwable t) {
            return ReloadOutcome.failed("Reloading " + side.id() + " scripts failed: " + t);
        }
    }
}
