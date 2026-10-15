package cc.sighs.autojs.neoforge.rhino;

import cc.sighs.autojs.engine.ScriptEngine;
import cc.sighs.autojs.eval.EvalResult;
import cc.sighs.autojs.neoforge.backend.ReloadableSide.ReloadOutcome;
import cc.sighs.autojs.side.EvalSide;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The engine AutoJS runs on when KubeJS is not installed, for Minecraft 1.18.2.
 *
 * <p>It is the {@link ScriptEngine} around {@link RhinoRuntime}: one runtime per side, so the two sides never
 * share a global, and a reload throws the scope away and lets the next eval build a new one. There is no
 * sandbox to lift here — a bare Rhino context filters nothing — and no script files to re-run, which is why
 * this engine is a thin adapter rather than KubeJS's equal.
 *
 * <p>The Rhino generation this is written against is why the kernel lives in this target: Rhino changed by
 * leaps between the Minecraft versions AutoJS supports, so another target supplies its own kernel behind the
 * same {@link ScriptEngine}. What is 1802 about this one is that a context is associated with a thread rather
 * than built per call — {@code Context.enter()} consults a {@code VMBridge} thread-local and
 * {@code Context.getContext()} throws without one — so {@link RhinoContexts} pairs every enter with an exit
 * instead of leaving one behind for whoever runs next.
 */
public final class RhinoEngine implements ScriptEngine {
    /** Stateless apart from one runtime per side, so both sides can share it. */
    public static final RhinoEngine INSTANCE = new RhinoEngine();

    private final Map<EvalSide, RhinoRuntime> runtimes = new ConcurrentHashMap<>();

    private RhinoEngine() {
    }

    /** A bare Rhino context is always ready; the side is answered by the backend when it is gone. */
    @Override
    public boolean available(EvalSide side) {
        return true;
    }

    /** The base context filters nothing, so no script here was ever confined in the first place. */
    @Override
    public boolean unrestricted() {
        return true;
    }

    @Override
    public EvalResult eval(EvalSide side, String code) {
        return runtime(side).eval(code);
    }

    @Override
    public ReloadOutcome reload(EvalSide side) {
        runtime(side).reset();
        return ReloadOutcome.ok("Rebuilt the " + side.id() + " Rhino scope: globals defined by earlier 'eval_js' "
                + "calls are gone. There are no script files to re-run without KubeJS.");
    }

    /** Built on first use, which is always on the game thread that owns that side. */
    private RhinoRuntime runtime(EvalSide side) {
        return runtimes.computeIfAbsent(side, RhinoRuntime::new);
    }
}
