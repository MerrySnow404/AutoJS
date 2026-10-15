package cc.sighs.autojs.neoforge.mc;

import cc.sighs.autojs.mc.ClientApi;
import cc.sighs.autojs.neoforge.client.AutoJSInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.Input;

/**
 * {@link ClientApi} for Minecraft 1.21.1.
 *
 * <p>Client-only in the same way {@code ClientScriptBridge} is: the mod constructor never names it, and the
 * client entry point installs it, so a dedicated server neither loads this class nor the {@code Minecraft} it
 * reaches.
 *
 * <p>{@code Minecraft.getFps()} exists in this version (older ones only have the private field the HUD reads),
 * the first-run accessibility screen exists (1.20.1 and older have no such screen), and the script's own
 * movement input is {@link AutoJSInput}, whose tick takes the two arguments this version passes it.
 */
public final class AutoJSClient implements ClientApi {
    /** Stateless: every answer is a read of the client. */
    public static final AutoJSClient INSTANCE = new AutoJSClient();

    private AutoJSClient() {
    }

    @Override
    public int fps() {
        return Minecraft.getInstance().getFps();
    }

    @Override
    public boolean isFirstRunScreen(Screen screen) {
        return screen instanceof AccessibilityOnboardingScreen;
    }

    @Override
    public Input scriptInput() {
        return new AutoJSInput();
    }
}
