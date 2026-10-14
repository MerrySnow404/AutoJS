package cc.sighs.autojs.mcp;

import cc.sighs.autojs.AutoJS;
import cc.sighs.autojs.config.AutoJSConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * MCP endpoint over streamable HTTP, served by the JDK's own HTTP server so AutoJS needs no extra
 * dependency.
 *
 * <p>One listener serves every side of the game instance; the side is chosen per tool call, which
 * keeps a single connection useful for a client that has both a client and an integrated server.
 *
 * <p>Transport notes: {@code POST /mcp} takes JSON-RPC and answers with JSON. There is no server-sent
 * event stream, so {@code GET /mcp} answers 405 as the specification allows, and {@code DELETE} is
 * accepted and ignored because no session state is kept.
 */
public final class McpServer implements AutoCloseable {
    public static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS/Mcp");

    private final AutoJSConfig config;
    private final McpToolRegistry tools;
    private final Supplier<JsonObject> serverInfo;

    private HttpServer http;
    private ExecutorService executor;

    public McpServer(AutoJSConfig config, McpToolRegistry tools, Supplier<JsonObject> serverInfo) {
        this.config = config;
        this.tools = tools;
        this.serverInfo = serverInfo;
    }

    public synchronized boolean isRunning() {
        return http != null;
    }

