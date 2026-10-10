package cc.sighs.autojs.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Minimal JSON-RPC 2.0 envelope construction. */
public final class JsonRpc {
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private JsonRpc() {
    }

    public static JsonObject result(JsonElement id, JsonElement result) {
        var json = new JsonObject();
        json.addProperty("jsonrpc", "2.0");
        json.add("id", id == null ? JsonNull.INSTANCE : id);
        json.add("result", result);
        return json;
    }

    public static JsonObject error(JsonElement id, int code, String message) {
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        var json = new JsonObject();
        json.addProperty("jsonrpc", "2.0");
        json.add("id", id == null ? JsonNull.INSTANCE : id);
        json.add("error", error);
        return json;
    }
}
