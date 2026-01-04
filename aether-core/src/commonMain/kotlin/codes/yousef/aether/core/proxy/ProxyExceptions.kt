package codes.yousef.aether.core.proxy

/**
 * Base exception for all proxy-related errors.
 */
open class ProxyException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Thrown when a connection to the upstream server cannot be established.
 * Results in HTTP 502 Bad Gateway response.
 */
class ProxyConnectionException(
    val upstream: String,
    message: String = "Failed to connect to upstream: $upstream",
    cause: Throwable? = null
) : ProxyException(message, cause)

/**
 * Thrown when the upstream request times out.
 * Results in HTTP 504 Gateway Timeout response.
 */
class ProxyTimeoutException(
    val upstream: String,
    val timeoutType: TimeoutType,
    message: String = "Proxy timeout ($timeoutType) for upstream: $upstream",
    cause: Throwable? = null
) : ProxyException(message, cause) {
    
    enum class TimeoutType {
        /** Connection establishment timeout */
        CONNECT,
        /** Total request timeout */
        REQUEST,
        /** No data received within idle timeout */
        IDLE
    }
}

/**
 * Thrown when the circuit breaker is open and requests are being rejected.
 * Results in HTTP 503 Service Unavailable response.
 */
class ProxyCircuitOpenException(
    val upstream: String,
    val circuitName: String,
    message: String = "Circuit breaker open for upstream: $upstream"
) : ProxyException(message)

/**
 * Thrown when the request body exceeds the maximum allowed size.
 * Results in HTTP 413 Payload Too Large response.
 */
class ProxyPayloadTooLargeException(
    val maxSize: Long,
    val actualSize: Long,
    message: String = "Request body size ($actualSize bytes) exceeds maximum ($maxSize bytes)"
) : ProxyException(message)

/**
 * Thrown when an SSL/TLS error occurs during the proxy operation.
 * Results in HTTP 502 Bad Gateway response.
 */
class ProxySslException(
    val upstream: String,
    message: String = "SSL/TLS error connecting to upstream: $upstream",
    cause: Throwable? = null
) : ProxyException(message, cause)

/**
 * Thrown when the upstream returns an invalid response.
 * Results in HTTP 502 Bad Gateway response.
 */
class ProxyInvalidResponseException(
    val upstream: String,
    message: String = "Invalid response from upstream: $upstream",
    cause: Throwable? = null
) : ProxyException(message, cause)

/**
 * Thrown when too many redirects are encountered.
 * Results in HTTP 502 Bad Gateway response.
 */
class ProxyTooManyRedirectsException(
    val upstream: String,
    val redirectCount: Int,
    val maxRedirects: Int,
    message: String = "Too many redirects ($redirectCount > $maxRedirects) for upstream: $upstream"
) : ProxyException(message)

/**
 * Thrown when the proxy operation is cancelled (e.g., client disconnects).
 */
class ProxyCancelledException(
    val upstream: String,
    message: String = "Proxy operation cancelled for upstream: $upstream",
    cause: Throwable? = null
) : ProxyException(message, cause)
