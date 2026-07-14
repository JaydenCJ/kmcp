package dev.kmcp.client

import dev.kmcp.protocol.JsonRpcErrorResponse
import dev.kmcp.protocol.JsonRpcNotification
import dev.kmcp.protocol.JsonRpcRequest
import dev.kmcp.protocol.JsonRpcResponse
import dev.kmcp.protocol.RequestId
import dev.kmcp.transport.ClientTransport
import dev.kmcp.transport.McpTransportException
import dev.kmcp.types.CallToolResult
import dev.kmcp.types.ClientCapabilities
import dev.kmcp.types.GetPromptParams
import dev.kmcp.types.GetPromptResult
import dev.kmcp.types.Implementation
import dev.kmcp.types.InitializeParams
import dev.kmcp.types.InitializeResult
import dev.kmcp.types.ListPromptsResult
import dev.kmcp.types.ListResourcesResult
import dev.kmcp.types.ListToolsResult
import dev.kmcp.types.McpProtocol
import dev.kmcp.types.ReadResourceParams
import dev.kmcp.types.ReadResourceResult
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Lifecycle states of an [McpClient] session. */
public enum class SessionState {
    /** Created, `initialize` has not run yet. */
    IDLE,

    /** `initialize` is in flight. */
    INITIALIZING,

    /** Handshake finished; requests may be issued. */
    READY,

    /** Handshake failed (for example, version negotiation). */
    FAILED,

    /** [McpClient.close] was called. */
    CLOSED,
}

/**
 * An MCP client session over any [ClientTransport].
 *
 * Call [initialize] once before any other request; the client enforces the
 * handshake state machine and performs protocol version negotiation. All
 * request methods throw [McpErrorException] when the server answers with a
 * JSON-RPC error and [McpTransportException] on transport failures.
 */
public class McpClient(
    private val transport: ClientTransport,
    private val clientInfo: Implementation,
    private val capabilities: ClientCapabilities = ClientCapabilities(),
    private val requestedVersion: String = McpProtocol.LATEST_VERSION,
) {
    /** The current lifecycle state of this session. */
    public var state: SessionState = SessionState.IDLE
        private set

    /** The protocol version agreed with the server, set by [initialize]. */
    public var negotiatedProtocolVersion: String? = null
        private set

    /** The server identity reported during [initialize]. */
    public var serverInfo: Implementation? = null
        private set

    private var nextId: Long = 1

    /**
     * Performs the `initialize` / `notifications/initialized` handshake.
     *
     * @throws McpSessionStateException when called twice or after [close].
     * @throws McpVersionMismatchException when the server proposes a
     * protocol version this client does not support.
     */
    public suspend fun initialize(): InitializeResult {
        if (state != SessionState.IDLE) {
            throw McpSessionStateException(
                "initialize() may only be called once, in state IDLE (was $state)",
            )
        }
        state = SessionState.INITIALIZING
        try {
            val params = InitializeParams(
                protocolVersion = requestedVersion,
                capabilities = capabilities,
                clientInfo = clientInfo,
            )
            val result = call(
                method = "initialize",
                params = dev.kmcp.protocol.JsonRpc.json
                    .encodeToJsonElement(InitializeParams.serializer(), params),
                deserializer = InitializeResult.serializer(),
            )
            if (result.protocolVersion !in McpProtocol.SUPPORTED_VERSIONS) {
                throw McpVersionMismatchException(
                    serverVersion = result.protocolVersion,
                    clientVersions = McpProtocol.SUPPORTED_VERSIONS,
                )
            }
            transport.onProtocolVersionNegotiated(result.protocolVersion)
            transport.notify(JsonRpcNotification(method = "notifications/initialized"))
            serverInfo = result.serverInfo
            negotiatedProtocolVersion = result.protocolVersion
            state = SessionState.READY
            return result
        } catch (e: Exception) {
            state = SessionState.FAILED
            throw e
        }
    }

    /** Sends `ping` and returns when the server answers. */
    public suspend fun ping() {
        requireReady()
        call("ping", null, JsonObject.serializer())
    }

    /**
     * Lists the tools the server exposes.
     *
     * Servers may paginate: when the result carries a non-null
     * [ListToolsResult.nextCursor], pass it as [cursor] to fetch the next
     * page. Omit [cursor] for the first page.
     */
    public suspend fun listTools(cursor: String? = null): ListToolsResult {
        requireReady()
        return call("tools/list", paginationParams(cursor), ListToolsResult.serializer())
    }

    /** Calls tool [name] with [arguments]. */
    public suspend fun callTool(
        name: String,
        arguments: JsonObject = JsonObject(emptyMap()),
    ): CallToolResult {
        requireReady()
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        return call("tools/call", params, CallToolResult.serializer())
    }

    /**
     * Lists the resources the server exposes.
     *
     * Pass the previous result's [ListResourcesResult.nextCursor] as
     * [cursor] to fetch the next page; omit it for the first page.
     */
    public suspend fun listResources(cursor: String? = null): ListResourcesResult {
        requireReady()
        return call("resources/list", paginationParams(cursor), ListResourcesResult.serializer())
    }

    /** Reads the resource at [uri]. */
    public suspend fun readResource(uri: String): ReadResourceResult {
        requireReady()
        val params = dev.kmcp.protocol.JsonRpc.json
            .encodeToJsonElement(ReadResourceParams.serializer(), ReadResourceParams(uri))
        return call("resources/read", params, ReadResourceResult.serializer())
    }

    /**
     * Lists the prompts the server exposes.
     *
     * Pass the previous result's [ListPromptsResult.nextCursor] as [cursor]
     * to fetch the next page; omit it for the first page.
     */
    public suspend fun listPrompts(cursor: String? = null): ListPromptsResult {
        requireReady()
        return call("prompts/list", paginationParams(cursor), ListPromptsResult.serializer())
    }

    /** Renders prompt [name] with [arguments]. */
    public suspend fun getPrompt(
        name: String,
        arguments: Map<String, String> = emptyMap(),
    ): GetPromptResult {
        requireReady()
        val params = dev.kmcp.protocol.JsonRpc.json
            .encodeToJsonElement(GetPromptParams.serializer(), GetPromptParams(name, arguments))
        return call("prompts/get", params, GetPromptResult.serializer())
    }

    /** Closes the session and the underlying transport. */
    public fun close() {
        state = SessionState.CLOSED
        transport.close()
    }

    /** Builds `{"cursor": ...}` params for list requests, or null for page one. */
    private fun paginationParams(cursor: String?): JsonElement? =
        cursor?.let { c -> buildJsonObject { put("cursor", c) } }

    private fun requireReady() {
        if (state != SessionState.READY) {
            throw McpSessionStateException(
                "Session is $state; call initialize() before issuing requests",
            )
        }
    }

    private suspend fun <T> call(
        method: String,
        params: JsonElement?,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val request = JsonRpcRequest(
            id = RequestId.Num(nextId++),
            method = method,
            params = params,
        )
        return when (val reply = transport.request(request)) {
            is JsonRpcResponse ->
                dev.kmcp.protocol.JsonRpc.json.decodeFromJsonElement(deserializer, reply.result)
            is JsonRpcErrorResponse ->
                throw McpErrorException(reply.error.code, reply.error.message, reply.error.data)
            else -> throw McpTransportException("Transport returned a non-response message")
        }
    }
}
