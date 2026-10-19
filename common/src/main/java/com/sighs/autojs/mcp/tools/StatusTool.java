package com.sighs.autojs.mcp.tools;

import com.sighs.autojs.AutoJS;
import com.sighs.autojs.config.AutoJSConfig;
import com.sighs.autojs.mcp.McpTool;
import com.sighs.autojs.mcp.ToolResult;
import com.sighs.autojs.side.EvalBackend;
import com.sighs.autojs.side.EvalBackends;
import com.sighs.autojs.side.EvalSide;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.function.Supplier;

/** Reports which sides are live, so an agent can tell a dedicated server from a client with a world. */
public final class StatusTool implements McpTool {
    private final AutoJSConfig config;
    private final Supplier<JsonObject> serverInfo;

    public StatusTool(AutoJSConfig config, Supplier<JsonObject> serverInfo) {
        this.config = config;
        this.serverInfo = serverInfo;
    }

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return """
                Report the state of the AutoJS endpoint: which sides exist in this game instance, whether each one can \
                evaluate right now, and the meaning of the whitelisted sides. Call this before eval_js when you are unsure \
                whether the client side or the server side is available.
                """;
    }

    @Override
    public JsonObject inputSchema() {
        var schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    @Override
    public ToolResult call(JsonObject arguments) {
        var sides = new JsonArray();
        for (var side : EvalSide.values()) {
            var backend = EvalBackends.get(side);
            var json = new JsonObject();
            json.addProperty("side", side.id());
            json.addProperty("registered", backend != null);
            json.addProperty("available", backend != null && backend.isAvailable());
            json.addProperty("state", backend == null ? "this game instance has no such side" : backend.describe());
            sides.add(json);
        }

        var limits = new JsonObject();
        limits.addProperty("evalTimeoutMs", config.evalTimeoutMs);
        limits.addProperty("maxResultChars", config.maxResultChars);
        limits.addProperty("tokenRequired", config.requiresToken());
        limits.addProperty("logEval", config.logEval);

        var endpoints = new JsonObject();
        endpoints.addProperty("bindAddress", config.bindAddress);
        endpoints.addProperty("port", config.port);
        endpoints.addProperty("mcpPath", AutoJS.MCP_PATH);
        endpoints.addProperty("url", "http://" + config.bindAddress + ":" + config.port + AutoJS.MCP_PATH);

        var json = new JsonObject();
        json.add("serverInfo", serverInfo.get());
        json.add("endpoints", endpoints);
        json.add("sides", sides);
        json.add("limits", limits);
        json.addProperty("note",
                "eval_js runs a snippet on the chosen side's main thread through KubeJS. Registering event handlers, "
                        + "recipes, tags or commands from a snippet silently does nothing (KubeJS writes the refusal to "
                        + "logs/kubejs/<side>.log instead of raising), registry objects cannot be registered at runtime, and "
                        + "globals defined by a snippet are discarded on reload.");
        return ToolResult.ok(json.toString());
    }
}