    public synchronized void start() throws IOException {
        if (http != null) {
            return;
        }

        var address = new InetSocketAddress(config.bindAddress, config.port);
        var server = HttpServer.create(address, 0);
        var pool = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "AutoJS-Mcp");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handleRoot);
        server.createContext(AutoJS.MCP_PATH, this::handleMcp);
        server.start();

        // A port of 0 asks the OS for any free port. Read back what it actually handed us and keep it, so
        // the log line and the status tool report the port a client has to connect to, not the request.
        if (config.port == 0) {
            config.port = server.getAddress().getPort();
        }

        this.http = server;
        this.executor = pool;

        LOGGER.info("MCP endpoint listening on http://{}:{}{}", config.bindAddress, config.port, AutoJS.MCP_PATH);
        if (config.requiresToken()) {
            LOGGER.info("A bearer token is required; send it in the Authorization header");
        } else {
            LOGGER.warn("No token configured: anything that can open {}:{} can run JavaScript in this game",
                    config.bindAddress, config.port);
        }
    }

    @Override
    public synchronized void close() {
        var server = http;
        var pool = executor;
        http = null;
        executor = null;
        if (server != null) {
            server.stop(0);
            LOGGER.info("MCP endpoint stopped");
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ HTTP

    private void handleRoot(HttpExchange exchange) {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respondText(exchange, 405, "method not allowed");
                return;
            }
            var path = exchange.getRequestURI().getPath();
            if (path.equals("/health")) {
                respondText(exchange, 200, "ok");
                return;
            }
            if (!path.equals("/") && !path.equals("/index.json")) {
                respondText(exchange, 404, "not found; the MCP endpoint is " + AutoJS.MCP_PATH);
                return;
            }
            var names = new JsonArray();
            tools.all().forEach(tool -> names.add(tool.name()));

            var json = new JsonObject();
            json.add("serverInfo", serverInfo.get());
            json.addProperty("endpoint", AutoJS.MCP_PATH);
            json.add("tools", names);
            respondJson(exchange, 200, json);
        } catch (IOException e) {
            LOGGER.error("Failed to answer {}", exchange.getRequestURI(), e);
        }
    }

    private void handleMcp(HttpExchange exchange) {
        try (exchange) {
            switch (exchange.getRequestMethod()) {
                case "POST" -> handlePost(exchange);
                case "GET" -> {
                    exchange.getResponseHeaders().add("Allow", "POST, DELETE");
                    respondText(exchange, 405, "this endpoint does not offer a server-sent event stream");
                }
                case "DELETE" -> respondText(exchange, 204, "");
                default -> respondText(exchange, 405, "method not allowed");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to answer an MCP request", e);
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        if (!isOriginAllowed(exchange)) {
            respondText(exchange, 403, "origin not allowed: this endpoint only accepts local clients");
            return;
        }
        if (!isAuthorized(exchange)) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
            respondText(exchange, 401, "unauthorized");
            return;
        }

        JsonElement message;
        try {
            message = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            respondJson(exchange, 200, JsonRpc.error(JsonNull.INSTANCE, JsonRpc.PARSE_ERROR, "Parse error"));
            return;
        }

        if (message.isJsonArray()) {
            var responses = new JsonArray();
            for (var element : message.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    var response = dispatch(element.getAsJsonObject());
                    if (response != null) {
                        responses.add(response);
                    }
                }
            }
            if (responses.isEmpty()) {
                respondText(exchange, 202, "");
            } else {
                respondJson(exchange, 200, responses);
            }
        } else if (message.isJsonObject()) {
            var response = dispatch(message.getAsJsonObject());
            if (response == null) {
                respondText(exchange, 202, "");
            } else {
                respondJson(exchange, 200, response);
            }
        } else {
            respondJson(exchange, 200, JsonRpc.error(JsonNull.INSTANCE, JsonRpc.INVALID_REQUEST, "Invalid Request"));
        }
    }

    // ------------------------------------------------------------- JSON-RPC

    /** Returns the response, or null for notifications and client-to-server responses. */
    private JsonObject dispatch(JsonObject message) {
        if (!message.has("method") || !message.get("method").isJsonPrimitive()) {
            return null;
        }
        var method = message.get("method").getAsString();
        var id = message.get("id");
        var params = message.has("params") && message.get("params").isJsonObject()
                ? message.getAsJsonObject("params")
                : new JsonObject();

        if (id == null || id.isJsonNull()) {
            LOGGER.debug("Ignoring notification {}", method);
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> JsonRpc.result(id, initialize(params));
                case "ping" -> JsonRpc.result(id, new JsonObject());
                case "tools/list" -> JsonRpc.result(id, toolsList());
                case "tools/call" -> callTool(id, params);
                default -> JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method);
            };
        } catch (Exception e) {
            LOGGER.error("MCP method {} failed", method, e);
            return JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, String.valueOf(e));
        }
    }

    private JsonObject initialize(JsonObject params) {
        var requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : PROTOCOL_VERSION;
        var negotiated = SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION;

        var toolCapability = new JsonObject();
        toolCapability.addProperty("listChanged", false);
        var capabilities = new JsonObject();
        capabilities.add("tools", toolCapability);

        var result = new JsonObject();
        result.addProperty("protocolVersion", negotiated);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo.get());
        result.addProperty("instructions",
                "AutoJS exposes the KubeJS script contexts of this running game. Use the 'status' tool first to see which "
                        + "sides are live, then 'eval_js' with side 'server' or 'client' to read or mutate live game state. "
                        + "Snippets run on the side's main thread with the same globals as KubeJS scripts, so they can call "
                        + "Java APIs and wrap results in JSON.stringify(...). Registering event handlers, recipes, tags or "
                        + "commands from a snippet silently does nothing: KubeJS swallows the refusal, so check the "
                        + "'diagnostics' field of the eval result and use 'kubejs_log' for everything logged earlier. After "
                        + "editing script files, call 'reload_scripts' for that side and then 'kubejs_log': a reload clears the "
                        + "console, so what it contains right afterwards is exactly what the load reported. Registry objects "
                        + "cannot be registered at runtime, and globals defined by a snippet are lost on the next reload.");
        return result;
    }

    private JsonObject toolsList() {
        var result = new JsonObject();
        result.add("tools", tools.describe());
        return result;
    }

    private JsonObject callTool(JsonElement id, JsonObject params) {
        var name = params.has("name") && params.get("name").isJsonPrimitive() ? params.get("name").getAsString() : "";
        var tool = tools.get(name);
        if (tool == null) {
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Unknown tool: " + name);
        }

        var arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments")
                : new JsonObject();

        ToolResult outcome;
        try {
            outcome = tool.call(arguments);
        } catch (Exception e) {
            LOGGER.error("Tool {} failed", name, e);
            outcome = ToolResult.error(name + " failed: " + e);
        }

        var text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", outcome.text());
        var content = new JsonArray();
        content.add(text);

        var result = new JsonObject();
        result.add("content", content);
        if (outcome.isError()) {
            result.addProperty("isError", true);
        }
        return JsonRpc.result(id, result);
    }

    // ------------------------------------------------------- request hygiene

    /**
     * Rejects non-local browser origins. Without this a page the user visits could drive the endpoint
     * through DNS rebinding, which matters a lot for an endpoint that runs arbitrary code.
     */
    private boolean isOriginAllowed(HttpExchange exchange) {
        var origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            var host = URI.create(origin).getHost();
            return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        if (!config.requiresToken()) {
            return true;
        }
        var header = exchange.getRequestHeaders().getFirst("Authorization");
        var prefix = "Bearer ";
        if (header == null || !header.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        var presented = header.substring(prefix.length()).trim();
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), config.token.getBytes(StandardCharsets.UTF_8));
    }

    private void respondJson(HttpExchange exchange, int status, JsonElement body) throws IOException {
        respond(exchange, status, "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void respondText(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, "text/plain; charset=utf-8",
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
