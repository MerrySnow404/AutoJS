package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.neoforge.backend.ReloadableSide;
import dev.latvian.mods.kubejs.script.ScriptManager;

import java.util.concurrent.Callable;

/**
 * A side whose scripts are KubeJS scripts.
 *
 * <p>Adds what the KubeJS specific tools need on top of the loader-agnostic backend: the manager itself, and a
 * way to run arbitrary work on the thread that owns the side. Reloading is not KubeJS specific any more, so it
 * comes from {@link ReloadableSide}.
 */
public interface KubeJSSideBackend extends ReloadableSide {
    /** The live manager, or null while this side has no scripts loaded. */
    ScriptManager manager();

    /** Runs work on the thread that owns this side and waits for it. */
    <T> T submit(Callable<T> work, long timeoutMs, T onTimeout);
}
