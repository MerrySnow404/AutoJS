package com.sighs.autojs.mcp.tools;

import com.sighs.autojs.config.AutoJSConfig;
import com.sighs.autojs.eval.EvalResult;
import com.sighs.autojs.mcp.McpTool;
import com.sighs.autojs.mcp.ToolResult;
import com.sighs.autojs.side.EvalBackend;
import com.sighs.autojs.side.EvalBackends;
import com.sighs.autojs.side.EvalSide;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.StringJoiner;

/** Evaluates a JavaScript snippet through KubeJS, on a chosen side of the running game. */
public final class EvalJsTool implements McpTool {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoJS/Eval");
    private static final String TIMEOUT_FIELD = "timeout_ms";

    private final AutoJSConfig config;

    public EvalJsTool(AutoJSConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "eval_js";
    }

    @Override
    public String description() {
        return """
                Evaluate JavaScript inside the running game through KubeJS and return the value of the last expression.

                The snippet runs on the chosen side's main thread with the same globals as a KubeJS script file of that \
                side, so it can read and change live game state and call the Java APIs KubeJS exposes (Item, Block, Text, \
                NBT, Registry, player and level objects, ...). Wrap the value in JSON.stringify(...) when you want \
                structured data back. The class-loading helper is the one this KubeJS generation has: `Java.loadClass(...)` \
                on most of them, the older lowercase `java('...')` on the 1.18.2 line, which has no `Java` global at all.

                Limits, and how they actually fail:

                - Event handlers, recipes, tags and commands cannot be registered from here. KubeJS refuses that outside \
                its script loading phase and swallows the refusal instead of raising it, so such a snippet still reports \
                ok:true while doing nothing. The refusal is returned in 'diagnostics' whenever it happens during this \
                call, so always read that field; anything logged earlier is in the side's KubeJS console file, \
                logs/kubejs/<side>.log (the oldest KubeJS line writes .txt there instead). Expect no-ops and verify side \
                effects by reading state back.
                - Items, blocks and other registry objects cannot be registered at runtime at all.
                - Globals you define are discarded on the next /reload or resource reload, because KubeJS builds a fresh \
                context each time.
                - The client side does not exist on a dedicated server, and the server side exists on a client only while \
                a world is loaded. Ask the 'status' tool which sides are live.
                - KubeJS blocks whole packages from scripts (java.lang outside a small whitelist, java.io, java.nio, \
                java.net, sun, io.netty, org.objectweb.asm, net.neoforged.fml, cpw.mods, ...) and blocks reflection and \
                class loaders, so Runtime, ProcessBuilder and Files are out of reach. Use JsonIO or NBTIO for files, and \
                note they are confined to the game directory.
                """;
    }

    @Override
    public JsonObject inputSchema() {
        var sideValues = new JsonArray();
        sideValues.add("auto");
        sideValues.add("server");
        sideValues.add("client");

        var side = new JsonObject();
        side.addProperty("type", "string");
        side.add("enum", sideValues);
        side.addProperty("default", "auto");
        side.addProperty("description",
                "Which side evaluates the snippet. 'auto' prefers the server when it is usable, otherwise the client.");

        var code = new JsonObject();
        code.addProperty("type", "string");
        code.addProperty("description", "JavaScript source; the value of its last expression is returned.");

        var timeout = new JsonObject();
        timeout.addProperty("type", "integer");
        timeout.addProperty("minimum", 1);
        timeout.addProperty("description", "Milliseconds to wait for the game thread. Defaults to the configured value.");

        var properties = new JsonObject();
        properties.add("code", code);
        properties.add("side", side);
        properties.add(TIMEOUT_FIELD, timeout);

        var required = new JsonArray();
        required.add("code");

        var schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    @Override
    public ToolResult call(JsonObject arguments) {
        if (!arguments.has("code") || !arguments.get("code").isJsonPrimitive()) {
            return ToolResult.error("Missing required argument 'code' (a JavaScript string).");
        }
        var code = arguments.get("code").getAsString();

        EvalSide side;
        try {
            var requested = arguments.has("side") && arguments.get("side").isJsonPrimitive()
                    ? arguments.get("side").getAsString()
                    : null;
            side = EvalBackends.resolve(requested);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }

        var backend = EvalBackends.get(side);
        if (backend == null) {
            return ToolResult.error("Side '" + side.id() + "' is not registered in this game instance. Registered sides: "
                    + registeredSides() + ". Call the 'status' tool for details.");
        }
        if (!backend.isAvailable()) {
            return ToolResult.error("Side '" + side.id() + "' cannot evaluate right now (" + backend.describe()
                    + "). A dedicated server has no client side; on a client the server side exists only while a world is loaded.");
        }

        var timeout = config.evalTimeoutMs;
        if (arguments.has(TIMEOUT_FIELD) && arguments.get(TIMEOUT_FIELD).isJsonPrimitive()) {
            timeout = Math.max(1L, arguments.get(TIMEOUT_FIELD).getAsLong());
        }

        if (config.logEval) {
            LOGGER.info("[{}] {}", side.id(), code);
        }

        var result = backend.eval(code, timeout);
        return new ToolResult(!result.ok(), serialize(result).toString());
    }

    private JsonObject serialize(EvalResult result) {
        var json = new JsonObject();
        json.addProperty("ok", result.ok());
        json.addProperty("side", result.side().id());
        json.addProperty("elapsedMs", result.elapsedMs());
        if (result.ok()) {
            json.addProperty("type", result.valueType());
            json.addProperty("value", truncate(result.value()));
        } else {
            json.addProperty("error", result.error());
            var stack = new JsonArray();
            result.stack().forEach(stack::add);
            json.add("stack", stack);
        }
        if (!result.diagnostics().isEmpty()) {
            var diagnostics = new JsonArray();
            result.diagnostics().forEach(diagnostics::add);
            json.add("diagnostics", diagnostics);
        }
        return json;
    }

    private String truncate(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= config.maxResultChars) {
            return value;
        }
        return value.substring(0, config.maxResultChars) + "... (truncated, " + value.length() + " characters total)";
    }

    private String registeredSides() {
        var joiner = new StringJoiner(", ");
        for (EvalBackend backend : EvalBackends.all()) {
            joiner.add(backend.side().id());
        }
        return joiner.length() == 0 ? "none" : joiner.toString();
    }
}
