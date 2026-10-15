package cc.sighs.autojs.neoforge.mc;

import cc.sighs.autojs.mc.ClientApi;
import cc.sighs.autojs.neoforge.client.AutoJSInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.ClientInput;

/**
 * {@link ClientApi} for Minecraft 26.1.
 *
 * <p>Client-only in the same way {@code ClientScriptBridge} is: the mod constructor never names it, and the
 * client entry point installs it, so a dedicated server neither loads this class nor the {@code Minecraft} it
 * reaches.
 *
 * <p>Two of the three answers are the same ones every target since 1.20 gives: {@code Minecraft.getFps()}
 * exists in this version, and the first-run accessibility screen exists. The third is this version's alone —
 * the input a script drives is a {@link ClientInput}, not the {@code net.minecraft.client.player.Input} that
 * 26.1 deletes — which is why this file and the interface it implements are target-owned here.
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
    public ClientInput scriptInput() {
        return new AutoJSInput();
    }
}
