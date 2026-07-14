package dev.kmcp.schema

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaValidatorTest {

    private val weatherSchema = objectSchema {
        string("city", description = "City name", minLength = 1)
        integer("days", required = false, minimum = 1, maximum = 14)
        string("units", required = false, enum = listOf("metric", "imperial"))
        noAdditionalProperties()
    }

    @Test
    fun builderProducesExpectedSchemaShape() {
        assertEquals("object", weatherSchema.getValue("type").let { (it as JsonPrimitive).content })
        val required = weatherSchema.getValue("required").toString()
        assertTrue("city" in required)
        assertTrue("days" !in required)
    }

    @Test
    fun validObjectPasses() {
        val value = buildJsonObject {
            put("city", "Tokyo")
            put("days", 3)
            put("units", "metric")
        }
        assertEquals(emptyList(), SchemaValidator.validate(weatherSchema, value))
    }

    @Test
    fun missingRequiredPropertyIsReported() {
        val violations = SchemaValidator.validate(weatherSchema, buildJsonObject { put("days", 3) })
        assertTrue(violations.any { "city" in it.message }, "violations: $violations")
    }

    @Test
    fun wrongTypeIsReported() {
        val value = buildJsonObject {
            put("city", "Tokyo")
            put("days", "three")
        }
        val violations = SchemaValidator.validate(weatherSchema, value)
        assertTrue(violations.any { it.path == "$.days" && "integer" in it.message })
    }

    @Test
    fun enumViolationIsReported() {
        val value = buildJsonObject {
            put("city", "Tokyo")
            put("units", "kelvin")
        }
        val violations = SchemaValidator.validate(weatherSchema, value)
        assertTrue(violations.any { it.path == "$.units" && "enum" in it.message })
    }

    @Test
    fun rangeViolationsAreReported() {
        val value = buildJsonObject {
            put("city", "Tokyo")
            put("days", 30)
        }
        val violations = SchemaValidator.validate(weatherSchema, value)
        assertTrue(violations.any { it.path == "$.days" && "<=" in it.message })
    }

    @Test
    fun additionalPropertyIsRejectedWhenForbidden() {
        val value = buildJsonObject {
            put("city", "Tokyo")
            put("zip", "100-0001")
        }
        val violations = SchemaValidator.validate(weatherSchema, value)
        assertTrue(violations.any { it.path == "$.zip" })
    }

    @Test
    fun nestedObjectsAndArraysAreValidated() {
        val schema = objectSchema {
            obj("filter") {
                string("field")
                array(
                    "values",
                    items = buildJsonObject { put("type", "string") },
                    minItems = 1,
                )
            }
        }
        val bad = buildJsonObject {
            put(
                "filter",
                buildJsonObject {
                    put("field", "name")
                    put("values", buildJsonArray { add(JsonPrimitive(1)) })
                },
            )
        }
        val violations = SchemaValidator.validate(schema, bad)
        assertEquals(listOf("$.filter.values[0]"), violations.map { it.path })
    }

    @Test
    fun integerAcceptedWhereNumberExpected() {
        val schema = objectSchema { number("ratio", minimum = 0.0, maximum = 1.0) }
        val value = buildJsonObject { put("ratio", 1) }
        assertEquals(emptyList(), SchemaValidator.validate(schema, value))
    }

    @Test
    fun stringLengthAndPatternChecked() {
        val schema = objectSchema {
            string("code", minLength = 2, maxLength = 4, pattern = "^[A-Z]+$")
        }
        val short = buildJsonObject { put("code", "A") }
        val bad = buildJsonObject { put("code", "abc") }
        val good = buildJsonObject { put("code", "ABC") }
        assertTrue(SchemaValidator.validate(schema, short).isNotEmpty())
        assertTrue(SchemaValidator.validate(schema, bad).isNotEmpty())
        assertEquals(emptyList(), SchemaValidator.validate(schema, good))
    }
}
