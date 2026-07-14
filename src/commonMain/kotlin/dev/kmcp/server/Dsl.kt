package dev.kmcp.server

import dev.kmcp.schema.ObjectSchemaBuilder
import dev.kmcp.schema.SchemaDsl
import dev.kmcp.types.CallToolResult
import dev.kmcp.types.GetPromptResult
import dev.kmcp.types.Implementation
import dev.kmcp.types.Prompt
import dev.kmcp.types.PromptArgument
import dev.kmcp.types.Resource
import dev.kmcp.types.ResourceContents
import dev.kmcp.types.TextContent
import dev.kmcp.types.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Validated tool arguments passed to a tool handler.
 *
 * By the time a handler runs, the arguments have already passed JSON Schema
 * validation, so the typed accessors are safe for declared required fields.
 */
public class ToolArguments(
    /** The raw arguments object as received on the wire. */
    public val raw: JsonObject,
) {
    /** Returns the string argument [name]. */
    public fun string(name: String): String =
        (raw[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Argument \"$name\" is not a string")

    /** Returns the string argument [name], or null when absent. */
    public fun stringOrNull(name: String): String? =
        (raw[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Returns the integer argument [name]. */
    public fun long(name: String): Long =
        (raw[name] as? JsonPrimitive)?.longOrNull
            ?: throw IllegalArgumentException("Argument \"$name\" is not an integer")

    /** Returns the integer argument [name], or null when absent. */
    public fun longOrNull(name: String): Long? =
        (raw[name] as? JsonPrimitive)?.longOrNull

    /** Returns the number argument [name]. */
    public fun double(name: String): Double =
        (raw[name] as? JsonPrimitive)?.doubleOrNull
            ?: throw IllegalArgumentException("Argument \"$name\" is not a number")

    /** Returns the boolean argument [name]. */
    public fun boolean(name: String): Boolean =
        (raw[name] as? JsonPrimitive)?.booleanOrNull
            ?: throw IllegalArgumentException("Argument \"$name\" is not a boolean")

    /** Returns the boolean argument [name], or null when absent. */
    public fun booleanOrNull(name: String): Boolean? =
        (raw[name] as? JsonPrimitive)?.booleanOrNull
}

/** Handler signature for a registered tool. */
public typealias ToolHandler = suspend (ToolArguments) -> CallToolResult

internal class RegisteredTool(
    val descriptor: Tool,
    val handler: ToolHandler,
)

internal class RegisteredResource(
    val descriptor: Resource,
    val reader: suspend () -> ResourceContents,
)

internal class RegisteredPrompt(
    val descriptor: Prompt,
    val renderer: suspend (Map<String, String>) -> GetPromptResult,
)

/** Builds a [CallToolResult] with a single text content block. */
public fun toolText(text: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text)))

/** Builds an in-band tool failure result with a single text block. */
public fun toolError(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

/** Builder scope for one tool registration inside [mcpServer]. */
@SchemaDsl
public class ToolBuilder internal constructor(
    private val name: String,
    private val description: String?,
    private val title: String?,
) {
    private var inputSchema: JsonObject = JsonObject(
        mapOf("type" to JsonPrimitive("object")),
    )
    private var outputSchema: JsonObject? = null
    private var handler: ToolHandler? = null

    /** Declares the tool's input arguments as an `object` JSON Schema. */
    public fun input(block: ObjectSchemaBuilder.() -> Unit) {
        inputSchema = ObjectSchemaBuilder().apply(block).build()
    }

    /** Declares a raw JSON Schema for the tool input. */
    public fun inputSchema(schema: JsonObject) {
        inputSchema = schema
    }

    /** Declares a JSON Schema for the tool's `structuredContent` output. */
    public fun outputSchema(schema: JsonObject) {
        outputSchema = schema
    }

    /** Registers the suspending handler invoked on `tools/call`. */
    public fun handle(handler: ToolHandler) {
        this.handler = handler
    }

    internal fun build(): RegisteredTool {
        val boundHandler = handler
            ?: throw IllegalStateException("Tool \"$name\" has no handler; call handle { ... }")
        return RegisteredTool(
            descriptor = Tool(
                name = name,
                description = description,
                title = title,
                inputSchema = inputSchema,
                outputSchema = outputSchema,
            ),
            handler = boundHandler,
        )
    }
}

/** Builder scope of the [mcpServer] DSL. */
@SchemaDsl
public class McpServerBuilder internal constructor(
    private val name: String,
    private val version: String,
    private val title: String?,
) {
    private val tools = LinkedHashMap<String, RegisteredTool>()
    private val resources = LinkedHashMap<String, RegisteredResource>()
    private val prompts = LinkedHashMap<String, RegisteredPrompt>()
    private var instructions: String? = null

    /** Sets the optional usage instructions returned by `initialize`. */
    public fun instructions(text: String) {
        instructions = text
    }

    /**
     * Registers a tool. Inside [block], declare the input schema with
     * [ToolBuilder.input] and the handler with [ToolBuilder.handle].
     */
    public fun tool(
        name: String,
        description: String? = null,
        title: String? = null,
        block: ToolBuilder.() -> Unit,
    ) {
        require(name !in tools) { "Tool \"$name\" is already registered" }
        tools[name] = ToolBuilder(name, description, title).apply(block).build()
    }

    /** Registers a resource whose contents are produced by [reader]. */
    public fun resource(
        uri: String,
        name: String,
        description: String? = null,
        mimeType: String? = null,
        reader: suspend () -> ResourceContents,
    ) {
        require(uri !in resources) { "Resource \"$uri\" is already registered" }
        resources[uri] = RegisteredResource(
            descriptor = Resource(
                uri = uri,
                name = name,
                description = description,
                mimeType = mimeType,
            ),
            reader = reader,
        )
    }

    /** Registers a prompt template rendered by [renderer]. */
    public fun prompt(
        name: String,
        description: String? = null,
        arguments: List<PromptArgument> = emptyList(),
        renderer: suspend (Map<String, String>) -> GetPromptResult,
    ) {
        require(name !in prompts) { "Prompt \"$name\" is already registered" }
        prompts[name] = RegisteredPrompt(
            descriptor = Prompt(name = name, description = description, arguments = arguments),
            renderer = renderer,
        )
    }

    internal fun build(): McpServer = McpServer(
        serverInfo = Implementation(name = name, version = version, title = title),
        instructions = instructions,
        tools = tools,
        resources = resources,
        prompts = prompts,
    )
}

/**
 * Builds an [McpServer] with a registration DSL.
 *
 * ```
 * val server = mcpServer(name = "echo", version = "0.1.0") {
 *     tool("echo", description = "Echo text back") {
 *         input { string("text", description = "Text to echo") }
 *         handle { args -> toolText("echo: " + args.string("text")) }
 *     }
 * }
 * ```
 */
public fun mcpServer(
    name: String,
    version: String,
    title: String? = null,
    block: McpServerBuilder.() -> Unit,
): McpServer = McpServerBuilder(name, version, title).apply(block).build()
