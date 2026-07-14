package dev.kmcp.transport

import dev.kmcp.protocol.JsonRpcRequest
import dev.kmcp.protocol.JsonRpcResponse
import dev.kmcp.protocol.RequestId
import dev.kmcp.types.McpProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A scriptable fake engine for driving the transport without a network. */
private class FakeEngine(
    private val respond: (HttpRequestData) -> HttpResponseData,
) : HttpTransportEngine {
    val requests = mutableListOf<HttpRequestData>()

    override suspend fun execute(request: HttpRequestData): HttpResponseData {
        requests += request
        return respond(request)
    }
}

class StreamableHttpTransportTest {

    private val pingRequest = JsonRpcRequest(id = RequestId.Num(1), method = "ping")

    @Test
    fun decodesPlainJsonResponse() = runTest {
        val engine = FakeEngine {
            HttpResponseData(
                status = 200,
                headers = mapOf("content-type" to "application/json"),
                body = """{"jsonrpc":"2.0","id":1,"result":{}}""",
            )
        }
        val transport = StreamableHttpClientTransport(engine, "http://127.0.0.1:9/mcp")
        val reply = transport.request(pingRequest)
        assertIs<JsonRpcResponse>(reply)
        assertEquals(RequestId.Num(1), reply.id)
    }

    @Test
    fun decodesSseResponseAndPicksMatchingId() = runTest {
        val sse = buildString {
            append("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}\n\n")
            append("data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}\n\n")
        }
        val engine = FakeEngine {
            HttpResponseData(
                status = 200,
                headers = mapOf("content-type" to "text/event-stream; charset=utf-8"),
                body = sse,
            )
        }
        val transport = StreamableHttpClientTransport(engine, "http://127.0.0.1:9/mcp")
        val reply = transport.request(pingRequest)
        assertIs<JsonRpcResponse>(reply)
        assertTrue("ok" in reply.result.toString())
    }

    @Test
    fun sendsProtocolVersionHeaderAfterNegotiation() = runTest {
        val engine = FakeEngine {
            HttpResponseData(
                status = 200,
                headers = mapOf("content-type" to "application/json"),
                body = """{"jsonrpc":"2.0","id":1,"result":{}}""",
            )
        }
        val transport = StreamableHttpClientTransport(engine, "http://127.0.0.1:9/mcp")
        transport.request(pingRequest)
        transport.onProtocolVersionNegotiated(McpProtocol.LATEST_VERSION)
        transport.request(pingRequest)
        assertEquals(null, engine.requests[0].headers[McpProtocol.VERSION_HEADER])
        assertEquals(
            McpProtocol.LATEST_VERSION,
            engine.requests[1].headers[McpProtocol.VERSION_HEADER],
        )
        assertEquals("application/json", engine.requests[0].headers["content-type"])
        assertTrue("text/event-stream" in engine.requests[0].headers.getValue("accept"))
    }

    @Test
    fun non2xxStatusBecomesTransportException() = runTest {
        val engine = FakeEngine {
            HttpResponseData(status = 500, headers = emptyMap(), body = "boom")
        }
        val transport = StreamableHttpClientTransport(engine, "http://127.0.0.1:9/mcp")
        val e = assertFailsWith<McpTransportException> { transport.request(pingRequest) }
        assertEquals(500, e.statusCode)
    }

    @Test
    fun unmatchedResponseIdBecomesTransportException() = runTest {
        val engine = FakeEngine {
            HttpResponseData(
                status = 200,
                headers = mapOf("content-type" to "application/json"),
                body = """{"jsonrpc":"2.0","id":999,"result":{}}""",
            )
        }
        val transport = StreamableHttpClientTransport(engine, "http://127.0.0.1:9/mcp")
        assertFailsWith<McpTransportException> { transport.request(pingRequest) }
    }
}
