package cc.sighs.autojs.neoforge.kubejs;

import cc.sighs.autojs.AutoJSRuntime;
import cc.sighs.autojs.neoforge.client.ClientEvalBackend;
import cc.sighs.autojs.neoforge.script.ScriptOutput;
import cc.sighs.autojs.neoforge.server.ServerEvalBackend;
import cc.sighs.autojs.neoforge.tools.KubeJSLogTool;
import cc.sighs.autojs.side.EvalBackend;
import cc.sighs.autojs.side.EvalSide;
import dev.latvian.mods.kubejs.script.ScriptType;
import net.minecraft.server.MinecraftServer;

/**
 * Everything that exists only when KubeJS is installed.
 *
 * <p>AutoJS reaches KubeJS through this class and nowhere else, so that the mod never loads a KubeJS type on a
 * game that has none: KubeJS is a soft dependency, and a single unresolved class in the mod constructor or in
 * {@code AutoJSBackends} would fail the whole mod with {@code NoClassDefFoundError}. Loading this class is
 * therefore the same statement as "KubeJS mode".
 */
public final class KubeJSIntegration {
    private KubeJSIntegration() {
    }

    /** The KubeJS-backed server side. */
    public static EvalBackend serverBackend(MinecraftServer server) {
        return new ServerEvalBackend(server);
    }

    /** The KubeJS-backed client side. */
    public static EvalBackend clientBackend() {
        return new ClientEvalBackend();
    }

    /** The tools that read KubeJS's own state, and that mean nothing without it. */
    public static void registerTools() {
        AutoJSRuntime.registerTool(new KubeJSLogTool());
    }

    /**
     * Which eval side a KubeJS script type belongs to. Startup scripts have no side of their own — they run
     * before either exists — so they report the server, which is also how they behave wherever the two differ.
     */
    public static EvalSide sideOf(ScriptType type) {
        return type.isClient() ? EvalSide.CLIENT : EvalSide.SERVER;
    }

    /** Where a script's output goes: KubeJS's own console, which is what reaches its log file. */
    public static ScriptOutput outputOf(ScriptType type) {
        return type.console::info;
    }
}
