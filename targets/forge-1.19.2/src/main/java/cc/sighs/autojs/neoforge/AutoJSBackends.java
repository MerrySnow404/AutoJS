package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.AutoJSRuntime;
import cc.sighs.autojs.neoforge.backend.RhinoSideBackend;
import cc.sighs.autojs.neoforge.kubejs.KubeJSIntegration;
import cc.sighs.autojs.neoforge.tools.ReloadScriptsTool;
import cc.sighs.autojs.side.EvalBackend;
import cc.sighs.autojs.side.EvalSide;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Decides which engine a side runs on: KubeJS when it is installed, AutoJS's own Rhino kernel when it is not.
 *
 * <p>This is the only class the mod constructor asks, and it deliberately names no KubeJS type. KubeJS is a
 * soft dependency, so one KubeJS class mentioned here would fail the whole mod with
 * {@code NoClassDefFoundError} on a game without KubeJS; every KubeJS branch is instead a call into
 * {@link KubeJSIntegration}, which only a KubeJS mode ever loads. The same reasoning keeps client-only types
 * out too — see {@link #client(Supplier)}.
 */
public final class AutoJSBackends {
    private AutoJSBackends() {
    }

    /** The one thing that selects a mode: this is what "KubeJS is optional" means in practice. */
    public static boolean kubeJSInstalled() {
        return ModList.get().isLoaded("kubejs");
    }

    /** The server side's backend, for a server that has just started. */
    public static EvalBackend server(MinecraftServer server) {
        return kubeJSInstalled()
                ? KubeJSIntegration.serverBackend(server)
                : new RhinoSideBackend(EvalSide.SERVER, () -> server);
    }

    /**
     * The client side's backend.
     *
     * <p>The game thread arrives as an argument rather than being read from {@code Minecraft} here, because
     * this class is loaded on a dedicated server as well, and the client side of the game does not exist there.
     */
    public static EvalBackend client(Supplier<Executor> gameThread) {
        return kubeJSInstalled()
                ? KubeJSIntegration.clientBackend()
                : new RhinoSideBackend(EvalSide.CLIENT, gameThread);
    }

    /** Registers the tools, including the ones only one of the two engines can answer. */
    public static void registerTools() {
        AutoJSRuntime.registerTool(new ReloadScriptsTool());
        if (kubeJSInstalled()) {
            KubeJSIntegration.registerTools();
        }
    }
}
