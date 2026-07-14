package dev.kmcp.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A JSON-RPC 2.0 request or response identifier.
 *
 * The JSON-RPC specification allows string, number, and (for error responses
 * to unparseable requests) null identifiers. [Null] is only ever produced by
 * error responses; requests must use [Str] or [Num].
 */
@Serializable(with = RequestIdSerializer::class)
public sealed class RequestId {
    /** A string identifier, e.g. `"req-1"`. */
    public data class Str(public val value: String) : RequestId() {
        override fun toString(): String = value
    }

    /** An integral identifier, e.g. `42`. */
    public data class Num(public val value: Long) : RequestId() {
        override fun toString(): String = value.toString()
    }

    /** The null identifier used when a request id could not be determined. */
    public object Null : RequestId() {
        override fun toString(): String = "null"
    }
}

internal object RequestIdSerializer : KSerializer<RequestId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.kmcp.protocol.RequestId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RequestId) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("RequestId only supports JSON encoding")
        when (value) {
            is RequestId.Str -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.value))
            is RequestId.Num -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.value))
            is RequestId.Null -> jsonEncoder.encodeJsonElement(JsonNull)
        }
    }

    override fun deserialize(decoder: Decoder): RequestId {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("RequestId only supports JSON decoding")
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return RequestId.Null
        val primitive = element as? JsonPrimitive
            ?: throw JsonRpcParseException("Request id must be a string, number, or null")
        return if (primitive.isString) {
            RequestId.Str(primitive.content)
        } else {
            primitive.longOrNull?.let { RequestId.Num(it) }
                ?: throw JsonRpcParseException("Request id must be an integral number")
        }
    }
}

/** Marker interface for the four JSON-RPC 2.0 message shapes. */
public sealed interface JsonRpcMessage

/** A JSON-RPC 2.0 request: carries an [id] and expects exactly one response. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class JsonRpcRequest(
    @EncodeDefault public val jsonrpc: String = JsonRpc.VERSION,
    public val id: RequestId,
    public val method: String,
    public val params: JsonElement? = null,
) : JsonRpcMessage

/** A JSON-RPC 2.0 notification: fire-and-forget, no response is produced. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class JsonRpcNotification(
    @EncodeDefault public val jsonrpc: String = JsonRpc.VERSION,
    public val method: String,
    public val params: JsonElement? = null,
) : JsonRpcMessage

/** A successful JSON-RPC 2.0 response carrying a [result] for request [id]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class JsonRpcResponse(
    @EncodeDefault public val jsonrpc: String = JsonRpc.VERSION,
    public val id: RequestId,
    public val result: JsonElement,
) : JsonRpcMessage

/** The `error` member of a JSON-RPC 2.0 error response. */
@Serializable
public data class JsonRpcErrorObject(
    public val code: Int,
    public val message: String,
    public val data: JsonElement? = null,
)

/** A JSON-RPC 2.0 error response for request [id]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class JsonRpcErrorResponse(
    @EncodeDefault public val jsonrpc: String = JsonRpc.VERSION,
    @EncodeDefault public val id: RequestId = RequestId.Null,
    public val error: JsonRpcErrorObject,
) : JsonRpcMessage

/** Thrown when a payload cannot be decoded as a JSON-RPC 2.0 message. */
public class JsonRpcParseException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Standard JSON-RPC 2.0 error codes plus the MCP-specific codes.
 *
 * The `-32700..-32600` range comes from the JSON-RPC 2.0 specification;
 * [RESOURCE_NOT_FOUND] is defined by the MCP specification for
 * `resources/read` on an unknown URI.
 */
public object ErrorCodes {
    public const val PARSE_ERROR: Int = -32700
    public const val INVALID_REQUEST: Int = -32600
    public const val METHOD_NOT_FOUND: Int = -32601
    public const val INVALID_PARAMS: Int = -32602
    public const val INTERNAL_ERROR: Int = -32603
    public const val RESOURCE_NOT_FOUND: Int = -32002
}

/**
 * Encoder/decoder for single JSON-RPC 2.0 messages.
 *
 * The 2025-06-18 revision of the MCP specification removed JSON-RPC batching,
 * so only single messages are supported here.
 */
public object JsonRpc {
    /** The protocol version constant, always `"2.0"`. */
    public const val VERSION: String = "2.0"

    /** The shared [Json] configuration used for every MCP payload. */
    public val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = "type"
    }

    /** Serializes [message] to its wire representation. */
    public fun encodeToString(message: JsonRpcMessage): String = when (message) {
        is JsonRpcRequest -> json.encodeToString(JsonRpcRequest.serializer(), message)
        is JsonRpcNotification -> json.encodeToString(JsonRpcNotification.serializer(), message)
        is JsonRpcResponse -> json.encodeToString(JsonRpcResponse.serializer(), message)
        is JsonRpcErrorResponse -> json.encodeToString(JsonRpcErrorResponse.serializer(), message)
    }

    /**
     * Parses [text] into one of the four message shapes.
     *
     * @throws JsonRpcParseException if the payload is not valid JSON or does
     * not match any JSON-RPC 2.0 message shape.
     */
    public fun decodeFromString(text: String): JsonRpcMessage {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw JsonRpcParseException("Invalid JSON payload", e)
        }
        return decodeFromElement(element)
    }

    /** Parses an already-decoded JSON [element] into a JSON-RPC message. */
    public fun decodeFromElement(element: JsonElement): JsonRpcMessage {
        val obj = element as? JsonObject
            ?: throw JsonRpcParseException("A JSON-RPC message must be a JSON object")
        val version = (obj["jsonrpc"] as? JsonPrimitive)?.content
        if (version != VERSION) {
            throw JsonRpcParseException("Unsupported or missing jsonrpc version: $version")
        }
        return try {
            when {
                "method" in obj && "id" in obj ->
                    json.decodeFromJsonElement(JsonRpcRequest.serializer(), obj)
                "method" in obj ->
                    json.decodeFromJsonElement(JsonRpcNotification.serializer(), obj)
                "result" in obj ->
                    json.decodeFromJsonElement(JsonRpcResponse.serializer(), obj)
                "error" in obj ->
                    json.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), obj)
                else -> throw JsonRpcParseException(
                    "Object matches no JSON-RPC message shape (method/result/error missing)",
                )
            }
        } catch (e: JsonRpcParseException) {
            throw e
        } catch (e: Exception) {
            throw JsonRpcParseException("Malformed JSON-RPC message", e)
        }
    }
}

/** Convenience accessor: params decoded as a [JsonObject], or an empty one. */
public fun JsonRpcRequest.paramsObject(): JsonObject =
    (params as? JsonObject) ?: JsonObject(emptyMap())

/** Convenience accessor for a required string member of a params object. */
internal fun JsonObject.requiredString(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw JsonRpcParseException("Missing or non-string param: $key")

internal fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
