package dev.kmcp.server

import dev.kmcp.protocol.ErrorCodes
import dev.kmcp.protocol.JsonRpc
import dev.kmcp.protocol.JsonRpcErrorObject
import dev.kmcp.protocol.JsonRpcErrorResponse
import dev.kmcp.protocol.JsonRpcMessage
import dev.kmcp.protocol.JsonRpcNotification
import dev.kmcp.protocol.JsonRpcParseException
import dev.kmcp.protocol.JsonRpcRequest
import dev.kmcp.protocol.JsonRpcResponse
import dev.kmcp.protocol.paramsObject
import dev.kmcp.schema.SchemaValidator
import dev.kmcp.types.CallToolParams
import dev.kmcp.types.CallToolResult
import dev.kmcp.types.ClientCapabilities
import dev.kmcp.types.GetPromptParams
import dev.kmcp.types.Implementation
import dev.kmcp.types.InitializeParams
import dev.kmcp.types.InitializeResult
import dev.kmcp.types.ListPromptsResult
import dev.kmcp.types.ListResourcesResult
import dev.kmcp.types.ListToolsResult
import dev.kmcp.types.McpProtocol
import dev.kmcp.types.PromptsCapability
import dev.kmcp.types.ReadResourceParams
import dev.kmcp.types.ReadResourceResult
import dev.kmcp.types.ResourcesCapability
import dev.kmcp.types.ServerCapabilities
import dev.kmcp.types.TextContent
import dev.kmcp.types.ToolsCapability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An MCP server: a registry of tools, resources, and prompts plus a
 * transport-agnostic JSON-RPC dispatcher.
 *
 * The dispatcher is stateless: every request is handled independently, so a
 * single instance can serve any number of concurrent stateless Streamable
 * HTTP clients. Build instances with the [mcpServer] DSL.
 */
