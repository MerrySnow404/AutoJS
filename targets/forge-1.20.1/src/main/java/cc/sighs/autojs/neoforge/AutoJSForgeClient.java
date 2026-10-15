package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.AutoJSRuntime;
import cc.sighs.autojs.mc.McApi;
import cc.sighs.autojs.neoforge.client.ClientScriptBridge;
import cc.sighs.autojs.neoforge.mc.AutoJSClient;
import cc.sighs.autojs.neoforge.script.AutoJSTools;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/**
 * Client-only wiring.
 *
 * <p>Nothing on the server path refers to this class: the mod constructor calls {@link #register()} only
 * when the dist is a client, so a dedicated server never loads it and therefore never touches a
 * client-only event type or the client half of the {@code AutoJS} global.
 *
 * <p>Forge has no {@code ClientTickEvent} type of its own: the client's tick arrives as
 * {@code TickEvent.ClientTickEvent}, and every tick event is fired twice, once per {@link TickEvent.Phase}, so
 * the phase is checked rather than relying on a class that means "after".
 */
public final class AutoJSForgeClient {
    private static boolean registered;
    private static boolean started;

    private AutoJSForgeClient() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        McApi.installClient(AutoJSClient.INSTANCE);
        AutoJSTools.setClientBridge(new ClientScriptBridge());
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                onFirstClientTick();
            }
        });
    }

    /**
     * The client side is registered from the first tick rather than from mod construction: by then the
     * game window exists, so a snippet that touches the client has something to touch.
     *
     * <p>The game thread is handed in rather than read by the facade, because the facade is loaded on a
     * dedicated server too, where no Minecraft client exists.
     */
    private static void onFirstClientTick() {
        if (started) {
            return;
        }
        started = true;
        AutoJSRuntime.addSide(AutoJSBackends.client(() -> Minecraft.getInstance()));
    }
}
