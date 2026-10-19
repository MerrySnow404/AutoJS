package com.sighs.autojs.neoforge;

import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptPack;
import dev.latvian.mods.kubejs.script.ScriptType;

/**
 * Finds the live KubeJS script manager of a side, and the script scope that goes with it.
 *
 * <p>On this KubeJS generation there is no plugin callback that hands a manager over, and none is needed:
 * {@code ScriptType} holds a public {@code Supplier<ScriptManager>} per side, and each supplier is the
 * accessor KubeJS itself answers the same question with ({@code ServerScriptManager.instance.scriptManager}
 * for the server, {@code KubeJS.clientScriptManager} for the client). Asking the enum means this class never
 * has to name the {@code KubeJS} class, which is one more class-file reference that cannot go stale between
 * releases.
 *
 * <p>Nothing is cached: each call re-reads the supplier, so a manager that KubeJS replaces on a reload is
 * picked up on the next request rather than after a game restart.
 *
 * <p>{@link #packOf(ScriptManager)} exists because 1802 keeps the script context and the top-level scope on the
 * {@link ScriptPack} rather than on the manager — see {@link KubeJSEvaluator} for what that means for
 * evaluating a snippet.
 */
public final class KubeJSScriptManagers {
    private KubeJSScriptManagers() {
    }

    /** The manager of one script type, or null when that type has no manager yet. */
    public static ScriptManager byType(ScriptType type) {
        if (type == null) {
            return null;
        }
        var supplier = type.manager;
        return supplier == null ? null : supplier.get();
    }

    public static ScriptManager server() {
        return byType(ScriptType.SERVER);
    }

    public static ScriptManager client() {
        return byType(ScriptType.CLIENT);
    }

    public static ScriptManager startup() {
        return byType(ScriptType.STARTUP);
    }

    /**
     * The script scope a manager's own script files ran in, or null when it has none loaded.
     *
     * <p>The pack is keyed by the directory the manager was built for — {@code ScriptManager.loadFromDirectory}
     * adds one entry under {@code directory.getFileName()} — so asking for that name is asking for the pack
     * KubeJS itself created for this side's {@code kubejs/<side>_scripts}. A manager knows its own directory, so
     * no side-to-directory table is written down here.
     *
     * <p>The fallback covers the packs a datapack adds, which share the scope-building code but are keyed by
     * namespace: if the side's own directory pack is somehow absent, any pack that has a built scope is closer
     * to what the caller wants than nothing at all. Which pack it landed on is not observable from a snippet —
     * they all receive the same bindings — but which <em>scope</em> is, because a global a snippet defines lands
     * in the one the snippet ran in.
     */
    public static ScriptPack packOf(ScriptManager manager) {
        if (manager == null) {
            return null;
        }

        var directory = manager.directory;
        if (directory != null && directory.getFileName() != null) {
            var named = manager.packs.get(directory.getFileName().toString());
            if (named != null) {
                return named;
            }
        }

        for (var pack : manager.packs.values()) {
            if (pack.context != null && pack.scope != null) {
                return pack;
            }
        }
        return null;
    }
}
