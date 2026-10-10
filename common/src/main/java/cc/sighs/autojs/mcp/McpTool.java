package cc.sighs.autojs.mcp;

import com.google.gson.JsonObject;

/** One MCP tool. Arguments arrive as the already-parsed {@code arguments} object. */
public interface McpTool {
    String name();

    String description();

    /** JSON Schema of the arguments. */
    JsonObject inputSchema();

    ToolResult call(JsonObject arguments) throws Exception;
}
