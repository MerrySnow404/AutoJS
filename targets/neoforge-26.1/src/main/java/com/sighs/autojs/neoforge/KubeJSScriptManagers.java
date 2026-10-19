package com.sighs.autojs.neoforge;

import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;

/**
 * Holds the live KubeJS script managers, one per side.
 *
 * <p>Refreshed on every script load, which includes every {@code /reload} and resource reload, because
 * KubeJS replaces the context inside the manager each time.
 */
public final class KubeJSScriptManagers {
    private static volatile ScriptManager startup;
    private static volatile ScriptManager server;
    private static volatile ScriptManager client;

    private KubeJSScriptManagers() {
    }

    static void onScriptsLoaded(ScriptManager manager) {
        var type = manager.scriptType;
        if (type == ScriptType.SERVER) {
            server = manager;
        } else if (type == ScriptType.CLIENT) {
            client = manager;
        } else if (type == ScriptType.STARTUP) {
            startup = manager;
        }
    }

    public static ScriptManager byType(ScriptType type) {
        if (type == ScriptType.SERVER) {
            return server;
        }
        if (type == ScriptType.CLIENT) {
            return client;
        }
        if (type == ScriptType.STARTUP) {
            return startup;
        }
        return null;
    }

    public static ScriptManager server() {
        return server;
    }

    public static ScriptManager client() {
        return client;
    }

    public static ScriptManager startup() {
        return startup;
    }
}
