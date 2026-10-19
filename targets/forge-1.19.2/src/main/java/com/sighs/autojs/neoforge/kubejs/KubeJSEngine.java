package com.sighs.autojs.neoforge.kubejs;

import com.sighs.autojs.engine.ScriptEngine;
import com.sighs.autojs.eval.EvalResult;
import com.sighs.autojs.mc.McApi;
import com.sighs.autojs.neoforge.AutoJSUnrestricted;
import com.sighs.autojs.neoforge.KubeJSEvaluator;
import com.sighs.autojs.neoforge.KubeJSScriptManagers;
import com.sighs.autojs.neoforge.backend.ReloadableSide.ReloadOutcome;
import com.sighs.autojs.side.EvalSide;
import dev.latvian.mods.kubejs.script.ScriptManager;

/**
 * The KubeJS engine for Minecraft 1.19.2 (KubeJS 1902).
 *
 * <p>This is the {@link ScriptEngine} the KubeJS-backed sides run on: it answers whether a side has a script
 * manager, lifts KubeJS's sandbox when the AutoJS config asks for it, evaluates through
 * {@link KubeJSEvaluator}, and re-runs the server side's script files. The KubeJS API the chain is written
 * against is the 1902 generation, which is why it lives in this target rather than in {@code common}: a KubeJS
 * on another generation provides its own implementation of the same interface.
 *
 * <p>One method below reads differently from its 2101 counterpart, and it is the only one that had to:
 * {@code reload} takes a resource manager here, because 1902's {@code ScriptManager.reload} is spelled
 * {@code reload(ResourceManager)} and not {@code reload()}.
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
     *
     * <p>The resource manager is handed over for the server side, because that is what KubeJS's own reload command
     * passes and what makes scripts shipped in a datapack reload as well as the ones in
     * {@code kubejs/server_scripts}. It comes from the live server through {@link McApi}, not from
     * {@code ServerScriptManager}'s own {@code server} field: that field is null in this KubeJS build even while
     * its manager is serving scripts, and reading it fails every reload with a {@code NullPointerException}. The
     * client side is not a server one and takes KubeJS's directory-only reload, the same call its own startup
     * reload makes.
     */
    @Override
    public ReloadOutcome reload(EvalSide side) {
        var manager = managerOf(side);
        if (manager == null) {
            return ReloadOutcome.failed("KubeJS has no " + side.id() + " script manager in this instance");
        }

        try {
            var server = side.isServer() ? McApi.loader().currentServer() : null;
            manager.reload(server == null ? null : server.getResourceManager());
            return ReloadOutcome.ok("server scripts reloaded. Recipes, tags and loot tables belong to the datapack, "
                    + "so run /reload as well if the change touched them.");
        } catch (Throwable t) {
            return ReloadOutcome.failed("Reloading " + side.id() + " scripts failed: " + t);
        }
    }
}
