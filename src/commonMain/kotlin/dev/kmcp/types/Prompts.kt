package dev.kmcp.types

import kotlinx.serialization.Serializable

/** A prompt template a server exposes through `prompts/list`. */
@Serializable
public data class Prompt(
    public val name: String,
    public val title: String? = null,
    public val description: String? = null,
    public val arguments: List<PromptArgument> = emptyList(),
)

/** A declared argument of a [Prompt]. */
@Serializable
public data class PromptArgument(
    public val name: String,
    public val description: String? = null,
    public val required: Boolean? = null,
)

/** Result of `prompts/list`. */
@Serializable
public data class ListPromptsResult(
    public val prompts: List<Prompt>,
    public val nextCursor: String? = null,
)

/** Parameters of `prompts/get`. */
@Serializable
public data class GetPromptParams(
    public val name: String,
    public val arguments: Map<String, String>? = null,
)

/** A single message inside a rendered prompt. */
@Serializable
public data class PromptMessage(
    public val role: Role,
    public val content: ContentBlock,
)

/** Result of `prompts/get`. */
@Serializable
public data class GetPromptResult(
    public val description: String? = null,
    public val messages: List<PromptMessage>,
)
