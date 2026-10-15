package cc.sighs.autojs.neoforge.unrestricted;

import dev.latvian.mods.kubejs.script.KubeJSContextFactory;
import dev.latvian.mods.kubejs.script.KubeJSServerContext;
import dev.latvian.mods.rhino.NativeJavaClass;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.type.TypeInfo;
import dev.latvian.mods.rhino.util.ClassVisibilityContext;

/**
 * The server-side script context with KubeJS's sandbox removed.
 *
 * <p>{@code KubeJSServerContext} is a bare subclass of {@code KubeJSContext}, so this mirrors its client
 * sibling rather than being able to share an implementation: a Java class cannot inherit the three
 * overrides from an interface, since a superclass method always wins.
 */
public class UnrestrictedKubeJSServerContext extends KubeJSServerContext {
    public UnrestrictedKubeJSServerContext(KubeJSContextFactory factory) {
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
