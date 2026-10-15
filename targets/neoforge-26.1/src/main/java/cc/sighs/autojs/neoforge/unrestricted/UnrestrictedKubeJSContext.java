package cc.sighs.autojs.neoforge.unrestricted;

import dev.latvian.mods.kubejs.script.KubeJSContext;
import dev.latvian.mods.kubejs.script.KubeJSContextFactory;
import dev.latvian.mods.rhino.NativeJavaClass;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.type.TypeInfo;
import dev.latvian.mods.rhino.util.ClassVisibilityContext;

/**
 * The client-side script context with KubeJS's sandbox removed. Only ever loaded on a client.
 */
public class UnrestrictedKubeJSContext extends KubeJSContext {
    public UnrestrictedKubeJSContext(KubeJSContextFactory factory) {
        super(factory);
    }

    @Override
    public boolean visibleToScripts(String fullClassName, ClassVisibilityContext type) {
        return true;
    }

    @Override
    public NativeJavaClass loadJavaClass(String name, boolean error) {
        return UnrestrictedAccess.loadJavaClass(this, name, error);
    }

    @Override
    public Scriptable wrapAsJavaObject(Scriptable scope, Object javaObject, TypeInfo target) {
        if (UnrestrictedAccess.isRefused(javaObject)) {
            return UnrestrictedAccess.wrapJavaObject(scope, javaObject, target, this);
        }
        return super.wrapAsJavaObject(scope, javaObject, target);
    }
}
