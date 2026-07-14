package dev.kmcp.sample

import dev.kmcp.server.jvm.KmcpHttpServer
import dev.kmcp.server.mcpServer
import dev.kmcp.server.toolText

/**
 * A runnable echo MCP server sample: `./gradlew runEchoServer`.
 *
 * Binds 127.0.0.1:8931 and exposes one `echo` tool plus an `about` resource,
 * so any MCP client (Claude Code, an agent framework, or plain curl) can run
 * the initialize -> tools/list -> tools/call round trip against it.
 */
public fun main() {
    val server = mcpServer(name = "kmcp-echo", version = "0.1.0", title = "kmcp echo sample") {
        instructions("Call the echo tool with a text argument to get it back.")
        tool("echo", description = "Echo text back") {
            input { string("text", description = "Text to echo") }
            handle { args -> toolText("echo: " + args.string("text")) }
        }
        resource(
            uri = "kmcp://about",
            name = "about",
            description = "Describes this sample server",
            mimeType = "text/plain",
        ) {
            dev.kmcp.types.TextResourceContents(
                uri = "kmcp://about",
                mimeType = "text/plain",
                text = "kmcp echo sample server, built with dev.kmcp:kmcp",
            )
        }
    }

    val http = KmcpHttpServer(server, host = "127.0.0.1", port = 8931).start()
    println("kmcp echo server listening on ${http.endpointUrl}")
    println("discovery document at http://127.0.0.1:${http.boundPort}/.well-known/mcp")
    println("press Ctrl+C to stop")
    Thread.currentThread().join()
}
