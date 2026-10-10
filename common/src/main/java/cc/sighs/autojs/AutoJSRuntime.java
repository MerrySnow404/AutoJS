package cc.sighs.autojs;

import cc.sighs.autojs.config.AutoJSConfig;
import cc.sighs.autojs.mcp.McpServer;
import cc.sighs.autojs.mcp.McpTool;
import cc.sighs.autojs.mcp.McpToolRegistry;
import cc.sighs.autojs.mcp.tools.EvalJsTool;
import cc.sighs.autojs.mcp.tools.StatusTool;
import cc.sighs.autojs.side.EvalBackend;
import cc.sighs.autojs.side.EvalBackends;
import cc.sighs.autojs.side.EvalSide;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Owns the AutoJS endpoint of this game instance.
 *
 * <p>The skeleton is deliberately small: the config decides whether a listener exists at all, sides are
 * added and removed by the target as the game starts and stops them, and every side shares the one
 * listener. A side is only advertised while it can actually evaluate, so an agent connecting to a
 * dedicated server sees the server side, and one connecting to a client also sees the client side.
 *
 * <p>Lifecycle, as seen from a target:
 * <ol>
 *     <li>{@link #init(Path, JsonObject)} once during mod construction.</li>
 *     <li>{@link #registerTool(McpTool)} for the tools that need loader or game types.</li>
 *     <li>{@link #addSide(EvalBackend)} when a side becomes usable; this opens the listener.</li>
 *     <li>{@link #removeSide(EvalSide)} when that side goes away; the listener closes with the last one.</li>
 * </ol>
 */
public final class AutoJSRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS");

    private static AutoJSConfig config;
    private static McpToolRegistry tools;
    private static McpServer server;

    private AutoJSRuntime() {
    }

    public static synchronized void init(Path configFile, JsonObject serverInfo) {
        if (config != null) {
            return;
        }

        config = AutoJSConfig.load(configFile);
        if (!config.enabled) {
            LOGGER.info("AutoJS is disabled in {}", configFile);
            return;
        }

        Supplier<JsonObject> info = () -> serverInfo;
        tools = new McpToolRegistry();
        tools.register(new StatusTool(config, info));
        tools.register(new EvalJsTool(config));
        server = new McpServer(config, tools, info);
        LOGGER.info("AutoJS {} initialised; config at {}", AutoJS.MOD_NAME, configFile);
    }

    /**
     * Adds a tool. This is how a target contributes the tools that need KubeJS or Minecraft types,
     * which must not leak into {@code common}. Call it from mod construction, after {@link #init}.
     */
    public static synchronized void registerTool(McpTool tool) {
        if (tools == null) {
            LOGGER.warn("Ignoring tool '{}': AutoJS is not initialised", tool.name());
            return;
        }
        tools.register(tool);
    }

    /** Registers a side and opens the listener if needed. Call from the game thread. */
    public static synchronized void addSide(EvalBackend backend) {
        if (server == null) {
            return;
        }
        var enabledForSide = backend.side().isServer() ? config.enableServerSide : config.enableClientSide;
        if (!enabledForSide) {
            LOGGER.info("Side '{}' is disabled by the AutoJS config; not exposing it", backend.side().id());
            return;
        }

        EvalBackends.register(backend);
        if (!server.isRunning()) {
            try {
                server.start();
            } catch (IOException e) {
                LOGGER.error("Could not bind the MCP endpoint on {}:{}", config.bindAddress, config.port, e);
            }
        }
    }

    /** Unregisters a side; the listener closes with the last one. Call from the game thread. */
    public static synchronized void removeSide(EvalSide side) {
        EvalBackends.unregister(side);
        if (EvalBackends.isEmpty() && server != null && server.isRunning()) {
            server.close();
        }
    }

    public static synchronized void shutdown() {
        for (var backend : EvalBackends.all()) {
            EvalBackends.unregister(backend.side());
        }
        if (server != null) {
            server.close();
        }
    }

    public static synchronized boolean isEnabled() {
        return server != null;
    }

    public static synchronized AutoJSConfig config() {
        return config;
    }
}
