package com.sighs.autojs.neoforge;

import com.sighs.autojs.AutoJS;
import com.sighs.autojs.AutoJSRuntime;
import com.sighs.autojs.mc.McApi;
import com.sighs.autojs.neoforge.mc.AutoJSLoader;
import com.sighs.autojs.neoforge.mc.AutoJSVanilla;
import com.sighs.autojs.side.EvalSide;
import com.google.gson.JsonObject;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Forge entry point for Minecraft 1.19.2.
 *
 * <p>It only wires lifecycle, and installs this version's Minecraft and loader API into {@link McApi}: what the
 * loader knows about the running game, and the vanilla API that moved between the supported versions. The
 * loader- and engine-agnostic half of AutoJS — the MCP endpoint, the side registry and the engine SPI — lives
 * in {@code common/}; everything the Forge FML API or this KubeJS generation shapes is target-owned, which is
 * why this entry point sits here and not in the shared tree.
 * Which script engine a side runs on is {@link AutoJSBackends}' decision — KubeJS when it is installed,
 * AutoJS's own Rhino kernel when it is not — and nothing here names a KubeJS type, so the mod also loads on a
 * game without KubeJS.
 *
 * <p>Three things about this class are Forge rather than NeoForge, and each is visible on the line that does
 * it: {@code net.minecraftforge} is where FML lives here; {@code FMLJavaModLoadingContext} is not consulted
 * because Forge's {@code @Mod} instantiates the class itself with a no-argument constructor, so the mod
 * version comes from the mod list rather than from an injected container; and the lifecycle events are
 * {@code net.minecraftforge.event.server} ones on {@code MinecraftForge.EVENT_BUS}, which is this loader's
 * single game bus.
 */
@Mod(AutoJS.MOD_ID)
public final class AutoJSForge {
    public AutoJSForge() {
        // This version's Minecraft and loader API, installed before anything can run a script.
        McApi.install(AutoJSLoader.INSTANCE, AutoJSVanilla.INSTANCE);

        AutoJSRuntime.init(FMLPaths.CONFIGDIR.get().resolve(AutoJS.MOD_ID + ".json"), serverInfo());
        AutoJSBackends.registerTools();

        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                AutoJSRuntime.addSide(AutoJSBackends.server(event.getServer())));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
                AutoJSRuntime.removeSide(EvalSide.SERVER));

        if (FMLEnvironment.dist.isClient()) {
            AutoJSForgeClient.register();
        }
    }

    /**
     * What the endpoint reports about itself.
     *
     * <p>The version is read back out of the mod list because Forge hands the mod's own metadata to nobody: the
     * constructor is called reflectively with no arguments, so unlike the NeoForge target there is no injected
     * container to ask.
     */
    private static JsonObject serverInfo() {
        var info = new JsonObject();
        info.addProperty("name", AutoJS.MOD_ID);
        info.addProperty("title", AutoJS.MOD_NAME);
        info.addProperty("version", modVersion());
        return info;
    }

    private static String modVersion() {
        return ModList.get().getModContainerById(AutoJS.MOD_ID)
                .map(container -> String.valueOf(container.getModInfo().getVersion()))
                .orElse("unknown");
    }
}
