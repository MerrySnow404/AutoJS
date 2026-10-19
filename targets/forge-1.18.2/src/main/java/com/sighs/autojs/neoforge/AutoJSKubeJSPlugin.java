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
 * <p>Two things about this class are the 1802 generation rather than the 2101 one it replaces, and both are
 * visible in it. {@code KubeJSPlugin} is a class here and not an interface, so this extends instead of
 * implements. And this generation's plugin callbacks are named without a {@code register}- prefix —
 * {@code addBindings} and {@code addTypeWrappers}, not {@code registerBindings}/{@code registerTypeWrappers} —
 * with no {@code beforeScriptsLoaded}/{@code afterScriptsLoaded} hooks at all: {@code addBindings} is the
 * window in which the context and the scope exist and no script file has run yet, which is what the 2101
 * generation reached through those hooks.
 */
public final class AutoJSKubeJSPlugin extends KubeJSPlugin {
    /** The global that scripts and the MCP endpoint both use. */
    public static final String BINDING = "AutoJS";

    /** Runs while a context is being built, once per side and per script scope, which is where a global belongs. */
    @Override
    public void addBindings(BindingsEvent bindings) {
        bindings.add(BINDING, new AutoJSTools(KubeJSIntegration.sideOf(bindings.getType()),
                KubeJSIntegration.outputOf(bindings.getType())));

        // The context, the scope and every plugin's type wrappers exist by now and no script file has been
        // evaluated yet, so both halves of a debugging install land on the script files themselves and not
        // only on snippets sent over the endpoint.
        AutoJSUnrestricted.install(bindings.manager);
        AutoJSUnrestricted.installPermissivePaths(bindings.manager);
    }

    /**
     * Nothing to register, and deliberately not the place to swap the path wrappers.
     *
     * <p>AutoJS contributes bindings and an unlocked context, not conversions. The path wrappers it wants to
     * replace are registered by KubeJS's own builtin plugin through this same callback, and the order in which
     * two plugins are asked is not something AutoJS can rely on: replacing them here would be undone by the
     * builtin plugin's later registration — or, on this generation, make that registration throw and take the
     * whole load's type wrappers with it. {@link #addBindings(BindingsEvent)} runs after all of them.
     */
    @Override
    public void addTypeWrappers(ScriptType type, TypeWrappers registry) {
    }
}
