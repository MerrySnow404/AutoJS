package com.sighs.autojs.neoforge.client;

import com.sighs.autojs.neoforge.AbstractKubeJSSideBackend;
import com.sighs.autojs.neoforge.KubeJSScriptManagers;
import com.sighs.autojs.side.EvalSide;
import dev.latvian.mods.kubejs.client.KubeJSClient;
import dev.latvian.mods.kubejs.script.ScriptManager;
import net.minecraft.client.Minecraft;

import java.util.concurrent.Executor;

/**
 * Client side. Client-only: it is never loaded on a dedicated server, which has no client side at all.
 *
 * <p>Work is handed to the client's event loop, so snippets run on the render thread together with the
 * game state they read. Evaluating is the engine's job; reloading is not quite, because KubeJS's client side
 * reloads by a different call than its server side, and that call is a client-only one — which is exactly why
 * it stays in this class rather than in the shared engine.
 */
public final class ClientEvalBackend extends AbstractKubeJSSideBackend {
    @Override
    public EvalSide side() {
        return EvalSide.CLIENT;
    }

    @Override
    protected Executor gameThread() {
        return Minecraft.getInstance();
    }

    @Override
    public ScriptManager manager() {
        return KubeJSScriptManagers.client();
    }

    @Override
    public String describe() {
        var manager = KubeJSScriptManagers.client();
        if (manager == null) {
            return "no KubeJS client script manager captured yet";
        }
        var scripts = manager.contextFactory == null ? "scripts not loaded yet" : "scripts loaded";
        var minecraft = Minecraft.getInstance();
        var world = minecraft == null || minecraft.level == null ? "no world loaded" : "world loaded";
        return "client running, " + world + ", " + scripts;
    }

    @Override
    protected ReloadOutcome doReload() {
        if (manager() == null) {
            return ReloadOutcome.failed("KubeJS has no " + side().id() + " script manager in this instance");
        }
        try {
            // KubeJS does more than a plain manager reload here: it also re-collects the client item
            // tooltip cache, so this is the call its own /kubejs reload client-scripts uses.
            KubeJSClient.reloadClientScripts();
            return ReloadOutcome.ok("client scripts reloaded; press F3+T or use the game's reload for assets, "
                    + "models and language files.");
        } catch (Throwable t) {
            return ReloadOutcome.failed("Reloading client scripts failed: " + t);
        }
    }
}
