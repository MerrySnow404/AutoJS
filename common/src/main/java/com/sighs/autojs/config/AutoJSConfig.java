package com.sighs.autojs.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AutoJS configuration, stored as JSON in the game directory.
 *
 * <p>The endpoint evaluates arbitrary JavaScript inside the running game, which is remote code
 * execution by design. It therefore binds to the loopback interface only, and rejects requests whose
 * {@code Origin} header is not local. Change {@link #bindAddress} only if you understand that.
 */
public final class AutoJSConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Master switch. When false, AutoJS never opens a socket. */
    public boolean enabled = true;

    /**
     * Runs the side's KubeJS script context without its sandbox: the class filter is bypassed, the
     * guard that forbids registering event handlers outside the loading phase is lifted, and reflection
     * objects can be wrapped again.
     *
     * <p>This is what makes the endpoint a real debugging tool, and it is also why the endpoint must stay
     * on loopback: it turns "run JavaScript" into "run arbitrary Java". It applies to the whole script
     * context of that side, not only to snippets evaluated over the endpoint, because KubeJS keeps one
     * context per side and thread.
     */
    public boolean unrestricted = true;

    /** Interface the MCP listener binds to. Loopback only by default. */
    public String bindAddress = "127.0.0.1";

    /**
     * TCP port of the MCP listener.
     *
     * <p>Overridable at launch so several instances can run side by side without editing this file: the
     * {@code autojs.port} system property wins over the {@code AUTOJS_PORT} environment variable, and both
     * win over this file. Setting it to {@code 0} asks the OS for a free port, which is then reported by
     * {@code status} and in the log.
     */
    public int port = 25585;

    /** Expose the server side (dedicated server, or the integrated server of a singleplayer world). */
    public boolean enableServerSide = true;

    /** Expose the client side. Only meaningful on a client; a dedicated server has no client side. */
    public boolean enableClientSide = true;

    /** Optional bearer token. Empty means anyone who can reach the port may evaluate. */
    public String token = "";

    /** How long an eval may block before the request is reported as a timeout. */
    public long evalTimeoutMs = 5000L;

    /** Longest result string handed back to the client. */
    public int maxResultChars = 100000;

    /** Log every evaluated snippet. */
    public boolean logEval = true;

    public static AutoJSConfig load(Path file) {
        AutoJSConfig config = null;
        if (Files.isRegularFile(file)) {
            try {
                config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AutoJSConfig.class);
                if (config == null) {
                    LOGGER.error("{} is empty; using defaults", file);
                }
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.error("Could not read {}; using defaults", file, e);
            }
        } else {
            try {
                new AutoJSConfig().save(file);
            } catch (IOException e) {
                LOGGER.error("Could not write the default config to {}", file, e);
            }
        }
        if (config == null) {
            config = new AutoJSConfig();
        }
        config.applyLaunchPort();
        return config;
    }

    /**
     * Applies the launch-time port override. It exists so a second game instance, or an automated
     * launcher, can pin the port without touching the config file every instance shares. Nothing is
     * written back, so the file keeps the port the user chose.
     */
    private void applyLaunchPort() {
        var source = "autojs.port";
        var raw = System.getProperty(source);
        if (raw == null || raw.isBlank()) {
            source = "AUTOJS_PORT";
            raw = System.getenv(source);
        }
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            var overridden = Integer.parseInt(raw.trim());
            if (overridden < 0 || overridden > 65535) {
                LOGGER.warn("Ignoring {}={}: a port must be between 0 and 65535", source, raw);
                return;
            }
            port = overridden;
            LOGGER.info("MCP port overridden to {} by {}", overridden, source);
        } catch (NumberFormatException e) {
            LOGGER.warn("Ignoring {}={}: not a number", source, raw);
        }
    }

    public void save(Path file) throws IOException {
        var parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, GSON.toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    public boolean requiresToken() {
        return token != null && !token.isBlank();
    }
}
