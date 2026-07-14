package dev.kmcp.types

import kotlinx.serialization.Serializable

/** A resource a server exposes through `resources/list`. */
@Serializable
public data class Resource(
    public val uri: String,
    public val name: String,
    public val title: String? = null,
    public val description: String? = null,
    public val mimeType: String? = null,
)

/** Result of `resources/list`. */
@Serializable
public data class ListResourcesResult(
    public val resources: List<Resource>,
    public val nextCursor: String? = null,
)

/** Parameters of `resources/read`. */
@Serializable
public data class ReadResourceParams(
    public val uri: String,
)

/** Result of `resources/read`. */
@Serializable
public data class ReadResourceResult(
    public val contents: List<ResourceContents>,
)
