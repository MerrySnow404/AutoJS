package com.sighs.autojs.neoforge.client;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

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
 * <p>This class is target-owned because the input object itself is what moved. Up to 1.21.1 a script wrote the
 * impulse fields of a {@code net.minecraft.client.player.Input} subclass directly; 26.1 deletes that class and
 * splits its job in two — {@link ClientInput} carries the {@code moveVector} the client's physics reads, and
 * the {@link Input} record carries the key masks the server is told about — so the values a script sets are
 * assembled here instead. {@code ClientInput.tick()} is already empty, unlike the keyboard's own override;
 * the override below states the intent and keeps the guarantee even if a later revision gives the base class
 * work to do.
 *
 * <p>The bridge reaches it through {@code com.sighs.autojs.mc.ClientApi#scriptInput()}, so a target with a
 * different input object only replaces this file, that interface and the bridge together.
 */
public final class AutoJSInput extends ClientInput {
    /**
     * Sets everything one {@code AutoJS.move(forward, strafe, jump, sprint, crouch)} call decides.
     *
     * <p>The vector is what {@code LocalPlayer.applyInput} reads, written as {@code (strafe, forward)} — the
     * same pair of impulses the older {@code Input} kept in fields named {@code leftImpulse} and
     * {@code forwardImpulse}. It is deliberately not normalised, which is how those fields behaved: a script
     * that asks for half speed still gets half speed.
     *
     * <p>The sprint key is deliberately left unset. On the older targets the bridge set the sprinting flag on
     * the player and never touched a key, and this keeps that split, so a script's {@code sprint} means the
     * same thing on both sides of the version boundary.
     */
    public void drive(boolean forward, boolean backward, boolean left, boolean right,
                      boolean jump, boolean crouch, float strafeImpulse, float forwardImpulse) {
        this.keyPresses = new Input(forward, backward, left, right, jump, crouch, false);
        this.moveVector = new Vec2(strafeImpulse, forwardImpulse);
    }

    @Override
    public void tick() {
        // Deliberately empty: the values are whatever AutoJS.move last set, and must survive the tick.
    }
}
