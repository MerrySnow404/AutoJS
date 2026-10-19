package com.sighs.autojs.neoforge;

import com.sighs.autojs.neoforge.kubejs.KubeJSIntegration;
import com.sighs.autojs.neoforge.script.AutoJSTools;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.TypeWrapperRegistry;

/**
 * KubeJS plugin entry, declared in {@code kubejs.plugins.txt}.
 *
 * <p>Going through the plugin API is what makes AutoJS survive {@code /reload}: KubeJS builds a fresh
 * context on every reload, and these hooks are the supported way to observe and adjust the side that owns it.
 *
 * <p>Only KubeJS loads this class, which is exactly when the KubeJS-backed global is the right one: the
 * {@code AutoJS} bound here reads the KubeJS console and the KubeJS globals.
 */
public final class AutoJSKubeJSPlugin implements KubeJSPlugin {
    /** The global that scripts and the MCP endpoint both use. */
    public static final String BINDING = "AutoJS";

    /** Runs while a context is being built, once per side and per thread, which is where a global belongs. */
    @Override
    public void registerBindings(BindingRegistry bindings) {
        bindings.add(BINDING, new AutoJSTools(KubeJSIntegration.sideOf(bindings.type()),
                KubeJSIntegration.outputOf(bindings.type())));
    }

    @Override
    public void beforeScriptsLoaded(ScriptManager manager) {
        KubeJSScriptManagers.onScriptsLoaded(manager);
    }

    /**
     * Runs after KubeJS has built this side's context factory but before it creates the context, which is
     * the only window in which a debugging install can swap the factory in. See
     * {@link AutoJSUnrestricted#install(dev.latvian.mods.kubejs.script.ScriptType)}.
     */
    @Override
    public void registerTypeWrappers(TypeWrapperRegistry registry) {
        AutoJSUnrestricted.install(registry.scriptType());
    }

    /** Runs after the load, so the wrappers it replaces are the ones the plugins registered. */
    @Override
    public void afterScriptsLoaded(ScriptManager manager) {
        AutoJSUnrestricted.installPermissivePaths(manager);
    }
}
