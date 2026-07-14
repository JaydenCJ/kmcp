package dev.kmcp.transport

import dev.kmcp.protocol.JsonRpc
import dev.kmcp.protocol.JsonRpcErrorResponse
import dev.kmcp.protocol.JsonRpcMessage
import dev.kmcp.protocol.JsonRpcNotification
import dev.kmcp.protocol.JsonRpcParseException
import dev.kmcp.protocol.JsonRpcRequest
import dev.kmcp.protocol.JsonRpcResponse
import dev.kmcp.types.McpProtocol

/**
 * Client transport for the MCP Streamable HTTP transport in stateless mode.
 *
 * Every JSON-RPC message is POSTed to [endpointUrl] as a self-contained
 * exchange. The server may answer with a single `application/json` message
 * or with a buffered `text/event-stream` whose events carry JSON-RPC
 * messages; both are handled transparently.
 *
 * The actual HTTP I/O is delegated to a pluggable [HttpTransportEngine]
 * (JVM: `JdkHttpTransportEngine`; Android/iOS: bring your own engine).
 */
public class StreamableHttpClientTransport(
    private val engine: HttpTransportEngine,
    private val endpointUrl: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : ClientTransport {

    private var negotiatedVersion: String? = null

    override suspend fun request(request: JsonRpcRequest): JsonRpcMessage {
        val response = post(JsonRpc.encodeToString(request))
        if (response.status !in 200..299) {
            throw McpTransportException(
                "MCP endpoint answered HTTP ${response.status}",
                statusCode = response.status,
            )
        }
        val messages = decodeMessages(response)
        val match = messages.firstOrNull { message ->
            when (message) {
                is JsonRpcResponse -> message.id == request.id
                is JsonRpcErrorResponse -> message.id == request.id
                else -> false
            }
        }
        return match ?: throw McpTransportException(
            "No response matching request id ${request.id} in the server reply",
        )
    }

    override suspend fun notify(notification: JsonRpcNotification) {
        val response = post(JsonRpc.encodeToString(notification))
        if (response.status !in 200..299) {
            throw McpTransportException(
                "MCP endpoint answered HTTP ${response.status} to a notification",
                statusCode = response.status,
            )
        }
    }

    override fun onProtocolVersionNegotiated(version: String) {
        negotiatedVersion = version
    }

    override fun close() {
        engine.close()
    }

    private suspend fun post(body: String): HttpResponseData {
        val headers = buildMap {
            put("content-type", "application/json")
            put("accept", "application/json, text/event-stream")
            negotiatedVersion?.let { put(McpProtocol.VERSION_HEADER, it) }
            putAll(extraHeaders)
        }
        return try {
            engine.execute(HttpRequestData("POST", endpointUrl, headers, body))
        } catch (e: McpTransportException) {
            throw e
        } catch (e: Exception) {
            throw McpTransportException("HTTP request to $endpointUrl failed", cause = e)
        }
    }

    private fun decodeMessages(response: HttpResponseData): List<JsonRpcMessage> = try {
        when (response.contentType()) {
            "text/event-stream" ->
                SseParser.parse(response.body)
                    .filter { it.event == "message" }
                    .map { JsonRpc.decodeFromString(it.data) }
            else -> listOf(JsonRpc.decodeFromString(response.body))
        }
    } catch (e: JsonRpcParseException) {
        throw McpTransportException("Server returned a malformed JSON-RPC payload", cause = e)
    }
}
