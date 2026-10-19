package com.sighs.autojs.mcp;

/** Result of one tool invocation, mapped to MCP's {@code content}/{@code isError} shape. */
public record ToolResult(boolean isError, String text) {
    public static ToolResult ok(String text) {
        return new ToolResult(false, text);
    }

    public static ToolResult error(String text) {
        return new ToolResult(true, text);
    }
}
