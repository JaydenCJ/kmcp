package dev.kmcp.discovery

import dev.kmcp.protocol.JsonRpc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `/.well-known/mcp` discovery document.
 *
 * Hosts publish this document so agents can find their MCP endpoints without
 * out-of-band configuration. The shape below follows the discovery drafts
 * circulating alongside the stateless Streamable HTTP work: a document
 * version plus a list of advertised servers with their endpoint URLs,
 * transports, and supported protocol revisions.
 */
@Serializable
public data class McpWellKnownDocument(
    /** Discovery document format version. */
    public val version: String = "1",
    /** The MCP servers this host advertises. */
    public val servers: List<McpWellKnownServer>,
)

/** One advertised server inside a [McpWellKnownDocument]. */
@Serializable
public data class McpWellKnownServer(
    /** Stable machine-readable server name. */
    public val name: String,
    /** Absolute or host-relative URL of the MCP endpoint. */
    public val endpoint: String,
    /** Transport identifier; kmcp serves `streamable-http`. */
    public val transport: String = "streamable-http",
    /** Optional human-readable description. */
    public val description: String? = null,
    /** Protocol revisions the server accepts, newest first. */
    public val protocolVersions: List<String> = emptyList(),
)

/** Helpers for building, parsing, and locating discovery documents. */
public object McpDiscovery {
    /** The well-known path where the discovery document is served. */
    public const val WELL_KNOWN_PATH: String = "/.well-known/mcp"

    // Discovery documents are read by third parties, so defaults such as
    // `version` and `transport` are written out explicitly.
    private val encoderJson: Json = Json(from = JsonRpc.json) { encodeDefaults = true }

    /** Parses a discovery document from its JSON [text]. */
    public fun parse(text: String): McpWellKnownDocument =
        JsonRpc.json.decodeFromString(McpWellKnownDocument.serializer(), text)

    /** Serializes [document] to JSON, writing defaulted members explicitly. */
    public fun encode(document: McpWellKnownDocument): String =
        encoderJson.encodeToString(McpWellKnownDocument.serializer(), document)

    /**
     * Builds the well-known URL for a server base URL, e.g.
     * `https://example.com` becomes `https://example.com/.well-known/mcp`.
     */
    public fun wellKnownUrl(baseUrl: String): String =
        baseUrl.trimEnd('/') + WELL_KNOWN_PATH
}
