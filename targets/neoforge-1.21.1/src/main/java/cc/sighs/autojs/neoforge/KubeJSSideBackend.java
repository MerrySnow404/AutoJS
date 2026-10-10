package cc.sighs.autojs.neoforge;

import cc.sighs.autojs.side.EvalBackend;
import dev.latvian.mods.kubejs.script.ScriptManager;

import java.util.concurrent.Callable;

/**
 * A side whose scripts are KubeJS scripts.
 *
 * <p>Adds what the KubeJS specific tools need on top of the loader-agnostic {@link EvalBackend}: the
 * manager itself, a way to run arbitrary work on the thread that owns the side, and a script reload.
 */
public interface KubeJSSideBackend extends EvalBackend {
    /** The live manager, or null while this side has no scripts loaded. */
    ScriptManager manager();

    /** Runs work on the thread that owns this side and waits for it. */
    <T> T submit(Callable<T> work, long timeoutMs, T onTimeout);

    /** Re-runs this side's scripts. */
    ReloadOutcome reload(long timeoutMs);

    /** Outcome of a script reload. */
    record ReloadOutcome(boolean ok, String message, long elapsedMs) {
        public static ReloadOutcome ok(String message) {
            return new ReloadOutcome(true, message, 0L);
        }

        public static ReloadOutcome failed(String message) {
            return new ReloadOutcome(false, message, 0L);
        }
    }
}
