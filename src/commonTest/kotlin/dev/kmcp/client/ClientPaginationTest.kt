package dev.kmcp.client

import dev.kmcp.protocol.JsonRpc
import dev.kmcp.protocol.JsonRpcMessage
import dev.kmcp.protocol.JsonRpcNotification
import dev.kmcp.protocol.JsonRpcRequest
import dev.kmcp.protocol.JsonRpcResponse
import dev.kmcp.protocol.paramsObject
import dev.kmcp.transport.ClientTransport
import dev.kmcp.types.Implementation
import dev.kmcp.types.InitializeResult
import dev.kmcp.types.ListPromptsResult
import dev.kmcp.types.ListResourcesResult
import dev.kmcp.types.ListToolsResult
import dev.kmcp.types.McpProtocol
import dev.kmcp.types.Prompt
import dev.kmcp.types.Resource
import dev.kmcp.types.ServerCapabilities
import dev.kmcp.types.Tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies that the client can consume paginated list results from servers
 * that split tools/resources/prompts across pages (as the official SDK
 * servers may): the cursor from `nextCursor` must be sent back on the wire
 * as `params.cursor`, and the first page request must carry no cursor.
 */
class ClientPaginationTest {

    /**
     * A fake server transport exposing two pages per list method. Page one is
     * returned for requests without a cursor; page two requires the exact
     * cursor value advertised by page one. Requests still travel through the
     * real encode/decode cycle so the wire shape is what gets asserted.
     */
    private class PagingServerTransport : ClientTransport {
        val seenCursors = mutableListOf<String?>()

        override suspend fun request(request: JsonRpcRequest): JsonRpcMessage {
            // Round-trip through the wire encoding like a real transport.
            val decoded = JsonRpc.decodeFromString(JsonRpc.encodeToString(request)) as JsonRpcRequest
            val result = when (decoded.method) {
                "initialize" -> JsonRpc.json.encodeToJsonElement(
                    InitializeResult.serializer(),
                    InitializeResult(
                        protocolVersion = McpProtocol.LATEST_VERSION,
                        capabilities = ServerCapabilities(),
                        serverInfo = Implementation(name = "paging", version = "0.0.1"),
                    ),
                )
                "tools/list" -> {
                    val cursor = cursorOf(decoded)
                    JsonRpc.json.encodeToJsonElement(
                        ListToolsResult.serializer(),
                        if (cursor == null) {
                            ListToolsResult(
                                tools = listOf(tool("alpha")),
                                nextCursor = "tools-page-2",
                            )
                        } else {
                            check(cursor == "tools-page-2") { "unexpected cursor: $cursor" }
                            ListToolsResult(tools = listOf(tool("beta")))
                        },
                    )
                }
                "resources/list" -> {
                    val cursor = cursorOf(decoded)
                    JsonRpc.json.encodeToJsonElement(
                        ListResourcesResult.serializer(),
                        if (cursor == null) {
                            ListResourcesResult(
                                resources = listOf(Resource(uri = "kmcp://a", name = "a")),
                                nextCursor = "res-page-2",
                            )
                        } else {
                            check(cursor == "res-page-2") { "unexpected cursor: $cursor" }
                            ListResourcesResult(resources = listOf(Resource(uri = "kmcp://b", name = "b")))
                        },
                    )
                }
                "prompts/list" -> {
                    val cursor = cursorOf(decoded)
                    JsonRpc.json.encodeToJsonElement(
                        ListPromptsResult.serializer(),
                        if (cursor == null) {
                            ListPromptsResult(
                                prompts = listOf(Prompt(name = "p1")),
                                nextCursor = "prompt-page-2",
                            )
                        } else {
                            check(cursor == "prompt-page-2") { "unexpected cursor: $cursor" }
                            ListPromptsResult(prompts = listOf(Prompt(name = "p2")))
                        },
                    )
                }
                else -> error("unexpected method: ${decoded.method}")
            }
            return JsonRpcResponse(id = decoded.id, result = result)
        }

        override suspend fun notify(notification: JsonRpcNotification) {}

        private fun cursorOf(request: JsonRpcRequest): String? {
            val cursor = (request.paramsObject()["cursor"] as? JsonPrimitive)?.content
            seenCursors += cursor
            return cursor
        }

        private fun tool(name: String) = Tool(
            name = name,
            inputSchema = buildJsonObject { },
        )
    }

    private suspend fun readyClient(transport: ClientTransport): McpClient {
        val client = McpClient(
            transport = transport,
            clientInfo = Implementation(name = "paging-test", version = "0.1.0"),
        )
        client.initialize()
        return client
    }

    @Test
    fun listToolsFollowsNextCursorAcrossPages() = runTest {
        val transport = PagingServerTransport()
        val client = readyClient(transport)
        val page1 = client.listTools()
        assertEquals(listOf("alpha"), page1.tools.map { it.name })
        assertEquals("tools-page-2", page1.nextCursor)
        val page2 = client.listTools(cursor = page1.nextCursor)
        assertEquals(listOf("beta"), page2.tools.map { it.name })
        assertNull(page2.nextCursor)
        assertEquals(listOf(null, "tools-page-2"), transport.seenCursors)
    }

    @Test
    fun listResourcesFollowsNextCursorAcrossPages() = runTest {
        val transport = PagingServerTransport()
        val client = readyClient(transport)
        val page1 = client.listResources()
        assertEquals("res-page-2", page1.nextCursor)
        val page2 = client.listResources(cursor = page1.nextCursor)
        assertEquals(listOf("kmcp://b"), page2.resources.map { it.uri })
        assertEquals(listOf(null, "res-page-2"), transport.seenCursors)
    }

    @Test
    fun listPromptsFollowsNextCursorAcrossPages() = runTest {
        val transport = PagingServerTransport()
        val client = readyClient(transport)
        val page1 = client.listPrompts()
        assertEquals("prompt-page-2", page1.nextCursor)
        val page2 = client.listPrompts(cursor = page1.nextCursor)
        assertEquals(listOf("p2"), page2.prompts.map { it.name })
        assertEquals(listOf(null, "prompt-page-2"), transport.seenCursors)
    }
}
