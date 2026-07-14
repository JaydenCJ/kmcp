package dev.kmcp.types

import dev.kmcp.protocol.JsonRpc
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ContentSerializationTest {

    @Test
    fun textContentUsesTypeDiscriminator() {
        val wire = JsonRpc.json.encodeToString(
            ContentBlock.serializer(),
            TextContent("hello"),
        )
        val obj = JsonRpc.json.parseToJsonElement(wire).jsonObject
        assertEquals("text", obj.getValue("type").jsonPrimitive.content)
        assertEquals("hello", obj.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun contentBlockRoundTripsPolymorphically() {
        val blocks: List<ContentBlock> = listOf(
            TextContent("hi"),
            ImageContent(data = "aGk=", mimeType = "image/png"),
            AudioContent(data = "aGk=", mimeType = "audio/wav"),
            EmbeddedResource(TextResourceContents(uri = "kmcp://about", text = "about")),
        )
        for (block in blocks) {
            val wire = JsonRpc.json.encodeToString(ContentBlock.serializer(), block)
            val decoded = JsonRpc.json.decodeFromString(ContentBlock.serializer(), wire)
            assertEquals(block, decoded)
        }
    }

    @Test
    fun resourceContentsAreDistinguishedByBlobPresence() {
        val text = """{"uri":"file:///a.txt","mimeType":"text/plain","text":"abc"}"""
        val blob = """{"uri":"file:///a.bin","mimeType":"application/octet-stream","blob":"aGk="}"""
        assertIs<TextResourceContents>(
            JsonRpc.json.decodeFromString(ResourceContents.serializer(), text),
        )
        assertIs<BlobResourceContents>(
            JsonRpc.json.decodeFromString(ResourceContents.serializer(), blob),
        )
    }

    @Test
    fun initializeResultRoundTrip() {
        val result = InitializeResult(
            protocolVersion = McpProtocol.LATEST_VERSION,
            capabilities = ServerCapabilities(tools = ToolsCapability(listChanged = false)),
            serverInfo = Implementation(name = "kmcp-echo", version = "0.1.0"),
            instructions = "Use the echo tool.",
        )
        val wire = JsonRpc.json.encodeToString(InitializeResult.serializer(), result)
        assertEquals(result, JsonRpc.json.decodeFromString(InitializeResult.serializer(), wire))
    }

    @Test
    fun callToolResultTextHelperJoinsTextBlocks() {
        val result = CallToolResult(
            content = listOf(
                TextContent("line one"),
                ImageContent(data = "aGk=", mimeType = "image/png"),
                TextContent("line two"),
            ),
        )
        assertEquals("line one\nline two", result.text())
    }

    @Test
    fun versionNegotiationPicksRequestedWhenSupported() {
        assertEquals("2025-03-26", McpProtocol.negotiate("2025-03-26"))
        assertEquals(McpProtocol.LATEST_VERSION, McpProtocol.negotiate("1999-01-01"))
    }
}
