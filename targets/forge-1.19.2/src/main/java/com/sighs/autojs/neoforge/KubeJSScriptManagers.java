package com.sighs.autojs.neoforge;

import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;

/**
 * Finds the live KubeJS script manager of a side.
 *
 * <p>On this KubeJS generation there is no plugin callback that hands a manager over, and none is needed:
 * {@code ScriptType} holds a public {@code Supplier<ScriptManager>} per side, and each supplier is the
 * accessor KubeJS itself answers the same question with ({@code ServerScriptManager.instance} for the server,
 * KubeJS's own getters for startup and client). Asking the enum means this class never has to name the
 * {@code KubeJS} class, which is one more class-file reference that cannot go stale between releases.
 *
 * <p>Nothing is cached: each call re-reads the supplier, so a manager that KubeJS replaces on a reload is
 * picked up on the next request rather than after a game restart. That is also why the 2101 generation's
 * "capture it in a load hook" indirection has no equivalent here.
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
}
