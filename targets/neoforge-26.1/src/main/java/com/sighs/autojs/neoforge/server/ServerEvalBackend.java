package com.sighs.autojs.neoforge.server;

import com.sighs.autojs.neoforge.AbstractKubeJSSideBackend;
import com.sighs.autojs.neoforge.KubeJSScriptManagers;
import com.sighs.autojs.side.EvalSide;
import dev.latvian.mods.kubejs.script.ScriptManager;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.Executor;

/**
 * Server side: the dedicated server, or the integrated server behind a singleplayer world.
 *
 * <p>Work is handed to the server's own event loop, so a snippet always runs on the thread that owns
 * the level, and the HTTP request thread waits for the answer. What runs there, and what a reload means, is
 * the engine's business — see {@link com.sighs.autojs.neoforge.kubejs.KubeJSEngine}.
 */
public final class ServerEvalBackend extends AbstractKubeJSSideBackend {
    private final MinecraftServer server;

    public ServerEvalBackend(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public EvalSide side() {
        return EvalSide.SERVER;
    }

    @Override
    protected Executor gameThread() {
        return server;
    }

    @Override
    public ScriptManager manager() {
        return KubeJSScriptManagers.server();
    }

    @Override
    public String describe() {
        var manager = KubeJSScriptManagers.server();
        if (manager == null) {
            return "server running but no KubeJS server script manager captured yet";
        }
        var scripts = manager.contextFactory == null ? "scripts not loaded yet" : "scripts loaded";
        return "server running with " + server.getPlayerCount() + " player(s) online, " + scripts;
    }
}
