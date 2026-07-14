package dev.kmcp.integration

import dev.kmcp.client.McpClient
import dev.kmcp.client.McpErrorException
import dev.kmcp.discovery.McpDiscovery
import dev.kmcp.protocol.ErrorCodes
import dev.kmcp.server.jvm.KmcpHttpServer
import dev.kmcp.server.mcpServer
import dev.kmcp.server.toolText
import dev.kmcp.transport.StreamableHttpClientTransport
import dev.kmcp.transport.jdk.JdkHttpTransportEngine
import dev.kmcp.types.Implementation
import dev.kmcp.types.McpProtocol
import dev.kmcp.types.TextResourceContents
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end protocol round trip over real HTTP on 127.0.0.1:
 * initialize -> notifications/initialized -> tools/list -> tools/call,
 * plus discovery document fetch and error paths. This is the smoke-level
 * proof that the streamable HTTP transport and dispatcher interoperate.
 */
class HttpTransportIntegrationTest {

    private lateinit var http: KmcpHttpServer

    @BeforeTest
    fun startServer() {
        val server = mcpServer(name = "kmcp-echo", version = "0.1.0", title = "echo test server") {
            tool("echo", description = "Echo text back") {
                input { string("text", description = "Text to echo") }
                handle { args -> toolText("echo: " + args.string("text")) }
            }
            resource(uri = "kmcp://about", name = "about", mimeType = "text/plain") {
                TextResourceContents(uri = "kmcp://about", mimeType = "text/plain", text = "about kmcp")
            }
        }
        http = KmcpHttpServer(server, host = "127.0.0.1", port = 0).start()
    }

    @AfterTest
    fun stopServer() {
        http.stop()
    }

    private fun newClient(): McpClient = McpClient(
        transport = StreamableHttpClientTransport(JdkHttpTransportEngine(), http.endpointUrl),
        clientInfo = Implementation(name = "kmcp-it", version = "0.1.0"),
    )

    @Test
    fun fullProtocolRoundTripOverHttp() = runBlocking {
        val client = newClient()

        val init = client.initialize()
        assertEquals(McpProtocol.LATEST_VERSION, init.protocolVersion)
        assertEquals("kmcp-echo", init.serverInfo.name)

        val tools = client.listTools()
        assertEquals(listOf("echo"), tools.tools.map { it.name })
        assertTrue("text" in tools.tools.single().inputSchema.toString())

        val result = client.callTool("echo", buildJsonObject { put("text", "over http") })
        assertEquals("echo: over http", result.text())

        val read = client.readResource("kmcp://about")
        val contents = read.contents.single()
        assertTrue(contents is TextResourceContents && contents.text == "about kmcp")

        client.ping()
        client.close()
    }

    @Test
    fun invalidArgumentsAreRejectedWithInvalidParams() = runBlocking {
        val client = newClient()
        client.initialize()
        val e = assertFailsWith<McpErrorException> {
            client.callTool("echo", buildJsonObject { put("text", 12345) })
        }
        assertEquals(ErrorCodes.INVALID_PARAMS, e.code)
        client.close()
    }

    @Test
    fun wellKnownDiscoveryDocumentIsServed() {
        val url = McpDiscovery.wellKnownUrl("http://127.0.0.1:" + http.boundPort)
        val response = rawGet(url)
        assertEquals(200, response.statusCode())
        val doc = McpDiscovery.parse(response.body())
        val advertised = doc.servers.single()
        assertEquals("kmcp-echo", advertised.name)
        assertEquals("/mcp", advertised.endpoint)
        assertEquals(McpProtocol.SUPPORTED_VERSIONS, advertised.protocolVersions)
    }

    @Test
    fun notificationsAreAcceptedWith202() {
        val response = rawPost(
            http.endpointUrl,
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
        )
        assertEquals(202, response.statusCode())
    }

    @Test
    fun getOnMcpEndpointIsRejected() {
        val response = rawGet(http.endpointUrl)
        assertEquals(405, response.statusCode())
    }

    @Test
    fun pathsExtendingTheMcpEndpointAre404() {
        // The JDK HttpServer dispatches contexts by longest path prefix;
        // the host must still 404 anything that is not the exact endpoint.
        val response = rawPost(
            http.endpointUrl + "extra",
            """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
        )
        assertEquals(404, response.statusCode())
    }

    @Test
    fun pathsExtendingTheWellKnownDocumentAre404() {
        val response = rawGet("http://127.0.0.1:" + http.boundPort + "/.well-known/mcp-other")
        assertEquals(404, response.statusCode())
    }

    @Test
    fun unsupportedProtocolVersionHeaderIsRejected() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(http.endpointUrl))
            .header("content-type", "application/json")
            .header(McpProtocol.VERSION_HEADER, "1999-01-01")
            .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
            .build()
        val response = HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(400, response.statusCode())
    }

    @Test
    fun disallowedOriginIsRejected() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(http.endpointUrl))
            .header("content-type", "application/json")
            .header("Origin", "https://evil.example")
            .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
            .build()
        val response = HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(403, response.statusCode())
    }

    private fun rawGet(url: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun rawPost(url: String, body: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
}
