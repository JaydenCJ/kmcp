package dev.kmcp.types

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A tool a server exposes through `tools/list`. */
@Serializable
public data class Tool(
    public val name: String,
    public val description: String? = null,
    public val title: String? = null,
    /** JSON Schema describing the tool arguments. Always an `object` schema. */
    public val inputSchema: JsonObject,
    /** Optional JSON Schema describing `structuredContent` in results. */
    public val outputSchema: JsonObject? = null,
)

/** Result of `tools/list`. */
@Serializable
public data class ListToolsResult(
    public val tools: List<Tool>,
    public val nextCursor: String? = null,
)

/** Parameters of `tools/call`. */
@Serializable
public data class CallToolParams(
    public val name: String,
    public val arguments: JsonObject? = null,
)

/** Result of `tools/call`. */
@Serializable
public data class CallToolResult(
    public val content: List<ContentBlock>,
    /** Structured tool output matching the tool's `outputSchema`, if any. */
    public val structuredContent: JsonObject? = null,
    /** True when the tool itself failed; protocol errors use JSON-RPC errors. */
    public val isError: Boolean? = null,
) {
    /** Concatenated text of all [TextContent] blocks, for convenience. */
    public fun text(): String =
        content.filterIsInstance<TextContent>().joinToString(separator = "\n") { it.text }
}
