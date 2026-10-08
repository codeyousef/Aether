package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.Attributes
import codes.yousef.aether.core.Exchange
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/** Controls whether request diagnostics may retain development-only detail. */
enum class DiagnosticsProfile {
    PRIVATE_PRODUCTION,
    DEVELOPMENT
}

/** Safe, finite request metadata used by recovery and call logging. */
data class RequestDiagnosticsPolicy(
    val requestIdHeader: String = "X-Request-Id",
    val minimumRequestIdLength: Int = 8,
    val maximumRequestIdLength: Int = 64,
    val includeRouteTemplate: Boolean = true,
    val maximumRouteTemplateLength: Int = 256,
    val unmatchedRouteLabel: String = "<unmatched>"
) {
    init {
        require(requestIdHeader.isNotBlank()) { "Request ID header must not be blank" }
        require(minimumRequestIdLength > 0) { "Minimum request ID length must be positive" }
        require(maximumRequestIdLength >= minimumRequestIdLength) {
            "Maximum request ID length must not be smaller than the minimum"
        }
        require(maximumRequestIdLength <= 128) { "Request IDs must be at most 128 characters" }
        require(unmatchedRouteLabel.isNotBlank()) { "Unmatched route label must not be blank" }
        require(maximumRouteTemplateLength in 1..1_024) {
            "Maximum route template length must be between 1 and 1024"
        }
        require(unmatchedRouteLabel.length <= maximumRouteTemplateLength) {
            "Unmatched route label exceeds maximum route template length"
        }
        require(unmatchedRouteLabel.all(::isSafeLogCharacter)) {
            "Unmatched route label contains unsafe log characters"
        }
    }
}

/** Fixed public error vocabulary. Messages never come from exception text. */
enum class ApiErrorKind(
    val statusCode: Int,
    val code: String,
    val publicMessage: String,
    val retryable: Boolean
) {
    UNAUTHENTICATED(401, "UNAUTHENTICATED", "Authentication is required", false),
    BAD_REQUEST(400, "BAD_REQUEST", "Request is malformed", false),
    PERMISSION_DENIED(403, "PERMISSION_DENIED", "Permission denied", false),
    NOT_FOUND(404, "NOT_FOUND", "Resource not found", false),
    REQUEST_TIMEOUT(408, "REQUEST_TIMEOUT", "Request body timed out", true),
    CONFLICT(409, "CONFLICT", "Request conflicts with current state", false),
    GONE(410, "GONE", "Resource is no longer available", false),
    PAYLOAD_TOO_LARGE(413, "PAYLOAD_TOO_LARGE", "Request body is too large", false),
    VALIDATION_FAILED(422, "VALIDATION_FAILED", "Request validation failed", false),
    UNSUPPORTED_FORMAT(422, "UNSUPPORTED_FORMAT", "Request format is unsupported", false),
    RATE_LIMITED(429, "RATE_LIMITED", "Request rate limit exceeded", true),
    DEPENDENCY_UNAVAILABLE(503, "DEPENDENCY_UNAVAILABLE", "A required service is unavailable", true),
    INTERNAL(500, "INTERNAL", "Internal server error", false)
}

/** Typed application failure. The public message is selected exclusively by [kind]. */
class ApiException(
    val kind: ApiErrorKind,
    cause: Throwable? = null
) : Exception(kind.code, cause)

/** Bounded mapping from exception chains to the fixed public error vocabulary. */
data class ApiErrorPolicy(
    val maximumCauseDepth: Int = 8,
    val diagnostics: RequestDiagnosticsPolicy = RequestDiagnosticsPolicy()
) {
    init {
        require(maximumCauseDepth in 1..32) { "Maximum cause depth must be between 1 and 32" }
    }

    fun classify(throwable: Throwable): ApiErrorKind {
        if (throwable is CancellationException) throw throwable
        if (throwable is Error) throw throwable

        var current: Throwable? = throwable
        val visited = mutableSetOf<Throwable>()
        repeat(maximumCauseDepth) {
            val candidate = current ?: return ApiErrorKind.INTERNAL
            if (!visited.add(candidate)) return ApiErrorKind.INTERNAL
            if (candidate is CancellationException) throw candidate
            if (candidate is Error) throw candidate
            if (candidate is ApiException) return candidate.kind
            current = candidate.cause
        }
        return ApiErrorKind.INTERNAL
    }
}

object RequestDiagnostics {
    val RequestIdKey = Attributes.key<String>("AetherRequestId")
    val RouteTemplateKey = Attributes.key<String>("AetherRouteTemplate")
    val ErrorCategoryKey = Attributes.key<String>("AetherErrorCategory")
}

/** Selects a bounded caller ID when valid, otherwise creates a new opaque 128-bit ID. */
fun selectRequestId(
    supplied: String?,
    policy: RequestDiagnosticsPolicy = RequestDiagnosticsPolicy()
): String {
    if (supplied != null && supplied.length in policy.minimumRequestIdLength..policy.maximumRequestIdLength &&
        supplied.all(::isRequestIdCharacter)
    ) {
        return supplied
    }
    val bytes = Random.Default.nextBytes(16)
    return buildString(32) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
}

/** Installs the request ID before downstream middleware and echoes it on the response. */
fun establishRequestDiagnostics(
    exchange: Exchange,
    policy: RequestDiagnosticsPolicy = RequestDiagnosticsPolicy()
): String {
    val requestId = exchange.attributes.getOrPut(RequestDiagnostics.RequestIdKey) {
        selectRequestId(exchange.request.headers[policy.requestIdHeader], policy)
    }
    exchange.response.setHeader(policy.requestIdHeader, requestId)
    return requestId
}

/** Canonical safe JSON response. Every interpolated value is framework-controlled or validated. */
fun encodeApiError(kind: ApiErrorKind, requestId: String): String =
    "{\"error\":{\"code\":\"${kind.code}\",\"message\":\"${kind.publicMessage}\"," +
        "\"retryable\":${kind.retryable}},\"request_id\":\"$requestId\"}"

suspend fun respondWithApiError(
    exchange: Exchange,
    kind: ApiErrorKind,
    diagnostics: RequestDiagnosticsPolicy = RequestDiagnosticsPolicy()
) {
    val requestId = establishRequestDiagnostics(exchange, diagnostics)
    exchange.attributes.put(RequestDiagnostics.ErrorCategoryKey, kind.code)
    if (exchange.response.isCommitted) return
    exchange.response.statusCode = kind.statusCode
    exchange.response.setHeader("Content-Type", "application/json; charset=utf-8")
    exchange.response.setHeader("Cache-Control", "no-store")
    exchange.response.write(encodeApiError(kind, requestId))
    exchange.response.end()
}

internal fun sanitizeLogToken(value: String, maximumLength: Int): String = buildString(
    minOf(value.length, maximumLength)
) {
    value.take(maximumLength).forEach { character ->
        append(if (isSafeLogCharacter(character)) character else '_')
    }
}

private fun isSafeLogCharacter(character: Char): Boolean = character in '!'..'~'

private fun isRequestIdCharacter(character: Char): Boolean =
    character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
        character == '-' || character == '_' || character == '.'

private const val HEX = "0123456789abcdef"
