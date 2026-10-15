package cc.sighs.autojs.engine;

import cc.sighs.autojs.eval.EvalResult;
import cc.sighs.autojs.neoforge.backend.ReloadableSide;
import cc.sighs.autojs.side.EvalSide;

/**
 * The JavaScript engine that runs one side's scripts.
 *
 * <p>AutoJS ships two of them and every target picks one per side: the KubeJS one drives a KubeJS script
 * context, the Rhino one drives AutoJS's own kernel when KubeJS is not installed. Both of them are tied to a
 * KubeJS/Rhino generation, so each target provides its own implementations of this interface — but nothing
 * here names KubeJS, Rhino or a loader, so a target replaces an engine without touching the side backends
 * that call it, and without the dispatch and timeout code being copied along.
 *
 * <p>What belongs here and what does not: reaching a script context, deciding whether the engine can run at
 * all, and lifting the engine's own sandbox are this interface's business. Scheduling the work onto the side's
 * game thread and turning a stuck thread into a timeout are not — see {@link EngineSideBackend}, which is
 * shared by both engines and both sides.
 *
 * <p>Implementations must be safe to call from the HTTP request thread (which only asks
 * {@link #available(EvalSide)} and {@link #unrestricted()}); {@link #eval(EvalSide, String)} and
 * {@link #reload(EvalSide)} are only ever entered on the game thread of the side they are given.
 */
public interface ScriptEngine {
    /** Whether this engine can evaluate the given side right now. Cheap; called on every request. */
    boolean available(EvalSide side);

    /**
     * Whether this side's scripts currently run without the engine's own sandbox.
     *
     * <p>Lifting the sandbox is the engine's business, not the caller's: a KubeJS engine has a class filter,
     * an event-registration guard and path wrappers that a debugging install removes, while the bare Rhino
     * kernel has no sandbox to lift in the first place. Callers ask this instead of reading the AutoJS config
     * or naming a KubeJS class.
     */
    boolean unrestricted();

    /**
     * Evaluates on the given side's game thread.
     *
     * <p>The result carries whatever the engine logged while running the snippet as its diagnostics, because
     * some failures are never raised back to the caller.
     */
    EvalResult eval(EvalSide side, String code);

    /** Rebuilds the given side's script scope, on its game thread. */
    ReloadableSide.ReloadOutcome reload(EvalSide side);
}
