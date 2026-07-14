package dev.kmcp.server.jvm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.kmcp.discovery.McpDiscovery
import dev.kmcp.discovery.McpWellKnownDocument
import dev.kmcp.discovery.McpWellKnownServer
import dev.kmcp.server.McpServer
import dev.kmcp.types.McpProtocol
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A minimal Streamable HTTP host for an [McpServer], built on the JDK's
 * `com.sun.net.httpserver` with zero extra dependencies.
 *
 * Routes:
 * - `POST <path>` — the MCP endpoint; requests are answered with
 *   `application/json`, notifications with `202 Accepted`.
 * - `GET /.well-known/mcp` — the discovery document.
 *
 * Security defaults: binds `127.0.0.1`, and when a browser sends an
 * `Origin` header the request is rejected unless the origin host is in
 * [allowedOriginHosts] (DNS-rebinding protection recommended by the spec).
 */
public class KmcpHttpServer(
    private val server: McpServer,
    private val host: String = "127.0.0.1",
    private val port: Int = 0,
    private val path: String = "/mcp",
    private val allowedOriginHosts: Set<String> = setOf("localhost", "127.0.0.1", "[::1]"),
) {
    private var httpServer: HttpServer? = null
    private var executor: ExecutorService? = null

    /** The port the server is bound to; valid after [start]. */
    public val boundPort: Int
        get() = httpServer?.address?.port
            ?: throw IllegalStateException("Server has not been started")

    /** The full MCP endpoint URL; valid after [start]. */
    public val endpointUrl: String
        get() = "http://$host:$boundPort$path"

    /** Starts the HTTP listener and returns this instance. */
    public fun start(): KmcpHttpServer {
        require(httpServer == null) { "Server is already running" }
        val listener = HttpServer.create(InetSocketAddress(host, port), 0)
        val pool = Executors.newFixedThreadPool(4)
        listener.executor = pool
        listener.createContext(path) { exchange -> handleMcp(exchange) }
        listener.createContext(McpDiscovery.WELL_KNOWN_PATH) { exchange -> handleWellKnown(exchange) }
        listener.start()
        executor = pool
        httpServer = listener
        return this
    }

    /**
     * Stops the HTTP listener and shuts down its worker thread pool.
     *
     * `HttpServer.stop` never shuts down a caller-supplied executor, so the
     * pool created by [start] is shut down here explicitly; otherwise its
     * non-daemon threads would keep an embedding JVM alive after stop().
     */
    public fun stop() {
        httpServer?.stop(0)
        httpServer = null
        executor?.shutdown()
        executor = null
    }

    /** Builds the discovery document advertising this endpoint. */
    public fun wellKnownDocument(): McpWellKnownDocument = McpWellKnownDocument(
        servers = listOf(
            McpWellKnownServer(
                name = server.serverInfo.name,
                endpoint = path,
                transport = "streamable-http",
                description = server.serverInfo.title,
                protocolVersions = McpProtocol.SUPPORTED_VERSIONS,
            ),
        ),
    )

    private fun handleMcp(exchange: HttpExchange) {
        exchange.use {
            // com.sun.net.httpserver contexts match by path prefix; only the
            // exact endpoint path is part of this server's contract.
            if (exchange.requestURI.path != path) {
                respond(exchange, 404, "text/plain", "Not found")
                return
            }
            if (!originAllowed(exchange)) {
                respond(exchange, 403, "text/plain", "Forbidden origin")
                return
            }
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, "text/plain", "Only POST is supported on this endpoint")
                return
            }
            val versionHeader = exchange.requestHeaders.getFirst(McpProtocol.VERSION_HEADER)
            if (versionHeader != null && versionHeader !in McpProtocol.SUPPORTED_VERSIONS) {
                respond(exchange, 400, "text/plain", "Unsupported MCP protocol version: $versionHeader")
                return
            }
            val body = exchange.requestBody.readBytes().decodeToString()
            val reply = runBlocking { server.handleRaw(body) }
            if (reply == null) {
                exchange.sendResponseHeaders(202, -1)
            } else {
                respond(exchange, 200, "application/json", reply)
            }
        }
    }

    private fun handleWellKnown(exchange: HttpExchange) {
        exchange.use {
            // Reject prefix matches such as /.well-known/mcp-other (see above).
            if (exchange.requestURI.path != McpDiscovery.WELL_KNOWN_PATH) {
                respond(exchange, 404, "text/plain", "Not found")
                return
            }
            if (exchange.requestMethod != "GET") {
                respond(exchange, 405, "text/plain", "Only GET is supported on this endpoint")
                return
            }
            respond(exchange, 200, "application/json", McpDiscovery.encode(wellKnownDocument()))
        }
    }

    private fun originAllowed(exchange: HttpExchange): Boolean {
        val origin = exchange.requestHeaders.getFirst("Origin") ?: return true
        val originHost = runCatching { URI(origin).host }.getOrNull() ?: return false
        return originHost in allowedOriginHosts
    }

    private fun respond(exchange: HttpExchange, status: Int, contentType: String, body: String) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

private inline fun HttpExchange.use(block: () -> Unit) {
    try {
        block()
    } finally {
        close()
    }
}
