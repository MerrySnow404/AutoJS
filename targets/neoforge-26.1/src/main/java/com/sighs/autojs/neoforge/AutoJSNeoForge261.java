package com.sighs.autojs.neoforge;

import com.sighs.autojs.AutoJS;
import com.sighs.autojs.AutoJSRuntime;
import com.sighs.autojs.mc.McApi;
import com.sighs.autojs.neoforge.mc.AutoJSLoader;
import com.sighs.autojs.neoforge.mc.AutoJSVanilla;
import com.sighs.autojs.side.EvalSide;
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
 * NeoForge entry point for Minecraft 26.1.
 *
 * <p>It only wires lifecycle, and installs this version's Minecraft and loader API into {@link McApi}: what the
 * loader knows about the running game, and the vanilla API that moved between the supported versions. The
 * loader- and engine-agnostic half of AutoJS — the MCP endpoint, the side registry and the engine SPI — lives
 * in {@code common/}; everything the NeoForge FML API or this KubeJS generation shapes is target-owned, which
 * is why this entry point sits here and not in the shared tree.
 * Which script engine a side runs on is {@link AutoJSBackends}' decision — KubeJS when it is installed,
 * AutoJS's own Rhino kernel when it is not — and nothing here names a KubeJS type, so the mod also loads on a
 * game without KubeJS.
 */
@Mod(AutoJS.MOD_ID)
public final class AutoJSNeoForge261 {
    public AutoJSNeoForge261(IEventBus modEventBus, ModContainer container) {
        // This version's Minecraft and loader API, installed before anything can run a script.
        McApi.install(AutoJSLoader.INSTANCE, AutoJSVanilla.INSTANCE);

        AutoJSRuntime.init(FMLPaths.CONFIGDIR.get().resolve(AutoJS.MOD_ID + ".json"), serverInfo(container));
        AutoJSBackends.registerTools();

        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                AutoJSRuntime.addSide(AutoJSBackends.server(event.getServer())));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
                AutoJSRuntime.removeSide(EvalSide.SERVER));

        if (FMLEnvironment.getDist().isClient()) {
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
