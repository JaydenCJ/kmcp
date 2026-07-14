package dev.kmcp.transport

import dev.kmcp.protocol.JsonRpcMessage
import dev.kmcp.protocol.JsonRpcNotification
import dev.kmcp.protocol.JsonRpcRequest

/**
 * A client-side MCP transport: delivers a request and returns the matching
 * response, or fires a notification without waiting for a reply.
 *
 * Implementations are stateless per call, matching the stateless Streamable
 * HTTP transport model: every [request] is a self-contained exchange.
 */
public interface ClientTransport {
    /**
     * Sends [request] and returns the server's response message
     * ([dev.kmcp.protocol.JsonRpcResponse] or
     * [dev.kmcp.protocol.JsonRpcErrorResponse]).
     *
     * @throws McpTransportException when the exchange fails at the transport
     * level (network error, unexpected status code, unmatched response id).
     */
    public suspend fun request(request: JsonRpcRequest): JsonRpcMessage

    /** Sends [notification] without expecting a response. */
    public suspend fun notify(notification: JsonRpcNotification)

    /**
     * Informs the transport of the protocol version negotiated during
     * `initialize`, so HTTP transports can attach the
     * `MCP-Protocol-Version` header to subsequent requests.
     */
    public fun onProtocolVersionNegotiated(version: String) {}

    /** Releases transport resources. Default implementation does nothing. */
    public fun close() {}
}

/** Thrown when a transport-level exchange fails. */
public class McpTransportException(
    message: String,
    /** HTTP status code when the failure came from an HTTP response. */
    public val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
