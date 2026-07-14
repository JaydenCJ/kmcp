package dev.kmcp.client

import dev.kmcp.protocol.JsonRpc
import dev.kmcp.protocol.JsonRpcMessage
import dev.kmcp.protocol.JsonRpcNotification
import dev.kmcp.protocol.JsonRpcRequest
import dev.kmcp.protocol.JsonRpcResponse
import dev.kmcp.server.mcpServer
import dev.kmcp.server.toolText
import dev.kmcp.transport.ClientTransport
import dev.kmcp.transport.InMemoryTransport
import dev.kmcp.types.Implementation
import dev.kmcp.types.InitializeResult
import dev.kmcp.types.McpProtocol
import dev.kmcp.types.ServerCapabilities
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HandshakeTest {

    private fun echoServer() = mcpServer(name = "echo", version = "0.1.0") {
        tool("echo", description = "Echo text back") {
            input { string("text", description = "Text to echo") }
            handle { args -> toolText("echo: " + args.string("text")) }
        }
    }

    private fun client(transport: ClientTransport) = McpClient(
        transport = transport,
        clientInfo = Implementation(name = "test-client", version = "0.1.0"),
    )

    @Test
    fun fullHandshakeMovesSessionToReady() = runTest {
        val client = client(InMemoryTransport(echoServer()))
        assertEquals(SessionState.IDLE, client.state)
        val result = client.initialize()
        assertEquals(SessionState.READY, client.state)
        assertEquals(McpProtocol.LATEST_VERSION, client.negotiatedProtocolVersion)
        assertEquals("echo", result.serverInfo.name)
        assertEquals("echo", client.serverInfo?.name)
    }

    @Test
    fun requestsBeforeInitializeAreRejected() = runTest {
        val client = client(InMemoryTransport(echoServer()))
        assertFailsWith<McpSessionStateException> { client.listTools() }
    }

    @Test
    fun initializeTwiceIsRejected() = runTest {
        val client = client(InMemoryTransport(echoServer()))
        client.initialize()
        assertFailsWith<McpSessionStateException> { client.initialize() }
    }

    @Test
    fun requestsAfterCloseAreRejected() = runTest {
        val client = client(InMemoryTransport(echoServer()))
        client.initialize()
        client.close()
        assertEquals(SessionState.CLOSED, client.state)
        assertFailsWith<McpSessionStateException> { client.ping() }
    }

    @Test
    fun serverErrorsSurfaceAsMcpErrorException() = runTest {
        val client = client(InMemoryTransport(echoServer()))
        client.initialize()
        val e = assertFailsWith<McpErrorException> {
            client.callTool("echo", buildJsonObject { put("wrong", 1) })
        }
        assertEquals(dev.kmcp.protocol.ErrorCodes.INVALID_PARAMS, e.code)
    }

    @Test
    fun versionMismatchFailsTheSession() = runTest {
        // A transport that impersonates a server answering with an unknown
        // protocol version, to drive the client-side negotiation check.
        val badServerTransport = object : ClientTransport {
            override suspend fun request(request: JsonRpcRequest): JsonRpcMessage {
                val result = InitializeResult(
                    protocolVersion = "1999-01-01",
                    capabilities = ServerCapabilities(),
                    serverInfo = Implementation(name = "old", version = "0.0.1"),
                )
                return JsonRpcResponse(
                    id = request.id,
                    result = JsonRpc.json.encodeToJsonElement(
                        InitializeResult.serializer(),
                        result,
                    ),
                )
            }

            override suspend fun notify(notification: JsonRpcNotification) {}
        }
        val client = client(badServerTransport)
        val e = assertFailsWith<McpVersionMismatchException> { client.initialize() }
        assertEquals("1999-01-01", e.serverVersion)
        assertEquals(SessionState.FAILED, client.state)
        assertFailsWith<McpSessionStateException> { client.listTools() }
    }

    @Test
    fun initializeWireFormatAlwaysCarriesCapabilities() = runTest {
        // Regression test: the shared Json configuration uses
        // encodeDefaults = false, which used to drop the (spec-required)
        // capabilities member from the serialized initialize params. Strict
        // servers such as the official TypeScript SDK reject that payload,
        // so assert on the actual wire bytes, not on the decoded object.
        val server = echoServer()
        var initializeWire: String? = null
        val capturingTransport = object : ClientTransport {
            override suspend fun request(request: JsonRpcRequest): JsonRpcMessage {
                val body = JsonRpc.encodeToString(request)
                if (request.method == "initialize") initializeWire = body
                val reply = server.handleRaw(body)
                    ?: throw IllegalStateException("no reply for ${request.method}")
                return JsonRpc.decodeFromString(reply)
            }

            override suspend fun notify(notification: JsonRpcNotification) {
                server.handleRaw(JsonRpc.encodeToString(notification))
            }
        }
        client(capturingTransport).initialize()
        val wire = initializeWire ?: error("initialize request was never sent")
        val params = JsonRpc.json.parseToJsonElement(wire)
            .jsonObject.getValue("params").jsonObject
        assertTrue("capabilities" in params, "initialize params must carry capabilities, wire was: $wire")
        assertTrue("protocolVersion" in params, "wire was: $wire")
        assertTrue("clientInfo" in params, "wire was: $wire")
    }

    @Test
    fun clientRoundTripAgainstDispatcher() = runTest {
        val client = client(InMemoryTransport(echoServer()))
        client.initialize()
        val tools = client.listTools()
        assertEquals(listOf("echo"), tools.tools.map { it.name })
        val result = client.callTool("echo", buildJsonObject { put("text", "hi") })
        assertEquals("echo: hi", result.text())
        assertTrue(result.isError != true)
        client.ping()
    }
}
