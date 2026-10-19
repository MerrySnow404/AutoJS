package com.sighs.autojs.neoforge.client;

import net.minecraft.client.player.Input;

/**
 * A movement input that a script owns instead of the keyboard.
 *
 * <p>Where a player is, is decided on the client: the keyboard fills these fields in every tick, the client's
 * own physics turns them into a position, and the server accepts what the client reports. Pushing a velocity
 * into the server's player therefore does almost nothing, which is why walking has to be driven from here.
 *
 * <p>Replacing the input object and refusing to let the keyboard recompute it is the whole trick. The cost is
 * that real key presses stop moving the player until the world is left and re-entered, which recreates the
 * local player.
 *
 * <p>This class is target-owned because {@link Input} itself is what moved: the tick that is overridden here
 * takes one argument on this version — whether the player is moving slowly, the flag vanilla uses to scale the
 * impulses by 0.3 — and two from 1.19, and 26.1 replaces the class outright. The bridge reaches it through
 * {@code com.sighs.autojs.mc.ClientApi#scriptInput()}, so a target with a different {@code Input} only replaces
 * this file.
 */
public final class AutoJSInput extends Input {
    @Override
    public void tick(boolean isMovingSlowly) {
        // Deliberately empty: the values are whatever AutoJS.move last set, and must survive the tick.
    }
}
