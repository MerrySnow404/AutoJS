package com.sighs.autojs.neoforge;

import com.sighs.autojs.neoforge.backend.ReloadableSide;
import dev.latvian.mods.kubejs.script.ScriptManager;

import java.util.concurrent.Callable;

/**
 * A side whose scripts are KubeJS scripts.
 *
 * <p>Adds what the KubeJS specific tools need on top of the shared backend: the manager itself, so
 * {@code kubejs_log} can read that side's console. Scheduling and reloading are not KubeJS specific, so they
 * come from {@link com.sighs.autojs.engine.EngineSideBackend} and {@link ReloadableSide}.
 */
public interface KubeJSSideBackend extends ReloadableSide {
    /** The live manager, or null while this side has no scripts loaded. */
    ScriptManager manager();

    /** Runs work on the thread that owns this side and waits for it. */
    <T> T submit(Callable<T> work, long timeoutMs, T onTimeout);
}
