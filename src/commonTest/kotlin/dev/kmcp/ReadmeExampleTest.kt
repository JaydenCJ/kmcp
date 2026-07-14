package dev.kmcp

import dev.kmcp.client.McpClient
import dev.kmcp.server.mcpServer
import dev.kmcp.server.toolText
import dev.kmcp.transport.InMemoryTransport
import dev.kmcp.types.Implementation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the minimal example from README.md verbatim, so the documentation
 * is guaranteed to compile and behave as printed.
 */
class ReadmeExampleTest {

    @Test
    fun readmeMinimalExampleWorksAsPrinted() = runTest {
        val server = mcpServer(name = "echo", version = "0.1.0") {
            tool("echo", description = "Echo text back") {
                input { string("text", description = "Text to echo") }
                handle { args -> toolText("echo: " + args.string("text")) }
            }
        }
        val client = McpClient(InMemoryTransport(server), Implementation("demo", "0.1.0"))
        client.initialize()
        val result = client.callTool("echo", buildJsonObject { put("text", "hi") })
        println(result.text()) // echo: hi

        assertEquals("echo: hi", result.text())
    }
}
