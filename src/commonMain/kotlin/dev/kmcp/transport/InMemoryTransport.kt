package dev.kmcp.transport

import dev.kmcp.protocol.JsonRpcMessage
import dev.kmcp.protocol.JsonRpcNotification
import dev.kmcp.protocol.JsonRpcRequest
import dev.kmcp.server.McpServer

/**
 * A [ClientTransport] that dispatches directly to an in-process [McpServer].
 *
 * Useful for unit tests and for embedding a client and server in the same
 * application without any network hop. Messages still go through the full
 * encode/decode cycle so serialization stays on the tested path.
 */
public class InMemoryTransport(
    private val server: McpServer,
) : ClientTransport {
    override suspend fun request(request: JsonRpcRequest): JsonRpcMessage {
        val body = dev.kmcp.protocol.JsonRpc.encodeToString(request)
        val reply = server.handleRaw(body)
            ?: throw McpTransportException("Server produced no response for request ${request.id}")
        return dev.kmcp.protocol.JsonRpc.decodeFromString(reply)
    }

    override suspend fun notify(notification: JsonRpcNotification) {
        server.handleRaw(dev.kmcp.protocol.JsonRpc.encodeToString(notification))
    }
}
