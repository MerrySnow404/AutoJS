package com.sighs.autojs.neoforge;

import com.sighs.autojs.engine.EngineSideBackend;
import com.sighs.autojs.engine.ScriptEngine;
import com.sighs.autojs.neoforge.kubejs.KubeJSEngine;

/**
 * The KubeJS half of a side backend: it names the engine, and everything else comes from
 * {@link EngineSideBackend}.
 *
 * <p>Scheduling, the timeout wording and the shape of a result are not KubeJS specific, so those live in
 * {@code common}; what is left here is that this side's scripts run in a KubeJS context. Which engine a side
 * runs on is decided once, in {@code AutoJSBackends}.
 */
public abstract class AbstractKubeJSSideBackend extends EngineSideBackend implements KubeJSSideBackend {
    @Override
    protected ScriptEngine engine() {
        return KubeJSEngine.INSTANCE;
    }
}
