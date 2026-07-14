package dev.kmcp.samples.android

import dev.kmcp.discovery.McpDiscovery
import dev.kmcp.discovery.McpWellKnownDocument
import dev.kmcp.discovery.McpWellKnownServer
import dev.kmcp.server.McpServer
import dev.kmcp.types.McpProtocol
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 host for an [McpServer] on Android, where the JDK's
 * `com.sun.net.httpserver` is unavailable. Binds 127.0.0.1 only.
 *
 * Supports exactly what the stateless MCP exchange needs: `POST /mcp` with a
 * JSON body, and `GET /.well-known/mcp` for discovery. One thread per
 * connection is plenty for a single on-device agent client.
 */
class LoopbackHttpServer(
    private val server: McpServer,
    private val port: Int = 8931,
    private val path: String = "/mcp",
) {
    @Volatile
    private var socket: ServerSocket? = null

    /** Starts the accept loop on a background thread. */
    fun start() {
        check(socket == null) { "Server is already running" }
        val listener = ServerSocket(port, 8, InetAddress.getLoopbackAddress())
        socket = listener
        thread(name = "kmcp-loopback-http", isDaemon = true) {
            while (!listener.isClosed) {
                val connection = try {
                    listener.accept()
                } catch (e: Exception) {
                    break
                }
                thread(isDaemon = true) { handle(connection) }
            }
        }
    }

    /** Stops accepting connections. */
    fun stop() {
        socket?.close()
        socket = null
    }

    private fun handle(connection: Socket) {
        connection.use { sock ->
            // A stuck or malicious peer must not pin the handler thread.
            sock.soTimeout = READ_TIMEOUT_MILLIS
            val input = BufferedInputStream(sock.getInputStream())
            val requestLine = readHeaderLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val (method, target) = parts
            var contentLength = 0
            while (true) {
                val line = readHeaderLine(input) ?: return
                if (line.isEmpty()) break
                val (name, value) = line.split(":", limit = 2).let {
                    (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "")
                }
                if (name.equals("content-length", ignoreCase = true)) {
                    contentLength = value.trim().toIntOrNull() ?: 0
                }
            }
            when {
                method == "POST" && target == path -> {
                    // Content-Length counts bytes, not characters. Read the
                    // raw bytes and decode as UTF-8 afterwards; reading
                    // contentLength *characters* would block forever on
                    // multi-byte bodies (e.g. Japanese text), whose character
                    // count is always smaller than their byte count.
                    val buffer = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buffer, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    val body = String(buffer, 0, read, Charsets.UTF_8)
                    val reply = runBlocking { server.handleRaw(body) }
                    if (reply == null) {
                        writeResponse(sock, 202, "Accepted", null)
                    } else {
                        writeResponse(sock, 200, "OK", reply)
                    }
                }
                method == "GET" && target == McpDiscovery.WELL_KNOWN_PATH -> {
                    val document = McpWellKnownDocument(
                        servers = listOf(
                            McpWellKnownServer(
                                name = server.serverInfo.name,
                                endpoint = path,
                                protocolVersions = McpProtocol.SUPPORTED_VERSIONS,
                            ),
                        ),
                    )
                    writeResponse(sock, 200, "OK", McpDiscovery.encode(document))
                }
                else -> writeResponse(sock, 404, "Not Found", null)
            }
        }
    }

    /**
     * Reads one header line terminated by CRLF (or bare LF). HTTP header
     * bytes are decoded as ISO-8859-1 per RFC 9112; the JSON body is read
     * separately as raw bytes. Returns null when the stream ends before any
     * byte of a line was read.
     */
    private fun readHeaderLine(input: InputStream): String? {
        val bytes = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.isEmpty()) null else bytes.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.append(b.toChar())
        }
        return bytes.toString()
    }

    private fun writeResponse(sock: Socket, status: Int, reason: String, body: String?) {
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val out = sock.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private companion object {
        /** Per-socket read timeout so a stalled client cannot hang a handler thread. */
        const val READ_TIMEOUT_MILLIS: Int = 10_000
    }
}
