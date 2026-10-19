package com.sighs.autojs.neoforge;

import com.sighs.autojs.neoforge.kubejs.KubeJSIntegration;
import com.sighs.autojs.neoforge.script.AutoJSTools;
import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.rhino.util.wrap.TypeWrappers;

/**
 * KubeJS plugin entry, declared in {@code kubejs.plugins.txt}.
 *
 * <p>Going through the plugin API is what makes AutoJS survive {@code /reload}: KubeJS builds a fresh
 * context on every reload, and these hooks are the supported way to observe and adjust the side that owns it.
 *
 * <p>Only KubeJS loads this class, which is exactly when the KubeJS-backed global is the right one: the
 * {@code AutoJS} bound here reads the KubeJS console and the KubeJS globals.
 *
 * <p>Two things about this class are the 1902 generation rather than the 2101 one it replaces, and both are
 * visible in it. {@code KubeJSPlugin} is a class here and not an interface, so this extends instead of
 * implements. And the hook it hangs the sandbox removal on is {@code registerBindings}, because this
 * generation has no {@code beforeScriptsLoaded}/{@code afterScriptsLoaded} callbacks at all: binding
 * registration happens inside {@code ScriptManager.load()} right after the context is built and before any
 * script file is evaluated, which is the same window the 2101 generation reached through its own hooks.
 */
public final class AutoJSKubeJSPlugin extends KubeJSPlugin {
    /** The global that scripts and the MCP endpoint both use. */
    public static final String BINDING = "AutoJS";

    /** Runs while a context is being built, once per side and per thread, which is where a global belongs. */
    @Override
    public void registerBindings(BindingsEvent bindings) {
        bindings.add(BINDING, new AutoJSTools(KubeJSIntegration.sideOf(bindings.getType()),
                KubeJSIntegration.outputOf(bindings.getType())));

        // The context is built by now, so this is the window in which a debugging install can lift the sandbox
        // for the script files themselves and not only for snippets sent over the endpoint. Only the class
        // filter is lifted here: the path wrappers are not touched during the load, for the reason in
        // AutoJSUnrestricted#installPermissivePaths.
        AutoJSUnrestricted.install(bindings.manager);
    }

    /**
     * Nothing to register: AutoJS contributes bindings and an unlocked context, not conversions.
     *
     * <p>Overridden and left empty on purpose, so that the file records what this generation offers the
     * sandbox removal. Unlike 2101, a plugin callback here never sees the context that will be built, so the
     * one thing this hook could reach — the type wrapper registry — is reached from {@link BindingsEvent}
     * instead.
     */
    @Override
    public void registerTypeWrappers(ScriptType type, TypeWrappers registry) {
    }
}
