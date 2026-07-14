package dev.kmcp.transport

/** A single event parsed from a `text/event-stream` payload. */
public data class SseEvent(
    /** The event name; `"message"` when the stream does not set one. */
    public val event: String = "message",
    /** The event data with multi-line `data:` fields joined by `\n`. */
    public val data: String,
    /** The last `id:` field seen in the event, if any. */
    public val id: String? = null,
)

/**
 * Minimal Server-Sent Events parser for buffered `text/event-stream` bodies.
 *
 * Streamable HTTP servers may answer a POST with an SSE stream that carries
 * JSON-RPC messages; this parser turns such a buffered stream into events.
 * It handles multi-line data fields, comment lines, and CRLF line endings.
 */
public object SseParser {
    /** Parses [body] into the list of events it contains, in order. */
    public fun parse(body: String): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        var eventName: String? = null
        var eventId: String? = null
        val dataLines = mutableListOf<String>()

        fun flush() {
            if (dataLines.isNotEmpty()) {
                events += SseEvent(
                    event = eventName ?: "message",
                    data = dataLines.joinToString(separator = "\n"),
                    id = eventId,
                )
            }
            eventName = null
            dataLines.clear()
        }

        for (rawLine in body.split("\n")) {
            val line = rawLine.removeSuffix("\r")
            when {
                line.isEmpty() -> flush()
                line.startsWith(":") -> Unit // comment line, ignored
                else -> {
                    val colon = line.indexOf(':')
                    val field = if (colon >= 0) line.substring(0, colon) else line
                    var value = if (colon >= 0) line.substring(colon + 1) else ""
                    if (value.startsWith(" ")) value = value.substring(1)
                    when (field) {
                        "event" -> eventName = value
                        "data" -> dataLines += value
                        "id" -> eventId = value
                        else -> Unit // unrecognized fields are ignored per spec
                    }
                }
            }
        }
        flush()
        return events
    }
}
