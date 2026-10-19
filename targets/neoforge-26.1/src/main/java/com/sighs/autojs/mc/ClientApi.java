package com.sighs.autojs.mc;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.ClientInput;

/**
 * The client-only API that moved across the versions AutoJS supports.
 *
 * <p>A dedicated server never installs this and never loads it, exactly like the client half of the
 * {@code AutoJS} global: it is only reachable through {@code ClientScriptBridge}, which only a client ever
 * installs.
 *
 * <p>Three answers, and each is a different kind of drift: {@code Minecraft.getFps()} arrives at 1.20; the
 * first-run accessibility screen is a class older versions do not have; and the movement input a script takes
 * over from the keyboard is the version's own input object.
 *
 * <p>That last one is why this file is target-owned on 26.1. The interface is the same three questions, but
 * the type the third one answers with is not: {@code net.minecraft.client.player.Input} is deleted at 26.1,
 * and the class the local player drives now is {@code ClientInput}, whose key masks are the separate
 * {@code net.minecraft.world.entity.player.Input} record. A signature naming the old class cannot be compiled
 * there, and a signature naming the new one cannot be compiled on any other target, so the shared copy stays
 * the one the older targets use and this one is 26.1's — replaced together with the bridge that writes it,
 * which is the only caller.
 */
public interface ClientApi {
    /** The frames per second the client is rendering. */
    int fps();

    /** Whether this screen is the first-run accessibility prompt, which older versions do not have. */
    boolean isFirstRunScreen(Screen screen);

    /**
     * A movement input a script owns instead of the keyboard: the version's own input, whose values persist
     * until they are changed.
     *
     * <p>It has to be built here rather than in the bridge, because the one thing the bridge does with it —
     * override the tick that would recompute the values from the keyboard — is the part that differs.
     */
    ClientInput scriptInput();
}
