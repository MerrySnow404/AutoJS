package cc.sighs.autojs.side;

import java.util.Locale;

/**
 * A game side that can evaluate JavaScript. The client and the server are separate JVMs on a
 * dedicated server, and separate script contexts inside one JVM when a world is loaded in
 * singleplayer, so they are always addressed explicitly.
 */
public enum EvalSide {
    SERVER("server"),
    CLIENT("client");

    private final String id;

    EvalSide(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isServer() {
        return this == SERVER;
    }

    public boolean isClient() {
        return this == CLIENT;
    }

    /** Returns null for unknown ids so callers can report a useful error. */
    public static EvalSide byId(String id) {
        if (id == null) {
            return null;
        }
        var normalized = id.trim().toLowerCase(Locale.ROOT);
        for (var side : values()) {
            if (side.id.equals(normalized)) {
                return side;
            }
        }
        return null;
    }
}
