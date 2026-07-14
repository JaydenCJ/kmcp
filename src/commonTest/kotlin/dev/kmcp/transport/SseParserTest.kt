package dev.kmcp.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class SseParserTest {

    @Test
    fun parsesSingleEvent() {
        val events = SseParser.parse("data: hello\n\n")
        assertEquals(listOf(SseEvent(data = "hello")), events)
    }

    @Test
    fun parsesMultipleEventsWithNamesAndIds() {
        val body = buildString {
            append("event: message\n")
            append("id: 1\n")
            append("data: first\n")
            append("\n")
            append("event: custom\n")
            append("data: second\n")
            append("\n")
        }
        val events = SseParser.parse(body)
        assertEquals(2, events.size)
        assertEquals(SseEvent(event = "message", data = "first", id = "1"), events[0])
        assertEquals("custom", events[1].event)
    }

    @Test
    fun joinsMultiLineData() {
        val events = SseParser.parse("data: line1\ndata: line2\n\n")
        assertEquals("line1\nline2", events.single().data)
    }

    @Test
    fun ignoresCommentsAndUnknownFields() {
        val events = SseParser.parse(": keepalive\nretry: 500\ndata: payload\n\n")
        assertEquals("payload", events.single().data)
    }

    @Test
    fun handlesCrlfAndMissingTrailingBlankLine() {
        val events = SseParser.parse("data: a\r\n\r\ndata: b")
        assertEquals(listOf("a", "b"), events.map { it.data })
    }
}
