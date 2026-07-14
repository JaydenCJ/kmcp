package dev.kmcp.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A single schema violation found by [SchemaValidator.validate]. */
public data class SchemaViolation(
    /** JSON-pointer-like path to the offending value, `$` for the root. */
    public val path: String,
    /** Human-readable description of the violation. */
    public val message: String,
) {
    override fun toString(): String = "$path: $message"
}

/**
 * Validator for the JSON Schema subset used by MCP tool input schemas.
 *
 * Supported keywords: `type` (string or array of strings), `properties`,
 * `required`, `additionalProperties` (boolean form), `enum`, `const`,
 * `items`, `minItems`, `maxItems`, `minLength`, `maxLength`, `pattern`,
 * `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`.
 * Unknown keywords are ignored, matching JSON Schema semantics.
 */
public object SchemaValidator {
    /**
     * Validates [value] against [schema] and returns every violation found.
     * An empty list means the value conforms to the supported subset.
     */
    public fun validate(schema: JsonObject, value: JsonElement): List<SchemaViolation> {
        val violations = mutableListOf<SchemaViolation>()
        validateNode(schema, value, "$", violations)
        return violations
    }

    private fun validateNode(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        out: MutableList<SchemaViolation>,
    ) {
        checkType(schema, value, path, out)
        checkEnumAndConst(schema, value, path, out)
        when (value) {
            is JsonObject -> validateObjectKeywords(schema, value, path, out)
            is JsonArray -> validateArrayKeywords(schema, value, path, out)
            is JsonPrimitive -> validatePrimitiveKeywords(schema, value, path, out)
        }
    }

    private fun checkType(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        out: MutableList<SchemaViolation>,
    ) {
        val typeElement = schema["type"] ?: return
        val allowed = when (typeElement) {
            is JsonPrimitive -> listOf(typeElement.content)
            is JsonArray -> typeElement.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> return
        }
        val actual = typeNameOf(value)
        val matches = allowed.any { candidate ->
            candidate == actual || (candidate == "number" && actual == "integer")
        }
        if (!matches) {
            out += SchemaViolation(path, "expected type ${allowed.joinToString("|")}, got $actual")
        }
    }

    private fun checkEnumAndConst(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        out: MutableList<SchemaViolation>,
    ) {
        (schema["enum"] as? JsonArray)?.let { options ->
            if (options.none { it == value }) {
                out += SchemaViolation(path, "value is not one of the allowed enum values")
            }
        }
        schema["const"]?.let { expected ->
            if (expected != value) {
                out += SchemaViolation(path, "value does not equal the required const")
            }
        }
    }

    private fun validateObjectKeywords(
        schema: JsonObject,
        value: JsonObject,
        path: String,
        out: MutableList<SchemaViolation>,
    ) {
        val properties = schema["properties"] as? JsonObject
        (schema["required"] as? JsonArray)?.forEach { requiredName ->
            val name = (requiredName as? JsonPrimitive)?.content ?: return@forEach
            if (name !in value) {
                out += SchemaViolation(path, "missing required property \"$name\"")
            }
        }
        if (properties != null) {
            for ((name, propertyValue) in value) {
                val propertySchema = properties[name] as? JsonObject
                if (propertySchema != null) {
                    validateNode(propertySchema, propertyValue, "$path.$name", out)
                } else if ((schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false) {
                    out += SchemaViolation("$path.$name", "additional property is not allowed")
                }
            }
        }
    }

    private fun validateArrayKeywords(
        schema: JsonObject,
        value: JsonArray,
        path: String,
        out: MutableList<SchemaViolation>,
    ) {
        (schema["minItems"] as? JsonPrimitive)?.longOrNull?.let { min ->
            if (value.size < min) out += SchemaViolation(path, "expected at least $min items")
        }
        (schema["maxItems"] as? JsonPrimitive)?.longOrNull?.let { max ->
            if (value.size > max) out += SchemaViolation(path, "expected at most $max items")
        }
        (schema["items"] as? JsonObject)?.let { itemSchema ->
            value.forEachIndexed { index, item ->
                validateNode(itemSchema, item, "$path[$index]", out)
            }
        }
    }

    private fun validatePrimitiveKeywords(
        schema: JsonObject,
        value: JsonPrimitive,
        path: String,
        out: MutableList<SchemaViolation>,
    ) {
        if (value.isString) {
            val text = value.content
            (schema["minLength"] as? JsonPrimitive)?.longOrNull?.let { min ->
                if (text.length < min) out += SchemaViolation(path, "expected at least $min characters")
            }
            (schema["maxLength"] as? JsonPrimitive)?.longOrNull?.let { max ->
                if (text.length > max) out += SchemaViolation(path, "expected at most $max characters")
            }
            (schema["pattern"] as? JsonPrimitive)?.content?.let { pattern ->
                val regex = runCatching { Regex(pattern) }.getOrNull()
                if (regex != null && !regex.containsMatchIn(text)) {
                    out += SchemaViolation(path, "value does not match pattern $pattern")
                }
            }
            return
        }
        val number = value.doubleOrNull ?: return
        (schema["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { min ->
            if (number < min) out += SchemaViolation(path, "value must be >= $min")
        }
        (schema["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { max ->
            if (number > max) out += SchemaViolation(path, "value must be <= $max")
        }
        (schema["exclusiveMinimum"] as? JsonPrimitive)?.doubleOrNull?.let { min ->
            if (number <= min) out += SchemaViolation(path, "value must be > $min")
        }
        (schema["exclusiveMaximum"] as? JsonPrimitive)?.doubleOrNull?.let { max ->
            if (number >= max) out += SchemaViolation(path, "value must be < $max")
        }
    }

    private fun typeNameOf(value: JsonElement): String = when (value) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            value.isString -> "string"
            value.booleanOrNull != null -> "boolean"
            value.longOrNull != null -> "integer"
            else -> "number"
        }
    }
}
