package com.sighs.autojs.neoforge.mc;

import com.sighs.autojs.mc.ClientApi;
import com.sighs.autojs.neoforge.client.AutoJSInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.Input;

/**
 * {@link ClientApi} for Minecraft 1.20.1.
 *
 * <p>Client-only in the same way {@code ClientScriptBridge} is: the mod constructor never names it, and the
 * client entry point installs it, so a dedicated server neither loads this class nor the {@code Minecraft} it
 * reaches.
 *
 * <p>Two of the three answers are this version's own, and both are the "before" side of a change:
 * {@code Minecraft.getFps()} does exist here (1.20 added it), while the first-run accessibility screen
 * ({@code AccessibilityOnboardingScreen}) does not exist until 1.20.2, so this version has no such prompt to
 * recognise. The script's own movement input is {@link AutoJSInput}, whose tick takes the two arguments this
 * version passes it.
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

    /**
     * Never true here: {@code AccessibilityOnboardingScreen} arrives at 1.20.2, and before that the first-run
     * prompt is the ordinary accessibility options screen, which is reached from the options menu and is not
     * distinguishable from it.
     */
    @Override
    public boolean isFirstRunScreen(Screen screen) {
        return false;
    }

    @Override
    public Input scriptInput() {
        return new AutoJSInput();
    }
}