public class McpServer internal constructor(
    /** Server name and version reported during `initialize`. */
    public val serverInfo: Implementation,
    private val instructions: String?,
    private val tools: Map<String, RegisteredTool>,
    private val resources: Map<String, RegisteredResource>,
    private val prompts: Map<String, RegisteredPrompt>,
) {
    /** Capabilities advertised in the `initialize` result. */
    public val capabilities: ServerCapabilities = ServerCapabilities(
        tools = if (tools.isNotEmpty()) ToolsCapability(listChanged = false) else null,
        resources = if (resources.isNotEmpty()) ResourcesCapability(listChanged = false) else null,
        prompts = if (prompts.isNotEmpty()) PromptsCapability(listChanged = false) else null,
    )

    /**
     * Handles one raw JSON-RPC payload and returns the serialized response,
     * or null when [body] was a notification (HTTP servers answer 202).
     * Never throws: malformed payloads become JSON-RPC error responses.
     */
    public suspend fun handleRaw(body: String): String? {
        val message = try {
            JsonRpc.decodeFromString(body)
        } catch (e: JsonRpcParseException) {
            return JsonRpc.encodeToString(
                JsonRpcErrorResponse(
                    error = JsonRpcErrorObject(ErrorCodes.PARSE_ERROR, e.message ?: "Parse error"),
                ),
            )
        }
        return handleMessage(message)?.let { JsonRpc.encodeToString(it) }
    }

    /**
     * Handles one decoded JSON-RPC message. Returns the response message, or
     * null for notifications. Responses and error responses sent by clients
     * are ignored (this dispatcher issues no server-to-client requests).
     */
    public suspend fun handleMessage(message: JsonRpcMessage): JsonRpcMessage? = when (message) {
        is JsonRpcRequest -> handleRequest(message)
        is JsonRpcNotification -> null
        else -> null
    }

    private suspend fun handleRequest(request: JsonRpcRequest): JsonRpcMessage = try {
        when (request.method) {
            "initialize" -> handleInitialize(request)
            "ping" -> success(request, JsonObject(emptyMap()))
            "tools/list" -> success(
                request,
                JsonRpc.json.encodeToJsonElement(
                    ListToolsResult.serializer(),
                    ListToolsResult(tools.values.map { it.descriptor }),
                ),
            )
            "tools/call" -> handleToolCall(request)
            "resources/list" -> success(
                request,
                JsonRpc.json.encodeToJsonElement(
                    ListResourcesResult.serializer(),
                    ListResourcesResult(resources.values.map { it.descriptor }),
                ),
            )
            "resources/read" -> handleResourceRead(request)
            "prompts/list" -> success(
                request,
                JsonRpc.json.encodeToJsonElement(
                    ListPromptsResult.serializer(),
                    ListPromptsResult(prompts.values.map { it.descriptor }),
                ),
            )
            "prompts/get" -> handlePromptGet(request)
            else -> error(
                request,
                ErrorCodes.METHOD_NOT_FOUND,
                "Method is not supported: ${request.method}",
            )
        }
    } catch (e: JsonRpcParseException) {
        error(request, ErrorCodes.INVALID_PARAMS, e.message ?: "Invalid params")
    } catch (e: Exception) {
        error(request, ErrorCodes.INTERNAL_ERROR, e.message ?: "Internal error")
    }

    private fun handleInitialize(request: JsonRpcRequest): JsonRpcMessage {
        val params = try {
            JsonRpc.json.decodeFromJsonElement(InitializeParams.serializer(), request.paramsObject())
        } catch (e: Exception) {
            return error(request, ErrorCodes.INVALID_PARAMS, "Malformed initialize params")
        }
        val result = InitializeResult(
            protocolVersion = McpProtocol.negotiate(params.protocolVersion),
            capabilities = capabilities,
            serverInfo = serverInfo,
            instructions = instructions,
        )
        return success(
            request,
            JsonRpc.json.encodeToJsonElement(InitializeResult.serializer(), result),
        )
    }

    private suspend fun handleToolCall(request: JsonRpcRequest): JsonRpcMessage {
        val params = try {
            JsonRpc.json.decodeFromJsonElement(CallToolParams.serializer(), request.paramsObject())
        } catch (e: Exception) {
            return error(request, ErrorCodes.INVALID_PARAMS, "Malformed tools/call params")
        }
        val tool = tools[params.name]
            ?: return error(request, ErrorCodes.INVALID_PARAMS, "Unknown tool: ${params.name}")
        val arguments = params.arguments ?: JsonObject(emptyMap())
        val violations = SchemaValidator.validate(tool.descriptor.inputSchema, arguments)
        if (violations.isNotEmpty()) {
            return error(
                request,
                ErrorCodes.INVALID_PARAMS,
                "Tool arguments failed schema validation",
                data = JsonArray(violations.map { JsonPrimitive(it.toString()) }),
            )
        }
        val result = try {
            tool.handler(ToolArguments(arguments))
        } catch (e: Exception) {
            // Tool execution failures are reported in-band per the MCP spec.
            CallToolResult(
                content = listOf(TextContent("Tool ${params.name} failed: ${e.message}")),
                isError = true,
            )
        }
        return success(
            request,
            JsonRpc.json.encodeToJsonElement(CallToolResult.serializer(), result),
        )
    }

    private suspend fun handleResourceRead(request: JsonRpcRequest): JsonRpcMessage {
        val params = try {
            JsonRpc.json.decodeFromJsonElement(ReadResourceParams.serializer(), request.paramsObject())
        } catch (e: Exception) {
            return error(request, ErrorCodes.INVALID_PARAMS, "Malformed resources/read params")
        }
        val resource = resources[params.uri]
            ?: return error(
                request,
                ErrorCodes.RESOURCE_NOT_FOUND,
                "Resource is not available: ${params.uri}",
                data = buildJsonObject { put("uri", params.uri) },
            )
        val contents = resource.reader()
        return success(
            request,
            JsonRpc.json.encodeToJsonElement(
                ReadResourceResult.serializer(),
                ReadResourceResult(listOf(contents)),
            ),
        )
    }

    private suspend fun handlePromptGet(request: JsonRpcRequest): JsonRpcMessage {
        val params = try {
            JsonRpc.json.decodeFromJsonElement(GetPromptParams.serializer(), request.paramsObject())
        } catch (e: Exception) {
            return error(request, ErrorCodes.INVALID_PARAMS, "Malformed prompts/get params")
        }
        val prompt = prompts[params.name]
            ?: return error(request, ErrorCodes.INVALID_PARAMS, "Unknown prompt: ${params.name}")
        val arguments = params.arguments ?: emptyMap()
        val missing = prompt.descriptor.arguments
            .filter { it.required == true && it.name !in arguments }
            .map { it.name }
        if (missing.isNotEmpty()) {
            return error(
                request,
                ErrorCodes.INVALID_PARAMS,
                "Missing required prompt arguments: ${missing.joinToString(", ")}",
            )
        }
        val result = prompt.renderer(arguments)
        return success(
            request,
            JsonRpc.json.encodeToJsonElement(
                dev.kmcp.types.GetPromptResult.serializer(),
                result,
            ),
        )
    }

    private fun success(request: JsonRpcRequest, result: JsonElement): JsonRpcResponse =
        JsonRpcResponse(id = request.id, result = result)

    private fun error(
        request: JsonRpcRequest,
        code: Int,
        message: String,
        data: JsonElement? = null,
    ): JsonRpcErrorResponse =
        JsonRpcErrorResponse(
            id = request.id,
            error = JsonRpcErrorObject(code, message, data),
        )
}
