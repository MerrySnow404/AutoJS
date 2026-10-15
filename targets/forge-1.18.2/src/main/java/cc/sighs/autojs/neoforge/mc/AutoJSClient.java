package cc.sighs.autojs.neoforge.mc;

import cc.sighs.autojs.mc.ClientApi;
import cc.sighs.autojs.neoforge.client.AutoJSInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.Input;

/**
 * {@link ClientApi} for Minecraft 1.18.2.
 *
 * <p>Client-only in the same way {@code ClientScriptBridge} is: the mod constructor never names it, and the
 * client entry point installs it, so a dedicated server neither loads this class nor the {@code Minecraft} it
 * reaches.
 *
 * <p>Two of the three answers are this version's own, and both are the "before" side of a change:
 * {@code Minecraft.getFps()} does not exist yet (1.20 added it), and the first-run accessibility screen
 * ({@code AccessibilityOnboardingScreen}) does not exist until 1.20.2, so this version has no such prompt to
 * recognise. The script's own movement input is {@link AutoJSInput}, whose tick takes the one argument this
 * version passes it — 1.19 adds a second one, which is why that file is a target file too.
 */
public final class AutoJSClient implements ClientApi {
    /** Stateless: every answer is a read of the client. */
    public static final AutoJSClient INSTANCE = new AutoJSClient();

    private AutoJSClient() {
    }

    /**
     * The counter Minecraft itself renders.
     *
     * <p>1.18.2 has no {@code getFps()}: the number is a private static field of {@code Minecraft}, updated
     * once a second in {@code runTick}, and what 1.20 turned into {@code getFps()} is that same field. Here it
     * is still only reachable through the one place vanilla publishes it, the public {@code fpsString} field,
     * which {@code runTick} rebuilds from it every second in {@code "%d fps T: ..."} form with
     * {@code Locale.ROOT}. Reading the leading integer back out of that string is therefore exactly the number
     * the 1.20+ targets report, with no reflection into a private field.
     *
     * <p>Empty before the first second of play and unparsable then, which is what the failing parse returns.
     */
    @Override
    public int fps() {
        var text = Minecraft.getInstance().fpsString;
        if (text == null) {
            return 0;
        }

        var digits = 0;
        while (digits < text.length() && Character.isDigit(text.charAt(digits))) {
            digits++;
        }
        if (digits == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(text.substring(0, digits));
        } catch (NumberFormatException e) {
            return 0;
        }
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
