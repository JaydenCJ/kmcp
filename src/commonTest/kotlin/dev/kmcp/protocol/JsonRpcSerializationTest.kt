package dev.kmcp.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonRpcSerializationTest {

    @Test
    fun requestRoundTripWithNumericId() {
        val request = JsonRpcRequest(
            id = RequestId.Num(42),
            method = "tools/call",
            params = buildJsonObject { put("name", "echo") },
        )
        val wire = JsonRpc.encodeToString(request)
        val decoded = JsonRpc.decodeFromString(wire)
        assertEquals(request, decoded)
    }

    @Test
    fun requestRoundTripWithStringId() {
        val request = JsonRpcRequest(id = RequestId.Str("req-7"), method = "ping")
        val decoded = JsonRpc.decodeFromString(JsonRpc.encodeToString(request))
        assertIs<JsonRpcRequest>(decoded)
        assertEquals(RequestId.Str("req-7"), decoded.id)
        assertNull(decoded.params)
    }

    @Test
    fun encodedRequestAlwaysCarriesJsonRpcVersion() {
        val wire = JsonRpc.encodeToString(JsonRpcRequest(id = RequestId.Num(1), method = "ping"))
        val obj = JsonRpc.json.parseToJsonElement(wire).jsonObject
        assertEquals("2.0", obj.getValue("jsonrpc").jsonPrimitive.content)
    }

    @Test
    fun notificationRoundTrip() {
        val notification = JsonRpcNotification(method = "notifications/initialized")
        val decoded = JsonRpc.decodeFromString(JsonRpc.encodeToString(notification))
        assertIs<JsonRpcNotification>(decoded)
        assertEquals("notifications/initialized", decoded.method)
    }

    @Test
    fun responseRoundTrip() {
        val response = JsonRpcResponse(
            id = RequestId.Num(3),
            result = buildJsonObject { put("ok", true) },
        )
        val decoded = JsonRpc.decodeFromString(JsonRpc.encodeToString(response))
        assertEquals(response, decoded)
    }

    @Test
    fun errorResponseRoundTripAndNullId() {
        val error = JsonRpcErrorResponse(
            error = JsonRpcErrorObject(ErrorCodes.PARSE_ERROR, "Parse error"),
        )
        val wire = JsonRpc.encodeToString(error)
        // JSON-RPC requires id to be present and null when undeterminable.
        assertTrue(wire.contains("\"id\":null"), "wire form was: $wire")
        val decoded = JsonRpc.decodeFromString(wire)
        assertIs<JsonRpcErrorResponse>(decoded)
        assertEquals(RequestId.Null, decoded.id)
        assertEquals(ErrorCodes.PARSE_ERROR, decoded.error.code)
    }

    @Test
    fun decodeRejectsMissingVersion() {
        assertFailsWith<JsonRpcParseException> {
            JsonRpc.decodeFromString("""{"id":1,"method":"ping"}""")
        }
    }

    @Test
    fun decodeRejectsNonObjectPayload() {
        assertFailsWith<JsonRpcParseException> { JsonRpc.decodeFromString("[1,2,3]") }
    }

    @Test
    fun decodeRejectsInvalidJson() {
        assertFailsWith<JsonRpcParseException> { JsonRpc.decodeFromString("not json") }
    }

    @Test
    fun decodeRejectsFractionalId() {
        assertFailsWith<JsonRpcParseException> {
            JsonRpc.decodeFromString("""{"jsonrpc":"2.0","id":1.5,"method":"ping"}""")
        }
    }

    @Test
    fun paramsObjectFallsBackToEmpty() {
        val request = JsonRpcRequest(id = RequestId.Num(1), method = "ping", params = JsonPrimitive(3))
        assertTrue(request.paramsObject().isEmpty())
    }
}
