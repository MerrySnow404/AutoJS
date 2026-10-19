package com.sighs.autojs.neoforge;

import com.sighs.autojs.AutoJS;
import com.sighs.autojs.AutoJSRuntime;
import com.sighs.autojs.mc.McApi;
import com.sighs.autojs.neoforge.mc.AutoJSLoader;
import com.sighs.autojs.neoforge.mc.AutoJSVanilla;
import com.sighs.autojs.side.EvalSide;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric entry point for Minecraft 1.20.1.
 *
 * <p>It only wires lifecycle, and installs this version's Minecraft and loader API into {@link McApi}: what the
 * loader knows about the running game, and the vanilla API that moved between the supported versions. The
 * loader- and engine-agnostic half of AutoJS — the MCP endpoint, the side registry and the engine SPI — lives
 * in {@code common/}; everything the Fabric API or this KubeJS generation shapes is target-owned, which is why
 * this entry point sits here and not in the shared tree. Which script engine a side runs on is
 * {@link AutoJSBackends}' decision — KubeJS when it is installed, AutoJS's own Rhino kernel when it is not —
 * and nothing here names a KubeJS type, so the mod also loads on a game without KubeJS.
 *
 * <p>Three things about this class are Fabric rather than Forge, and each is visible on the line that does it:
 * the entry point is a {@code ModInitializer} with an {@code onInitialize()} callback rather than a
 * {@code @Mod} class Forge instantiates itself; the lifecycle arrives from
 * {@code ServerLifecycleEvents.SERVER_STARTED}/{@code SERVER_STOPPING}, which are Fabric API events, rather
 * than from a loader-wide event bus; and the server they hand in is also what
 * {@link AutoJSLoader#setCurrentServer} remembers, because Fabric has no
 * {@code ServerLifecycleHooks.getCurrentServer()} to ask later.
 *
 * <p>The client half is a separate entry point — {@link AutoJSFabricClient}, named under {@code client} in
 * {@code fabric.mod.json} — so a dedicated server never loads it.
 */
public final class AutoJSFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This version's Minecraft and loader API, installed before anything can run a script.
        McApi.install(AutoJSLoader.INSTANCE, AutoJSVanilla.INSTANCE);

        AutoJSRuntime.init(FabricLoader.getInstance().getConfigDir().resolve(AutoJS.MOD_ID + ".json"), serverInfo());
        AutoJSBackends.registerTools();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            AutoJSLoader.setCurrentServer(server);
            AutoJSRuntime.addSide(AutoJSBackends.server(server));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            AutoJSLoader.setCurrentServer(null);
            AutoJSRuntime.removeSide(EvalSide.SERVER);
        });
    }

    /**
     * What the endpoint reports about itself.
     *
     * <p>The version is read back out of the loader's metadata, because Fabric hands an entry point no argument
     * and no container: unlike the NeoForge target there is nothing injected to ask.
     */
    private static JsonObject serverInfo() {
        var info = new JsonObject();
        info.addProperty("name", AutoJS.MOD_ID);
        info.addProperty("title", AutoJS.MOD_NAME);
        info.addProperty("version", modVersion());
        return info;
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer(AutoJS.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
