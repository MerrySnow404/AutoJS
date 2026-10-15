package cc.sighs.autojs.neoforge.backend;

import cc.sighs.autojs.side.EvalBackend;

/**
 * A side whose scripts can be re-run without restarting the game.
 *
 * <p>What a reload means belongs to the engine behind the side: KubeJS re-runs its script files, while the
 * Rhino kernel throws away the scope and builds a new one. Everything the {@code reload_scripts} tool needs is
 * here, so that tool does not have to know which of the two it is talking to.
 */
public interface ReloadableSide extends EvalBackend {
    /** Re-runs this side's scripts. Blocking, and called off the game thread. */
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
