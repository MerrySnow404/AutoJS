package cc.sighs.autojs;

/**
 * Constants shared by every AutoJS target.
 *
 * <p>AutoJS turns the KubeJS script contexts into a local MCP (Model Context Protocol) endpoint, so
 * that an AI agent can drive and debug the running game instead of guessing from static sources.
 */
public final class AutoJS {
    public static final String MOD_ID = "autojs";
    public static final String MOD_NAME = "AutoJS";

    /** Path of the MCP endpoint on the local listener, next to {@code /health} and {@code /}. */
    public static final String MCP_PATH = "/mcp";

    private AutoJS() {
    }
}
