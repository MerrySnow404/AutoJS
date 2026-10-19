package com.sighs.autojs.neoforge.backend;

import com.sighs.autojs.engine.EngineSideBackend;
import com.sighs.autojs.engine.ScriptEngine;
import com.sighs.autojs.neoforge.rhino.RhinoEngine;
import com.sighs.autojs.side.EvalSide;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * A side evaluated by AutoJS's own Rhino kernel, used when KubeJS is not installed.
 *
 * <p>One class covers both sides: the only differences are which event loop the work is handed to and whether
 * that loop exists at all. Each side gets its own runtime inside the shared {@code RhinoEngine}, so the two
 * scopes never share a global.
 */
public final class RhinoSideBackend extends EngineSideBackend {
    private final EvalSide side;
    private final Supplier<Executor> gameThread;

    public RhinoSideBackend(EvalSide side, Supplier<Executor> gameThread) {
        this.side = side;
        this.gameThread = gameThread;
    }

    @Override
    public EvalSide side() {
        return side;
    }

    @Override
    protected Executor gameThread() {
        return gameThread.get();
    }

    @Override
    protected ScriptEngine engine() {
        return RhinoEngine.INSTANCE;
    }

    /** There is no script manager to report, so the interesting part of the answer is which engine this is. */
    @Override
    public String describe() {
        return side.id() + " side running AutoJS's own Rhino kernel (KubeJS is not installed), so KubeJS globals, "
                + "events and script files are not available here";
    }

    /** The Rhino side rebuilds a scope rather than re-running script files, so its timeout says so. */
    @Override
    protected String reloadTimeoutHint() {
        return "the reset may still be running";
    }
}
