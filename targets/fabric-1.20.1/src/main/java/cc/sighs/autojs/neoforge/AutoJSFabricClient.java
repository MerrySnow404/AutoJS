package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.AutoJSRuntime;
import cc.sighs.autojs.mc.McApi;
import cc.sighs.autojs.neoforge.client.ClientScriptBridge;
import cc.sighs.autojs.neoforge.mc.AutoJSClient;
import cc.sighs.autojs.neoforge.script.AutoJSTools;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Client-only wiring.
 *
 * <p>Nothing on the server path refers to this class: it is named under {@code client} in
 * {@code fabric.mod.json}, and Fabric invokes that entry point only on a client, so a dedicated server never
 * loads it and therefore never touches a client-only event type or the client half of the {@code AutoJS}
 * global.
 *
 * <p>Where Forge has a single mod class that decides for itself whether the dist is a client, Fabric's entry
 * point list does that deciding, which is why this class also owns the {@code registered} flag on the Forge
 * side: the callback is invoked once and needs none.
 *
 * <p>The tick comes from {@code ClientTickEvents.END_CLIENT_TICK}, which is the "after the tick" event Forge
 * spells {@code TickEvent.Phase.END}.
 */
public final class AutoJSFabricClient implements ClientModInitializer {
    private boolean started;

    @Override
    public void onInitializeClient() {
        McApi.installClient(AutoJSClient.INSTANCE);
        AutoJSTools.setClientBridge(new ClientScriptBridge());
        ClientTickEvents.END_CLIENT_TICK.register(client -> onFirstClientTick());
    }

    /**
     * The client side is registered from the first tick rather than from entry-point execution: by then the
     * game window exists, so a snippet that touches the client has something to touch.
     *
     * <p>The game thread is handed in rather than read by the facade, because the facade is loaded on a
     * dedicated server too, where no Minecraft client exists.
     */
    private void onFirstClientTick() {
        if (started) {
            return;
        }
        started = true;
        AutoJSRuntime.addSide(AutoJSBackends.client(() -> Minecraft.getInstance()));
    }
}
