package dev.kmcp.server

import dev.kmcp.protocol.ErrorCodes
import dev.kmcp.protocol.JsonRpc
import dev.kmcp.protocol.JsonRpcErrorResponse
import dev.kmcp.protocol.JsonRpcNotification
import dev.kmcp.protocol.JsonRpcRequest
import dev.kmcp.protocol.JsonRpcResponse
import dev.kmcp.protocol.RequestId
import dev.kmcp.types.GetPromptResult
import dev.kmcp.types.InitializeResult
import dev.kmcp.types.ListToolsResult
import dev.kmcp.types.McpProtocol
import dev.kmcp.types.PromptArgument
import dev.kmcp.types.PromptMessage
import dev.kmcp.types.ReadResourceResult
import dev.kmcp.types.Role
import dev.kmcp.types.TextContent
import dev.kmcp.types.TextResourceContents
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DispatcherTest {

    private fun buildServer(): McpServer = mcpServer(name = "test", version = "0.1.0") {
        instructions("A test server.")
        tool("greet", description = "Greets a person") {
            input {
                string("name", minLength = 1)
                boolean("shout", required = false)
            }
            handle { args ->
                val greeting = "hello, " + args.string("name")
                toolText(if (args.booleanOrNull("shout") == true) greeting.uppercase() else greeting)
            }
        }
        tool("boom", description = "Always fails") {
            input { }
            handle { throw IllegalStateException("kaboom") }
        }
        resource(
            uri = "kmcp://notes",
            name = "notes",
            mimeType = "text/plain",
        ) {
            TextResourceContents(uri = "kmcp://notes", mimeType = "text/plain", text = "note body")
        }
        prompt(
            name = "summarize",
            description = "Summarize a text",
            arguments = listOf(PromptArgument(name = "text", required = true)),
        ) { args ->
            GetPromptResult(
                messages = listOf(
                    PromptMessage(Role.USER, TextContent("Summarize: " + args.getValue("text"))),
                ),
            )
        }
    }

    private suspend fun exchange(server: McpServer, request: JsonRpcRequest) =
        server.handleMessage(request)

    @Test
    fun initializeNegotiatesSupportedVersion() = runTest {
        val server = buildServer()
        val reply = exchange(
            server,
            JsonRpcRequest(
                id = RequestId.Num(1),
                method = "initialize",
                params = buildJsonObject {
                    put("protocolVersion", McpProtocol.LATEST_VERSION)
                    put("capabilities", buildJsonObject { })
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", "test-client")
                            put("version", "0.1.0")
                        },
                    )
                },
            ),
        )
        assertIs<JsonRpcResponse>(reply)
        val result = JsonRpc.json.decodeFromJsonElement(InitializeResult.serializer(), reply.result)
        assertEquals(McpProtocol.LATEST_VERSION, result.protocolVersion)
        assertEquals("test", result.serverInfo.name)
        assertNotNull(result.capabilities.tools)
        assertNotNull(result.capabilities.resources)
        assertNotNull(result.capabilities.prompts)
    }

    @Test
    fun initializeAnswersLatestForUnknownVersion() = runTest {
        val server = buildServer()
        val reply = exchange(
            server,
            JsonRpcRequest(
                id = RequestId.Num(1),
                method = "initialize",
                params = buildJsonObject {
                    put("protocolVersion", "1999-01-01")
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", "old-client")
                            put("version", "0.0.1")
                        },
                    )
                },
            ),
        )
        assertIs<JsonRpcResponse>(reply)
        val result = JsonRpc.json.decodeFromJsonElement(InitializeResult.serializer(), reply.result)
        assertEquals(McpProtocol.LATEST_VERSION, result.protocolVersion)
    }

    @Test
    fun initializedNotificationProducesNoResponse() = runTest {
        val server = buildServer()
        assertNull(server.handleMessage(JsonRpcNotification(method = "notifications/initialized")))
    }

    @Test
    fun toolsListReturnsDeclaredSchemas() = runTest {
        val server = buildServer()
        val reply = exchange(server, JsonRpcRequest(id = RequestId.Num(2), method = "tools/list"))
        assertIs<JsonRpcResponse>(reply)
        val result = JsonRpc.json.decodeFromJsonElement(ListToolsResult.serializer(), reply.result)
        assertEquals(listOf("greet", "boom"), result.tools.map { it.name })
        val greet = result.tools.first()
        assertEquals("object", greet.inputSchema.getValue("type").toString().trim('"'))
        assertTrue("name" in greet.inputSchema.getValue("properties").toString())
    }

    @Test
    fun toolCallRunsHandler() = runTest {
        val server = buildServer()
        val reply = exchange(
            server,
            JsonRpcRequest(
                id = RequestId.Num(3),
                method = "tools/call",
                params = buildJsonObject {
                    put("name", "greet")
                    put(
                        "arguments",
                        buildJsonObject {
                            put("name", "kmcp")
                            put("shout", true)
                        },
                    )
                },
            ),
        )
        assertIs<JsonRpcResponse>(reply)
        assertTrue("HELLO, KMCP" in reply.result.toString())
    }

    @Test
    fun toolCallRejectsSchemaViolations() = runTest {
        val server = buildServer()
        val reply = exchange(
            server,
            JsonRpcRequest(
                id = RequestId.Num(4),
                method = "tools/call",
                params = buildJsonObject {
                    put("name", "greet")
                    put("arguments", buildJsonObject { put("shout", true) })
                },
            ),
        )
        assertIs<JsonRpcErrorResponse>(reply)
        assertEquals(ErrorCodes.INVALID_PARAMS, reply.error.code)
        assertTrue("name" in (reply.error.data?.toString() ?: ""))
    }

    @Test
    fun toolCallOnUnknownToolIsInvalidParams() = runTest {
        val server = buildServer()
        val reply = exchange(
            server,
            JsonRpcRequest(
                id = RequestId.Num(5),
                method = "tools/call",
                params = buildJsonObject { put("name", "does-not-exist") },
            ),
        )
        assertIs<JsonRpcErrorResponse>(reply)
        assertEquals(ErrorCodes.INVALID_PARAMS, reply.error.code)
    }

    @Test
    fun failingToolIsReportedInBandNotAsProtocolError() = runTest {
        val server = buildServer()
        val reply = exchange(
            server,
            JsonRpcRequest(
                id = RequestId.Num(6),
                method = "tools/call",
                params = buildJsonObject {
                    put("name", "boom")
                    put("arguments", buildJsonObject { })
                },
            ),
        )
        assertIs<JsonRpcResponse>(reply)
        assertTrue("\"isError\":true" in JsonRpc.json.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(),
            reply.result,
        ))
    }

    @Test
    fun unknownMethodIsMethodNotFound() = runTest {
        val server = buildServer()
        val reply = exchange(server, JsonRpcRequest(id = RequestId.Num(7), method = "bogus/method"))
        assertIs<JsonRpcErrorResponse>(reply)
        assertEquals(ErrorCodes.METHOD_NOT_FOUND, reply.error.code)
    }

    @Test
    fun parseErrorYieldsMinus32700WithNullId() = runTest {
        val server = buildServer()
        val raw = server.handleRaw("this is not json")
        assertNotNull(raw)
        val reply = JsonRpc.decodeFromString(raw)
        assertIs<JsonRpcErrorResponse>(reply)
        assertEquals(ErrorCodes.PARSE_ERROR, reply.error.code)
        assertEquals(RequestId.Null, reply.id)
    }

    @Test
    fun resourceReadReturnsContents() = runTest {
        val server = buildServer()
        val reply = exchange(
            server,
            JsonRpcRequest(
                id = RequestId.Num(8),
                method = "resources/read",
                params = buildJsonObject { put("uri", "kmcp://notes") },
            ),
        )
        assertIs<JsonRpcResponse>(reply)
        val result = JsonRpc.json.decodeFromJsonElement(ReadResourceResult.serializer(), reply.result)
        val contents = result.contents.single()
        assertIs<TextResourceContents>(contents)
        assertEquals("note body", contents.text)
    }

    @Test
    fun unknownResourceUsesResourceNotFoundCode() = runTest {
        val server = buildServer()
        val reply = exchange(
            server,
            JsonRpcRequest(
                id = RequestId.Num(9),
                method = "resources/read",
                params = buildJsonObject { put("uri", "kmcp://missing") },
            ),
        )
        assertIs<JsonRpcErrorResponse>(reply)
        assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, reply.error.code)
    }

    @Test
    fun promptGetRendersMessages() = runTest {
        val server = buildServer()
        val reply = exchange(
            server,
            JsonRpcRequest(
                id = RequestId.Num(10),
                method = "prompts/get",
                params = buildJsonObject {
                    put("name", "summarize")
                    put("arguments", buildJsonObject { put("text", "kotlin rocks") })
                },
            ),
        )
        assertIs<JsonRpcResponse>(reply)
        assertTrue("Summarize: kotlin rocks" in reply.result.toString())
    }

    @Test
    fun promptGetReportsMissingRequiredArguments() = runTest {
        val server = buildServer()
        val reply = exchange(
            server,
            JsonRpcRequest(
                id = RequestId.Num(11),
                method = "prompts/get",
                params = buildJsonObject { put("name", "summarize") },
            ),
        )
        assertIs<JsonRpcErrorResponse>(reply)
        assertEquals(ErrorCodes.INVALID_PARAMS, reply.error.code)
        assertTrue("text" in reply.error.message)
    }

    @Test
    fun pingAnswersEmptyObject() = runTest {
        val server = buildServer()
        val reply = exchange(server, JsonRpcRequest(id = RequestId.Num(12), method = "ping"))
        assertIs<JsonRpcResponse>(reply)
        assertEquals("{}", reply.result.toString())
    }
}
