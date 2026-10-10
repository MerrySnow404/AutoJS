package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.AutoJS;
import cc.sighs.autojs.AutoJSRuntime;
import cc.sighs.autojs.neoforge.server.ServerEvalBackend;
import cc.sighs.autojs.neoforge.tools.KubeJSLogTool;
import cc.sighs.autojs.neoforge.tools.ReloadScriptsTool;
import cc.sighs.autojs.side.EvalSide;
import com.google.gson.JsonObject;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * NeoForge entry point for Minecraft 1.21.1.
 *
 * <p>It only wires lifecycle and the KubeJS-specific tools: the shared half of AutoJS lives in
 * {@code common/} and never mentions NeoForge or KubeJS, while everything that reaches into them lives in
 * this target.
 */
@Mod(AutoJS.MOD_ID)
public final class AutoJSNeoForge121 {
    public AutoJSNeoForge121(IEventBus modEventBus, ModContainer container) {
        AutoJSRuntime.init(FMLPaths.CONFIGDIR.get().resolve(AutoJS.MOD_ID + ".json"), serverInfo(container));
        AutoJSRuntime.registerTool(new KubeJSLogTool());
        AutoJSRuntime.registerTool(new ReloadScriptsTool());

        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                AutoJSRuntime.addSide(new ServerEvalBackend(event.getServer())));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
                AutoJSRuntime.removeSide(EvalSide.SERVER));

        if (FMLEnvironment.dist.isClient()) {
            AutoJSNeoForgeClient.register();
        }
    }

    private static JsonObject serverInfo(ModContainer container) {
        var info = new JsonObject();
        info.addProperty("name", AutoJS.MOD_ID);
        info.addProperty("title", AutoJS.MOD_NAME);
        info.addProperty("version", String.valueOf(container.getModInfo().getVersion()));
        return info;
    }
}
