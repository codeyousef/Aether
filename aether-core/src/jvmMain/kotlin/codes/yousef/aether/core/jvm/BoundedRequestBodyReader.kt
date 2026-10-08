package codes.yousef.aether.core.jvm
import codes.yousef.aether.core.pipeline.ApiErrorKind
import codes.yousef.aether.core.pipeline.RequestDiagnosticsPolicy
import codes.yousef.aether.core.pipeline.encodeApiError
import codes.yousef.aether.core.pipeline.selectRequestId

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerRequest
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** Internal cross-module server seam; not an application request-body API. */
@RequiresOptIn(
    message = "This API is reserved for Aether server adapters.",
    level = RequiresOptIn.Level.ERROR
)
annotation class InternalAetherServerApi

@InternalAetherServerApi
enum class RequestBodyReadFailure {
    TIMEOUT,
    TRANSPORT,
    CONNECTION_CLOSED,
    INVALID_FRAMING
}

@InternalAetherServerApi
sealed interface BoundedRequestBodyResult {
    data class Complete(val bytes: ByteArray) : BoundedRequestBodyResult
    data object TooLarge : BoundedRequestBodyResult
    data class Incomplete(val failure: RequestBodyReadFailure) : BoundedRequestBodyResult
}

/**
 * Installs every request callback synchronously and retains at most [maximumBytes].
 * Vert.x invokes the data handler after HTTP content decoding, so the limit applies to decoded bytes.
 */
@InternalAetherServerApi
fun readBoundedRequestBody(
    vertx: Vertx,
    request: HttpServerRequest,
    maximumBytes: Int,
    timeoutMillis: Long
): Deferred<BoundedRequestBodyResult> {
    require(maximumBytes > 0) { "Maximum body size must be positive" }
    require(timeoutMillis > 0) { "Body read timeout must be positive" }

    val result = CompletableDeferred<BoundedRequestBodyResult>()
    val body = BoundedRequestBodyBuffer(maximumBytes)
    val declaredLength = parseDeclaredContentLength(request)

    fun complete(value: BoundedRequestBodyResult, stopReading: Boolean = false) {
        if (result.complete(value) && stopReading) request.pause()
    }

    request.handler { chunk ->
        if (!result.isCompleted && !body.append(chunk)) {
            complete(BoundedRequestBodyResult.TooLarge, stopReading = true)
        }
    }
    request.endHandler {
        if (!result.isCompleted) {
            val bytes = body.finish()
            when {
                bytes == null -> complete(BoundedRequestBodyResult.TooLarge, stopReading = true)
                declaredLength == null -> complete(
                    BoundedRequestBodyResult.Incomplete(RequestBodyReadFailure.INVALID_FRAMING),
                    stopReading = true
                )
                declaredLength >= 0 && declaredLength != body.receivedBytes -> complete(
                    BoundedRequestBodyResult.Incomplete(RequestBodyReadFailure.INVALID_FRAMING),
                    stopReading = true
                )
                else -> complete(BoundedRequestBodyResult.Complete(bytes))
            }
        }
    }
    request.exceptionHandler {
        complete(
            BoundedRequestBodyResult.Incomplete(RequestBodyReadFailure.TRANSPORT),
            stopReading = true
        )
    }
    request.connection().closeHandler {
        complete(BoundedRequestBodyResult.Incomplete(RequestBodyReadFailure.CONNECTION_CLOSED))
    }

    val timer = vertx.setTimer(timeoutMillis) {
        complete(BoundedRequestBodyResult.Incomplete(RequestBodyReadFailure.TIMEOUT), stopReading = true)
    }
    result.invokeOnCompletion { vertx.cancelTimer(timer) }

    when {
        declaredLength == null -> complete(
            BoundedRequestBodyResult.Incomplete(RequestBodyReadFailure.INVALID_FRAMING),
            stopReading = true
        )
        declaredLength > maximumBytes.toLong() -> complete(BoundedRequestBodyResult.TooLarge, stopReading = true)
    }

    return result
}

/** Sends a safe pre-dispatch rejection and closes instead of draining attacker-controlled input. */
@InternalAetherServerApi
suspend fun rejectRequestBody(request: HttpServerRequest, result: BoundedRequestBodyResult) {
    val kind = when (result) {
        BoundedRequestBodyResult.TooLarge -> ApiErrorKind.PAYLOAD_TOO_LARGE
        is BoundedRequestBodyResult.Incomplete ->
            if (result.failure == RequestBodyReadFailure.TIMEOUT) {
                ApiErrorKind.REQUEST_TIMEOUT
            } else {
                ApiErrorKind.BAD_REQUEST
            }
        is BoundedRequestBodyResult.Complete -> error("A complete request body cannot be rejected")
    }
    respondToRawRequestFailure(request, kind, closeConnection = true)
}

@InternalAetherServerApi
suspend fun respondToRawRequestFailure(
    request: HttpServerRequest,
    kind: ApiErrorKind = ApiErrorKind.INTERNAL,
    closeConnection: Boolean = false,
    diagnostics: RequestDiagnosticsPolicy = RequestDiagnosticsPolicy(),
    establishedRequestId: String? = null
) {
    val response = request.response()
    if (response.headWritten() || response.ended()) {
        if (closeConnection) request.connection().close()
        return
    }
    val requestId = establishedRequestId
        ?: selectRequestId(request.getHeader(diagnostics.requestIdHeader), diagnostics)
    var responseEnded = false
    try {
        response
            .setStatusCode(kind.statusCode)
            .putHeader("Content-Type", "application/json; charset=utf-8")
            .putHeader("Cache-Control", "no-store")
            .putHeader(diagnostics.requestIdHeader, requestId)
            .apply { if (closeConnection) putHeader("Connection", "close") }
            .end(encodeApiError(kind, requestId))
            .coAwait()
        responseEnded = true
    } finally {
        if (closeConnection && !responseEnded) request.connection().close()
    }
}

private fun parseDeclaredContentLength(request: HttpServerRequest): Long? {
    if (request.getHeader("Transfer-Encoding") != null && request.getHeader("Content-Length") != null) return null
    val values = request.headers().getAll("Content-Length")
        .flatMap { header -> header.split(',') }
        .map(String::trim)
        .filter(String::isNotEmpty)
    if (values.isEmpty()) return -1
    val parsed = values.map { value -> value.toLongOrNull() ?: return null }
    return parsed.first().takeIf { it >= 0 && parsed.all { value -> value == it } }
}
/** Keeps at most [maximumBytes] and drops retained content immediately after the limit is crossed. */
internal class BoundedRequestBodyBuffer(private val maximumBytes: Int) {
    private var buffer = Buffer.buffer(minOf(maximumBytes, 8_192))
    var receivedBytes: Long = 0
        private set
    internal val retainedBytes: Int
        get() = buffer.length()
    private var oversized = false

    init {
        require(maximumBytes > 0) { "Maximum body size must be positive" }
    }

    /** Returns false once this body is oversized. */
    fun append(chunk: Buffer): Boolean {
        val chunkBytes = chunk.length().toLong()
        if (receivedBytes > Long.MAX_VALUE - chunkBytes) {
            oversized = true
        } else {
            receivedBytes += chunkBytes
            if (receivedBytes > maximumBytes.toLong()) oversized = true
        }
        if (oversized) {
            buffer = Buffer.buffer(0)
            return false
        }
        buffer.appendBuffer(chunk)
        return true
    }

    fun finish(): ByteArray? = if (oversized) null else buffer.bytes
}
