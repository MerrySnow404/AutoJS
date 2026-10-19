package com.sighs.autojs.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ordered tool registry. Registration order is the order advertised to clients. */
public final class McpToolRegistry {
    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public void register(McpTool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalStateException("Duplicate MCP tool: " + tool.name());
        }
    }

    public McpTool get(String name) {
        return name == null ? null : tools.get(name);
    }

    public Collection<McpTool> all() {
        return tools.values();
    }

    public JsonArray describe() {
        var array = new JsonArray();
        for (var tool : tools.values()) {
            var json = new JsonObject();
            json.addProperty("name", tool.name());
            json.addProperty("description", tool.description());
            json.add("inputSchema", tool.inputSchema());
            array.add(json);
        }
        return array;
    }
}
