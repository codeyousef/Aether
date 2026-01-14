package codes.yousef.aether.grpc.streaming

/**
 * Server-Sent Events (SSE) codec for streaming responses.
 *
 * SSE is used by gRPC-Web and Connect protocol for server streaming
 * over HTTP/1.1. Each event is formatted as:
 *
 * ```
 * event: <event-type>
 * id: <event-id>
 * data: <data>
 *
 * ```
 *
 * This codec handles formatting and parsing SSE events.
 */
class SseCodec {

    /**
     * Format a data payload as an SSE event.
     *
     * @param data The data to send
     * @param eventType Optional event type (e.g., "message", "error")
     * @param id Optional event ID for client-side tracking
     * @param retry Optional retry interval in milliseconds
     * @return The formatted SSE event string
     */
    fun formatEvent(
        data: String,
        eventType: String? = null,
        id: String? = null,
        retry: Long? = null
    ): String {
        return buildString {
            // Event type
            if (!eventType.isNullOrEmpty()) {
                append("event: $eventType\n")
            }

            // Event ID
            if (!id.isNullOrEmpty()) {
                append("id: $id\n")
            }

            // Retry interval
            if (retry != null) {
                append("retry: $retry\n")
            }

            // Data (handle multi-line data)
            data.lines().forEach { line ->
                append("data: $line\n")
            }

            // Event terminator
            append("\n")
        }
    }

    /**
     * Format a comment (keep-alive ping).
     *
     * @param comment The comment text
     * @return The formatted comment
     */
    fun formatComment(comment: String = ""): String {
        return ": $comment\n\n"
    }

    /**
     * Parse an SSE event from text.
     *
     * @param text The SSE event text
     * @return The parsed event, or null if invalid
     */
    fun parseEvent(text: String): SseEvent? {
        if (text.isBlank()) return null

        var eventType: String? = null
        var id: String? = null
        var retry: Long? = null
        val dataLines = mutableListOf<String>()

        for (line in text.lines()) {
            when {
                line.startsWith("event:") -> {
                    eventType = line.removePrefix("event:").trim()
                }

                line.startsWith("id:") -> {
                    id = line.removePrefix("id:").trim()
                }

                line.startsWith("retry:") -> {
                    retry = line.removePrefix("retry:").trim().toLongOrNull()
                }

                line.startsWith("data:") -> {
                    dataLines.add(line.removePrefix("data:").removePrefix(" "))
                }

                line.startsWith(":") -> {
                    // Comment, ignore
                }
            }
        }

        if (dataLines.isEmpty()) return null

        return SseEvent(
            data = dataLines.joinToString("\n"),
            eventType = eventType,
            id = id,
            retry = retry
        )
    }

    /**
     * Split a stream of SSE data into individual events.
     *
     * @param stream The raw SSE text stream
     * @return Sequence of event texts
     */
    fun splitEvents(stream: String): Sequence<String> {
        return sequence {
            val buffer = StringBuilder()
            for (line in stream.lineSequence()) {
                if (line.isEmpty() && buffer.isNotEmpty()) {
                    yield(buffer.toString())
                    buffer.clear()
                } else {
                    buffer.appendLine(line)
                }
            }
            if (buffer.isNotEmpty()) {
                yield(buffer.toString())
            }
        }
    }

    companion object {
        /**
         * Content type for SSE streams.
         */
        const val CONTENT_TYPE = "text/event-stream"

        /**
         * Character encoding for SSE.
         */
        const val CHARSET = "UTF-8"
    }
}

/**
 * Represents a parsed SSE event.
 */
data class SseEvent(
    val data: String,
    val eventType: String? = null,
    val id: String? = null,
    val retry: Long? = null
)
