package com.sighs.autojs.neoforge.tools;

import com.sighs.autojs.mcp.McpTool;
import com.sighs.autojs.mcp.ToolResult;
import com.sighs.autojs.neoforge.KubeJSConsole;
import com.sighs.autojs.neoforge.KubeJSSideBackend;
import com.sighs.autojs.side.EvalBackends;
import com.sighs.autojs.side.EvalSide;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.latvian.mods.kubejs.script.ScriptManager;

import java.util.Locale;

/** Reads the KubeJS console of a side: the errors and warnings that KubeJS never raises to a caller. */
public final class KubeJSLogTool implements McpTool {
    private static final long READ_TIMEOUT_MS = 5000L;
    private static final int DEFAULT_LIMIT = 20;

    @Override
    public String name() {
        return "kubejs_log";
    }

    @Override
    public String description() {
        return """
                Read the KubeJS console of a side: script load errors and warnings, plus whatever a snippet logged.

                KubeJS clears the log on every reload, so calling this right after 'reload_scripts' returns exactly what \
                that load reported. Many KubeJS failures are only ever written here and never raised, so this is the tool \
                that turns a silent no-op into a visible error.

                'kind' picks what to return: 'errors' and 'warnings' filter by level, 'all' returns every line, which is \
                how you see what a script logged itself with console.info(...).

                One Rhino parse error emits one message followed by dozens of JVM stack frames; those runs are folded into \
                their first frame plus a count, and 'errorCount' counts messages rather than raw lines.
                """;
    }

    @Override
    public JsonObject inputSchema() {
        var side = stringProperty("Which side's console to read. 'auto' prefers the server when it is usable.");
        side.add("enum", enumOf("auto", "server", "client"));
        side.addProperty("default", "auto");

        var kind = stringProperty("'errors', 'warnings' or 'all'.");
        kind.add("enum", enumOf("errors", "warnings", "all"));
        kind.addProperty("default", "errors");

        var limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 0);
        limit.addProperty("description", "How many of the newest entries to return. 0 returns everything.");
        limit.addProperty("default", DEFAULT_LIMIT);

        var properties = new JsonObject();
        properties.add("side", side);
        properties.add("kind", kind);
        properties.add("limit", limit);

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
        if (!(backend instanceof KubeJSSideBackend kubejs)) {
            return ToolResult.error("Side '" + side.id() + "' has no KubeJS scripts in this game instance"
                    + (backend == null ? "" : " (" + backend.describe() + ")")
                    + ". Use the 'status' tool to see which sides are live.");
        }

        var manager = kubejs.manager();
        if (manager == null) {
            return ToolResult.error("Side '" + side.id() + "' has no KubeJS script manager yet (" + kubejs.describe() + ").");
        }

        var kind = stringArg(arguments, "kind");
        var wanted = kind == null || kind.isBlank() ? "errors" : kind.toLowerCase(Locale.ROOT);
        var selection = switch (wanted) {
            case "errors" -> KubeJSConsole.Selection.ERRORS;
            case "warnings" -> KubeJSConsole.Selection.WARNINGS;
            case "all" -> KubeJSConsole.Selection.ALL;
            default -> null;
        };
        if (selection == null) {
            return ToolResult.error("Unknown kind '" + kind + "'; expected errors, warnings or all.");
        }

        var limit = intArg(arguments, "limit", DEFAULT_LIMIT);
        var json = kubejs.submit(() -> describe(side, manager, selection, limit).toString(), READ_TIMEOUT_MS, null);
        if (json == null) {
            return ToolResult.error("Timed out reading the " + side.id() + " console; the game thread is busy.");
        }
        return ToolResult.ok(json);
    }

    private static JsonObject describe(EvalSide side, ScriptManager manager, KubeJSConsole.Selection selection, int limit) {
        var readout = KubeJSConsole.read(manager, selection, limit);
        var array = new JsonArray();
        readout.entries().forEach(array::add);

        var json = new JsonObject();
        json.addProperty("side", side.id());
        json.addProperty("totalLines", readout.totalLines());
        json.addProperty("errorCount", readout.errorCount());
        json.addProperty("warningCount", readout.warningCount());
        json.addProperty("returned", readout.entries().size());
        json.add("entries", array);
        json.addProperty("logFile", KubeJSConsole.relativeLogFile(manager));
        return json;
    }

    private static JsonObject stringProperty(String description) {
        var property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        return property;
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

    private static int intArg(JsonObject arguments, String name, int fallback) {
        return arguments.has(name) && arguments.get(name).isJsonPrimitive() ? arguments.get(name).getAsInt() : fallback;
    }
}
