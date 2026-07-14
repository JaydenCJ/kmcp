package dev.kmcp.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Marks the schema builder DSL so scopes do not leak into each other. */
@DslMarker
public annotation class SchemaDsl

/**
 * Builder for `object` JSON Schemas, used to declare MCP tool inputs.
 *
 * Example:
 * ```
 * val schema = objectSchema {
 *     string("city", description = "City name")
 *     integer("days", required = false, minimum = 1, maximum = 14)
 * }
 * ```
 */
@SchemaDsl
public class ObjectSchemaBuilder {
    private val properties = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()
    private var additionalProperties: Boolean? = null

    /** Declares a string property. */
    public fun string(
        name: String,
        description: String? = null,
        required: Boolean = true,
        enum: List<String>? = null,
        minLength: Int? = null,
        maxLength: Int? = null,
        pattern: String? = null,
    ) {
        addProperty(name, required) {
            put("type", "string")
            description?.let { put("description", it) }
            enum?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
            minLength?.let { put("minLength", it) }
            maxLength?.let { put("maxLength", it) }
            pattern?.let { put("pattern", it) }
        }
    }

    /** Declares an integer property. */
    public fun integer(
        name: String,
        description: String? = null,
        required: Boolean = true,
        minimum: Long? = null,
        maximum: Long? = null,
    ) {
        addProperty(name, required) {
            put("type", "integer")
            description?.let { put("description", it) }
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }
    }

    /** Declares a floating point number property. */
    public fun number(
        name: String,
        description: String? = null,
        required: Boolean = true,
        minimum: Double? = null,
        maximum: Double? = null,
    ) {
        addProperty(name, required) {
            put("type", "number")
            description?.let { put("description", it) }
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }
    }

    /** Declares a boolean property. */
    public fun boolean(
        name: String,
        description: String? = null,
        required: Boolean = true,
    ) {
        addProperty(name, required) {
            put("type", "boolean")
            description?.let { put("description", it) }
        }
    }

    /** Declares an array property whose items match [items]. */
    public fun array(
        name: String,
        items: JsonObject,
        description: String? = null,
        required: Boolean = true,
        minItems: Int? = null,
        maxItems: Int? = null,
    ) {
        addProperty(name, required) {
            put("type", "array")
            description?.let { put("description", it) }
            put("items", items)
            minItems?.let { put("minItems", it) }
            maxItems?.let { put("maxItems", it) }
        }
    }

    /** Declares a nested object property built with the same DSL. */
    public fun obj(
        name: String,
        description: String? = null,
        required: Boolean = true,
        block: ObjectSchemaBuilder.() -> Unit,
    ) {
        val nested = ObjectSchemaBuilder().apply(block).build()
        addProperty(name, required) {
            description?.let { put("description", it) }
            nested.forEach { (key, value) -> put(key, value) }
        }
    }

    /** Forbids properties that are not declared in this schema. */
    public fun noAdditionalProperties() {
        additionalProperties = false
    }

    private fun addProperty(
        name: String,
        isRequired: Boolean,
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        properties[name] = buildJsonObject(block)
        if (isRequired) required += name
    }

    /** Builds the resulting `object` schema. */
    public fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            JsonObject(properties),
        )
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
        additionalProperties?.let { put("additionalProperties", it) }
    }
}

/** Builds an `object` JSON Schema with the [ObjectSchemaBuilder] DSL. */
public fun objectSchema(block: ObjectSchemaBuilder.() -> Unit): JsonObject =
    ObjectSchemaBuilder().apply(block).build()
