package dev.kmcp.client

import kotlinx.serialization.json.JsonElement

/** Thrown when the server answers a request with a JSON-RPC error. */
public class McpErrorException(
    /** JSON-RPC / MCP error code, see [dev.kmcp.protocol.ErrorCodes]. */
    public val code: Int,
    message: String,
    /** Optional structured error details from the server. */
    public val data: JsonElement? = null,
) : Exception("MCP error $code: $message")

/** Thrown when a client method is called in the wrong session state. */
public class McpSessionStateException(message: String) : IllegalStateException(message)

/** Thrown when protocol version negotiation fails during `initialize`. */
public class McpVersionMismatchException(
    /** The version the server proposed. */
    public val serverVersion: String,
    /** The versions this client supports. */
    public val clientVersions: List<String>,
) : Exception(
    "Server proposed protocol version $serverVersion; " +
        "client supports ${clientVersions.joinToString(", ")}",
)
