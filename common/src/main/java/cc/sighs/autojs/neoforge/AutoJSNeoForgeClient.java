package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.AutoJSRuntime;
import cc.sighs.autojs.neoforge.client.ClientScriptBridge;
import cc.sighs.autojs.neoforge.script.AutoJSTools;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only wiring.
 *
 * <p>Nothing on the server path refers to this class: the mod constructor calls {@link #register()} only
 * when the dist is a client, so a dedicated server never loads it and therefore never touches a
 * client-only event type or the client half of the {@code AutoJS} global.
 */
public final class AutoJSNeoForgeClient {
    private static boolean registered;
    private static boolean started;

    private AutoJSNeoForgeClient() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        AutoJSTools.setClientBridge(new ClientScriptBridge());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> onFirstClientTick());
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
