package dev.kmcp.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WellKnownTest {

    @Test
    fun parsesDiscoveryDocument() {
        val text = """
            {
              "version": "1",
              "servers": [
                {
                  "name": "kmcp-echo",
                  "endpoint": "/mcp",
                  "transport": "streamable-http",
                  "description": "echo sample",
                  "protocolVersions": ["2025-06-18", "2025-03-26"]
                }
              ]
            }
        """.trimIndent()
        val doc = McpDiscovery.parse(text)
        assertEquals("1", doc.version)
        val server = doc.servers.single()
        assertEquals("kmcp-echo", server.name)
        assertEquals("/mcp", server.endpoint)
        assertEquals("streamable-http", server.transport)
        assertEquals(listOf("2025-06-18", "2025-03-26"), server.protocolVersions)
    }

    @Test
    fun parseAppliesDefaults() {
        val doc = McpDiscovery.parse("""{"servers":[{"name":"s","endpoint":"/mcp"}]}""")
        assertEquals("1", doc.version)
        assertEquals("streamable-http", doc.servers.single().transport)
    }

    @Test
    fun encodeParseRoundTrip() {
        val doc = McpWellKnownDocument(
            servers = listOf(
                McpWellKnownServer(name = "a", endpoint = "https://example.com/mcp"),
            ),
        )
        val encoded = McpDiscovery.encode(doc)
        // Defaults are written explicitly for third-party consumers.
        assertTrue("\"version\":\"1\"" in encoded)
        assertTrue("\"transport\":\"streamable-http\"" in encoded)
        assertEquals(doc, McpDiscovery.parse(encoded))
    }

    @Test
    fun wellKnownUrlJoinsBaseUrl() {
        assertEquals(
            "https://example.com/.well-known/mcp",
            McpDiscovery.wellKnownUrl("https://example.com/"),
        )
        assertEquals(
            "http://127.0.0.1:8931/.well-known/mcp",
            McpDiscovery.wellKnownUrl("http://127.0.0.1:8931"),
        )
    }
}
