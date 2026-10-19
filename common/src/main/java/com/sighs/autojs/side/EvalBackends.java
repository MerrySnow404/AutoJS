package com.sighs.autojs.side;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of the sides that are currently able to evaluate. A side appears here only while its game
 * side exists: the server side is registered when a server starts and removed when it stops, so on a
 * client the server side is present exactly while a world is loaded.
 */
public final class EvalBackends {
    private static final Map<EvalSide, EvalBackend> BACKENDS = new ConcurrentHashMap<>();

    private EvalBackends() {
    }

    public static void register(EvalBackend backend) {
        BACKENDS.put(backend.side(), backend);
    }

    public static void unregister(EvalSide side) {
        BACKENDS.remove(side);
    }

    public static EvalBackend get(EvalSide side) {
        return BACKENDS.get(side);
    }

    public static List<EvalBackend> all() {
        var backends = new ArrayList<>(BACKENDS.values());
        backends.sort((a, b) -> a.side().compareTo(b.side()));
        return backends;
    }

    public static boolean isEmpty() {
        return BACKENDS.isEmpty();
    }

    /**
     * Resolves the requested side id. {@code null}, blank and {@code "auto"} pick the server when it
     * is usable and fall back to the client, which is what an agent wants when it does not care.
     */
    public static EvalSide resolve(String requested) {
        if (requested == null || requested.isBlank() || requested.equalsIgnoreCase("auto")) {
            var server = BACKENDS.get(EvalSide.SERVER);
            if (server != null && server.isAvailable()) {
                return EvalSide.SERVER;
            }
            var client = BACKENDS.get(EvalSide.CLIENT);
            if (client != null && client.isAvailable()) {
                return EvalSide.CLIENT;
            }
            return BACKENDS.containsKey(EvalSide.SERVER) ? EvalSide.SERVER : EvalSide.CLIENT;
        }
        var side = EvalSide.byId(requested);
        if (side == null) {
            throw new IllegalArgumentException("Unknown side '" + requested + "'; expected server, client or auto");
        }
        return side;
    }
}
