package dev.kmcp.types

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * A content block inside tool results and prompt messages.
 *
 * Serialized polymorphically on the `type` member, matching the MCP wire
 * format (`text`, `image`, `audio`, `resource`).
 */
@Serializable
public sealed class ContentBlock

/** Plain text content. */
@Serializable
@SerialName("text")
public data class TextContent(
    public val text: String,
) : ContentBlock()

/** Base64-encoded image content. */
@Serializable
@SerialName("image")
public data class ImageContent(
    public val data: String,
    public val mimeType: String,
) : ContentBlock()

/** Base64-encoded audio content. */
@Serializable
@SerialName("audio")
public data class AudioContent(
    public val data: String,
    public val mimeType: String,
) : ContentBlock()

/** A resource embedded directly in a message. */
@Serializable
@SerialName("resource")
public data class EmbeddedResource(
    public val resource: ResourceContents,
) : ContentBlock()

/**
 * The contents of a resource: either UTF-8 text or base64 binary data.
 *
 * The MCP wire format distinguishes the two by the presence of a `text` or
 * `blob` member rather than a discriminator, hence the custom serializer.
 */
@Serializable(with = ResourceContentsSerializer::class)
public sealed class ResourceContents {
    public abstract val uri: String
    public abstract val mimeType: String?
}

/** Text resource contents. */
@Serializable
public data class TextResourceContents(
    override val uri: String,
    override val mimeType: String? = null,
    public val text: String,
) : ResourceContents()

/** Binary resource contents, base64-encoded in [blob]. */
@Serializable
public data class BlobResourceContents(
    override val uri: String,
    override val mimeType: String? = null,
    public val blob: String,
) : ResourceContents()

internal object ResourceContentsSerializer :
    JsonContentPolymorphicSerializer<ResourceContents>(ResourceContents::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out ResourceContents> =
        if ("blob" in element.jsonObject) {
            BlobResourceContents.serializer()
        } else {
            TextResourceContents.serializer()
        }
}

/** The role of a prompt message author. */
@Serializable
public enum class Role {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,
}
