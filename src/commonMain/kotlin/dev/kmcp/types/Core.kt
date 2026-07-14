package dev.kmcp.types

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * MCP protocol version constants and negotiation logic.
 *
 * kmcp targets the stateless Streamable HTTP transport of the current MCP
 * specification generation. Version negotiation follows the spec: the client
 * proposes a version in `initialize`; if the server supports it, the server
 * echoes it back, otherwise the server answers with the latest version it
 * supports and the client decides whether it can proceed.
 */
public object McpProtocol {
    /** Protocol revisions this SDK implements, newest first. */
    public val SUPPORTED_VERSIONS: List<String> = listOf("2025-06-18", "2025-03-26")

    /** The newest protocol revision this SDK implements. */
    public val LATEST_VERSION: String = SUPPORTED_VERSIONS.first()

    /** The HTTP header carrying the negotiated protocol version. */
    public const val VERSION_HEADER: String = "mcp-protocol-version"

    /** Returns the version a server should answer with for a client proposal. */
    public fun negotiate(requested: String): String =
        if (requested in SUPPORTED_VERSIONS) requested else LATEST_VERSION
}

/** Name and version of an MCP client or server implementation. */
@Serializable
public data class Implementation(
    public val name: String,
    public val version: String,
    public val title: String? = null,
)

/** Capability flags a client advertises during `initialize`. */
@Serializable
public data class ClientCapabilities(
    public val roots: RootsCapability? = null,
    public val sampling: JsonObject? = null,
    public val elicitation: JsonObject? = null,
    public val experimental: JsonObject? = null,
)

/** Client capability: the client can expose filesystem roots. */
@Serializable
public data class RootsCapability(
    public val listChanged: Boolean? = null,
)

/** Capability flags a server advertises during `initialize`. */
@Serializable
public data class ServerCapabilities(
    public val tools: ToolsCapability? = null,
    public val resources: ResourcesCapability? = null,
    public val prompts: PromptsCapability? = null,
    public val logging: JsonObject? = null,
    public val completions: JsonObject? = null,
    public val experimental: JsonObject? = null,
)

/** Server capability: the server exposes callable tools. */
@Serializable
public data class ToolsCapability(
    public val listChanged: Boolean? = null,
)

/** Server capability: the server exposes readable resources. */
@Serializable
public data class ResourcesCapability(
    public val subscribe: Boolean? = null,
    public val listChanged: Boolean? = null,
)

/** Server capability: the server exposes prompt templates. */
@Serializable
public data class PromptsCapability(
    public val listChanged: Boolean? = null,
)

/** Parameters of the `initialize` request. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class InitializeParams(
    public val protocolVersion: String,
    /**
     * The MCP specification makes `capabilities` a required member of
     * `initialize`, and strict servers (for example the official TypeScript
     * SDK) reject the request when it is absent. The shared [dev.kmcp.protocol.JsonRpc.json]
     * configuration uses `encodeDefaults = false`, so this property is marked
     * [EncodeDefault] to guarantee it is always present on the wire (an empty
     * object when the client advertises nothing). The default value keeps
     * decoding lenient for clients that omit it.
     */
    @EncodeDefault
    public val capabilities: ClientCapabilities = ClientCapabilities(),
    public val clientInfo: Implementation,
)

/** Result of the `initialize` request. */
@Serializable
public data class InitializeResult(
    public val protocolVersion: String,
    public val capabilities: ServerCapabilities,
    public val serverInfo: Implementation,
    public val instructions: String? = null,
)
