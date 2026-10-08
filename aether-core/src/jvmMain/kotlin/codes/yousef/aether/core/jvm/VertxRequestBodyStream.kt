package codes.yousef.aether.core.jvm

import codes.yousef.aether.core.RequestBodyStream
import codes.yousef.aether.core.RequestBodyStreamException
import codes.yousef.aether.core.RequestBodyStreamFailure
import codes.yousef.aether.core.RequestBodyStreamLimits
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import kotlin.time.toDuration

/** Demand-driven Vert.x body source. The HTTP stream is paused unless bounded consumer demand exists. */
@InternalAetherServerApi
class VertxRequestBodySource(
    private val vertx: Vertx,
    private val request: HttpServerRequest,
    private val maximumBytes: Long,
    private val maximumChunkBytes: Int,
    private val timeoutMillis: Long,
    private val bufferedChunks: Int
) {
    private val chunks = Channel<ByteArray>(bufferedChunks)
    private val consumed = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val endedNormally = AtomicBoolean(false)
    private val receivedBytes = AtomicLong(0)
    private val queuedBytes = AtomicLong(0)
    private val maximumQueuedBytes = AtomicLong(0)
    @Volatile
    private var disconnectHandler: (() -> Unit)? = null

    val preflightFailure: RequestBodyStreamFailure?

    init {
        require(maximumBytes > 0) { "Maximum body size must be positive" }
        require(maximumChunkBytes > 0) { "Maximum body chunk size must be positive" }
        require(timeoutMillis > 0) { "Body read timeout must be positive" }
        require(bufferedChunks > 0) { "Buffered body chunks must be positive" }

        request.pause()
        val declaredLength = parseDeclaredContentLength(request)
        preflightFailure = when {
            declaredLength == null -> RequestBodyStreamFailure.TRANSPORT_FAILURE
            declaredLength > maximumBytes -> RequestBodyStreamFailure.TOTAL_LIMIT_EXCEEDED
            else -> null
        }

        request.handler { buffer ->
            if (completed.get()) return@handler
            val bytes = buffer.bytes
            val total = receivedBytes.addAndGet(bytes.size.toLong())
            when {
                bytes.size > maximumChunkBytes -> fail(RequestBodyStreamFailure.CHUNK_LIMIT_EXCEEDED)
                total > maximumBytes -> fail(RequestBodyStreamFailure.TOTAL_LIMIT_EXCEEDED)
                else -> {
                    val queued = queuedBytes.addAndGet(bytes.size.toLong())
                    if (chunks.trySend(bytes).isFailure) {
                        queuedBytes.addAndGet(-bytes.size.toLong())
                        fail(RequestBodyStreamFailure.TRANSPORT_FAILURE)
                    } else {
                        maximumQueuedBytes.accumulateAndGet(queued, ::maxOf)
                    }
                }
            }
        }
        request.endHandler {
            endedNormally.set(true)
            if (completed.compareAndSet(false, true)) chunks.close()
        }
        request.exceptionHandler { fail(RequestBodyStreamFailure.TRANSPORT_FAILURE) }
        request.connection().closeHandler {
            if (completed.compareAndSet(false, true)) {
                chunks.close(RequestBodyStreamException(RequestBodyStreamFailure.CONNECTION_CLOSED))
                disconnectHandler?.invoke()
            }
        }

        val timer = vertx.setTimer(timeoutMillis) {
            fail(RequestBodyStreamFailure.DEADLINE_EXCEEDED)
        }
        chunks.invokeOnClose { vertx.cancelTimer(timer) }
        preflightFailure?.let(::fail)
    }

    val highWaterBytes: Long
        get() = maximumQueuedBytes.get()

    fun onDisconnect(handler: () -> Unit) {
        disconnectHandler = handler
    }

    fun open(limits: RequestBodyStreamLimits): RequestBodyStream {
        if (!consumed.compareAndSet(false, true)) {
            throw RequestBodyStreamException(RequestBodyStreamFailure.ALREADY_CONSUMED)
        }
        val effectiveTotal = minOf(maximumBytes, limits.maximumTotalBytes)
        val effectiveChunk = minOf(maximumChunkBytes, limits.maximumChunkBytes)

        return object : RequestBodyStream {
            override val highWaterBytes: Long
                get() = this@VertxRequestBodySource.highWaterBytes

            override suspend fun consume(consumer: suspend (ByteArray) -> Unit) {
                var consumerBytes = 0L
                request.fetch(bufferedChunks.toLong())
                val completedInTime = withTimeoutOrNull(limits.deadline) {
                    for (chunk in chunks) {
                        queuedBytes.addAndGet(-chunk.size.toLong())
                        if (chunk.size > effectiveChunk) {
                            fail(RequestBodyStreamFailure.CHUNK_LIMIT_EXCEEDED)
                            throw RequestBodyStreamException(RequestBodyStreamFailure.CHUNK_LIMIT_EXCEEDED)
                        }
                        consumerBytes += chunk.size
                        if (consumerBytes > effectiveTotal) {
                            fail(RequestBodyStreamFailure.TOTAL_LIMIT_EXCEEDED)
                            throw RequestBodyStreamException(RequestBodyStreamFailure.TOTAL_LIMIT_EXCEEDED)
                        }
                        consumer(chunk)
                        request.fetch(1)
                    }
                    true
                } ?: false
                if (!completedInTime) {
                    fail(RequestBodyStreamFailure.DEADLINE_EXCEEDED)
                    throw RequestBodyStreamException(RequestBodyStreamFailure.DEADLINE_EXCEEDED)
                }
            }

            override fun cancel() {
                this@VertxRequestBodySource.cancel()
            }
        }
    }

    suspend fun readAll(): ByteArray {
        require(maximumBytes <= Int.MAX_VALUE) { "Buffered request limit exceeds JVM array capacity" }
        val output = ByteArrayOutputStream(minOf(maximumBytes.toInt(), 8_192))
        open(
            RequestBodyStreamLimits(
                maximumTotalBytes = maximumBytes,
                maximumChunkBytes = maximumChunkBytes,
                deadline = timeoutMillis.toDuration(kotlin.time.DurationUnit.MILLISECONDS)
            )
        ).consume { bytes -> output.write(bytes) }
        return output.toByteArray()
    }

    /** Release an unread/incomplete source. Complete streams keep their connection reusable. */
    fun cancel() {
        if (!released.compareAndSet(false, true)) return
        if (completed.compareAndSet(false, true)) chunks.cancel()
        request.pause()
        if (!endedNormally.get()) request.connection().close()
    }

    private fun fail(failure: RequestBodyStreamFailure) {
        if (completed.compareAndSet(false, true)) {
            request.pause()
            if (!request.response().headWritten() && !request.response().ended()) {
                request.response().putHeader("Connection", "close")
            }
            chunks.close(RequestBodyStreamException(failure))
        }
    }
}
