package cc.sighs.autojs.mc;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.Input;

/**
 * The client-only API that moved across the versions AutoJS supports.
 *
 * <p>A dedicated server never installs this and never loads it, exactly like the client half of the
 * {@code AutoJS} global: it is only reachable through {@code ClientScriptBridge}, which only a client ever
 * installs.
 *
 * <p>Three answers, and each is a different kind of drift: {@code Minecraft.getFps()} arrives at 1.20;
 * the first-run accessibility screen is a class older versions do not have; and the movement input a script
 * takes over from the keyboard is a subclass of a class whose own shape changed — {@code Input.tick} takes one
 * argument at 1.18.2 and two from 1.19, and 26.1 replaces {@code Input} outright. Every one of them is the
 * client's business, so none of them can be decided by shared code.
 */
public interface ClientApi {
    /** The frames per second the client is rendering. */
    int fps();

    /** Whether this screen is the first-run accessibility prompt, which older versions do not have. */
    boolean isFirstRunScreen(Screen screen);

    /**
     * A movement input a script owns instead of the keyboard: the version's own {@code Input}, whose values
     * persist until they are changed.
     *
     * <p>It has to be built here rather than in the bridge, because the one thing the bridge does with it —
     * override the tick that would recompute the values from the keyboard — is the part that differs.
     */
    Input scriptInput();
}
