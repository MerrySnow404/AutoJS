package com.sighs.autojs.neoforge.tools;

import com.sighs.autojs.mcp.McpTool;
import com.sighs.autojs.mcp.ToolResult;
import com.sighs.autojs.neoforge.backend.ReloadableSide;
import com.sighs.autojs.side.EvalBackends;
import com.sighs.autojs.side.EvalSide;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Re-runs one side's scripts, so edited script files take effect without restarting the game. */
public final class ReloadScriptsTool implements McpTool {
    /** A reload parses and runs every script of that side, so it needs a much longer budget than an eval. */
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;
    private static final long MIN_TIMEOUT_MS = 100L;

    @Override
    public String name() {
        return "reload_scripts";
    }

    @Override
    public String description() {
        return """
                Re-run one side's scripts, so edited script files take effect without restarting the game. With KubeJS \
                installed that is the same operation as the game's /kubejs reload <side>-scripts command; without it, the \
                side's Rhino scope is rebuilt.

                Use it after editing script files: reload, then call 'kubejs_log' (KubeJS mode only), because a reload \
                empties the KubeJS console, so what it contains right afterwards is exactly what that load reported.

                A reload rebuilds the side's script scope, so globals defined by earlier 'eval_js' calls are gone either \
                way. On the server side, recipes, tags and loot tables belong to the datapack: this tool does not re-run \
                /reload, so a change to those needs a datapack reload as well.
                """;
    }

    @Override
    public JsonObject inputSchema() {
        var side = new JsonObject();
        side.addProperty("type", "string");
        side.add("enum", enumOf("auto", "server", "client"));
        side.addProperty("default", "auto");
        side.addProperty("description", "Which side's scripts to reload. 'auto' prefers the server when it is usable.");

        var timeout = new JsonObject();
        timeout.addProperty("type", "integer");
        timeout.addProperty("minimum", MIN_TIMEOUT_MS);
        timeout.addProperty("description", "Milliseconds to wait for the reload. Defaults to " + DEFAULT_TIMEOUT_MS + ".");
        timeout.addProperty("default", DEFAULT_TIMEOUT_MS);

        var properties = new JsonObject();
        properties.add("side", side);
        properties.add("timeout_ms", timeout);

        var schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    @Override
    public ToolResult call(JsonObject arguments) {
        EvalSide side;
        try {
            side = EvalBackends.resolve(stringArg(arguments, "side"));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }

        var backend = EvalBackends.get(side);
        if (!(backend instanceof ReloadableSide reloadable)) {
            return ToolResult.error("Side '" + side.id() + "' has nothing to reload in this game instance"
                    + (backend == null ? "" : " (" + backend.describe() + ")")
                    + ". Use the 'status' tool to see which sides are live.");
        }

        var timeout = arguments.has("timeout_ms") && arguments.get("timeout_ms").isJsonPrimitive()
                ? Math.max(MIN_TIMEOUT_MS, arguments.get("timeout_ms").getAsLong())
                : DEFAULT_TIMEOUT_MS;

        var outcome = reloadable.reload(timeout);

        var json = new JsonObject();
        json.addProperty("ok", outcome.ok());
        json.addProperty("side", side.id());
        json.addProperty("elapsedMs", outcome.elapsedMs());
        json.addProperty("message", outcome.message());
        json.addProperty("nextStep", outcome.ok()
                ? "Globals defined by earlier 'eval_js' calls are gone; re-declare what you still need. With KubeJS "
                        + "installed, 'kubejs_log' shows what the reloaded scripts reported."
                : "The scripts may be unchanged or the game thread may be busy; with KubeJS installed check "
                        + "'kubejs_log', and check the game console either way.");
        return new ToolResult(!outcome.ok(), json.toString());
    }

    private static JsonArray enumOf(String... values) {
        var array = new JsonArray();
        for (var value : values) {
            array.add(value);
        }
        return array;
    }

    private static String stringArg(JsonObject arguments, String name) {
        return arguments.has(name) && arguments.get(name).isJsonPrimitive() ? arguments.get(name).getAsString() : null;
    }
}
