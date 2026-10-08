package codes.yousef.aether.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Per-consumer limits for an optional streaming request body. Server limits always take precedence. */
data class RequestBodyStreamLimits(
    val maximumTotalBytes: Long = 16L * 1024 * 1024,
    val maximumChunkBytes: Int = 8 * 1024,
    val deadline: Duration = 30.seconds
) {
    init {
        require(maximumTotalBytes > 0) { "Maximum stream size must be positive" }
        require(maximumChunkBytes > 0) { "Maximum stream chunk size must be positive" }
        require(deadline.isPositive()) { "Stream deadline must be positive" }
    }
}

/** Stable failure categories for bounded request streams. */
enum class RequestBodyStreamFailure {
    TOTAL_LIMIT_EXCEEDED,
    CHUNK_LIMIT_EXCEEDED,
    DEADLINE_EXCEEDED,
    CONNECTION_CLOSED,
    TRANSPORT_FAILURE,
    ALREADY_CONSUMED
}

/** Request-stream failure without transport or payload detail in its message. */
class RequestBodyStreamException(
    val failure: RequestBodyStreamFailure
) : Exception(
    when (failure) {
        RequestBodyStreamFailure.TOTAL_LIMIT_EXCEEDED -> "Request body exceeds the configured limit"
        RequestBodyStreamFailure.CHUNK_LIMIT_EXCEEDED -> "Request body chunk exceeds the configured limit"
        RequestBodyStreamFailure.DEADLINE_EXCEEDED -> "Request body deadline exceeded"
        RequestBodyStreamFailure.CONNECTION_CLOSED -> "Request body connection closed"
        RequestBodyStreamFailure.TRANSPORT_FAILURE -> "Request body transport failed"
        RequestBodyStreamFailure.ALREADY_CONSUMED -> "Request body stream was already consumed"
    }
)

/**
 * Single-consumer request body. [consume] applies transport backpressure and may be invoked once.
 * [highWaterBytes] reports the largest amount retained by this stream implementation.
 */
interface RequestBodyStream {
    val highWaterBytes: Long

    suspend fun consume(consumer: suspend (ByteArray) -> Unit)

    /** Stop reading and release the underlying source. Repeated calls are safe. */
    fun cancel()
}
