package codes.yousef.aether.core.proxy

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for proxy operations.
 * Provides fine-grained control over timeouts, retries, and circuit breaker behavior.
 */
data class ProxyConfig(
    /**
     * Connection timeout - how long to wait for the upstream connection to be established.
     */
    val connectTimeout: Duration = 10.seconds,
    
    /**
     * Request timeout - total time allowed for the proxy operation including response streaming.
     * For long-running inference endpoints, set this to a higher value (e.g., 120 seconds).
     */
    val requestTimeout: Duration = 60.seconds,
    
    /**
     * Idle timeout - maximum time a connection can remain idle during streaming.
     * This prevents hanging connections when upstream stops sending data.
     */
    val idleTimeout: Duration = 30.seconds,
    
    /**
     * Maximum request body size in bytes. Set to -1 for unlimited.
     * Default 10MB.
     */
    val maxRequestBodySize: Long = 10 * 1024 * 1024,
    
    /**
     * Whether to follow redirects from upstream automatically.
     */
    val followRedirects: Boolean = false,
    
    /**
     * Maximum number of redirects to follow if followRedirects is true.
     */
    val maxRedirects: Int = 5,
    
    /**
     * Buffer size for streaming operations in bytes.
     * Larger buffers improve throughput but increase memory usage.
     */
    val streamBufferSize: Int = 16 * 1024,
    
    /**
     * Circuit breaker configuration. Set to null to disable circuit breaker.
     */
    val circuitBreaker: CircuitBreakerConfig? = CircuitBreakerConfig(),
    
    /**
     * Whether to preserve the Host header from the original request.
     * If false, the Host header will be set to the upstream host.
     */
    val preserveHostHeader: Boolean = false,
    
    /**
     * Whether to add X-Forwarded-* headers (X-Forwarded-For, X-Forwarded-Proto, X-Forwarded-Host).
     */
    val addForwardedHeaders: Boolean = true,
    
    /**
     * Headers to remove from the proxied request.
     * By default, hop-by-hop headers are removed.
     */
    val removeRequestHeaders: Set<String> = DEFAULT_HOP_BY_HOP_HEADERS,
    
    /**
     * Headers to remove from the proxied response.
     */
    val removeResponseHeaders: Set<String> = setOf("Transfer-Encoding"),
    
    /**
     * Custom headers to add to every proxied request.
     */
    val additionalRequestHeaders: Map<String, String> = emptyMap(),
    
    /**
     * Custom headers to add to every proxied response.
     */
    val additionalResponseHeaders: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * Hop-by-hop headers that should not be forwarded.
         * Per RFC 2616 Section 13.5.1
         */
        val DEFAULT_HOP_BY_HOP_HEADERS = setOf(
            "Connection",
            "Keep-Alive",
            "Proxy-Authenticate",
            "Proxy-Authorization",
            "TE",
            "Trailers",
            "Transfer-Encoding",
            "Upgrade"
        )
        
        /**
         * Default configuration optimized for general use.
         */
        val Default = ProxyConfig()
        
        /**
         * Configuration optimized for LLM/AI inference endpoints.
         * Higher timeouts for long-running inference, larger buffers for token streaming.
         */
        val LLMInference = ProxyConfig(
            connectTimeout = 30.seconds,
            requestTimeout = 300.seconds,  // 5 minutes for long inference
            idleTimeout = 60.seconds,
            maxRequestBodySize = 50 * 1024 * 1024,  // 50MB for large prompts
            streamBufferSize = 4 * 1024,  // Smaller buffer for real-time SSE
            circuitBreaker = CircuitBreakerConfig(
                failureThreshold = 3,
                resetTimeout = 60.seconds
            )
        )
        
        /**
         * Configuration with circuit breaker disabled.
         */
        val NoCircuitBreaker = ProxyConfig(circuitBreaker = null)
    }
}

/**
 * Configuration for the circuit breaker pattern.
 * Prevents cascading failures by temporarily stopping requests to a failing upstream.
 */
data class CircuitBreakerConfig(
    /**
     * Number of consecutive failures before the circuit opens.
     */
    val failureThreshold: Int = 5,
    
    /**
     * Time to wait before attempting to close the circuit (half-open state).
     */
    val resetTimeout: Duration = 30.seconds,
    
    /**
     * Number of successful requests required in half-open state to close the circuit.
     */
    val successThreshold: Int = 2,
    
    /**
     * Size of the sliding window for failure counting.
     * Only failures within this window count toward the threshold.
     */
    val slidingWindowSize: Int = 10,
    
    /**
     * Duration of the sliding window. Failures older than this are ignored.
     */
    val slidingWindowDuration: Duration = 60.seconds,
    
    /**
     * Exception types that should trigger the circuit breaker.
     * If empty, all proxy-related exceptions trigger it.
     */
    val triggerExceptions: Set<String> = setOf(
        "ProxyConnectionException",
        "ProxyTimeoutException"
    )
)

/**
 * Configuration for an individual proxy request.
 * Allows per-request customization of headers and behavior.
 */
class ProxyRequestConfig @PublishedApi internal constructor() {
    internal val headersToAdd = mutableMapOf<String, String>()
    internal val headersToRemove = mutableSetOf<String>()
    internal var pathRewrite: ((String) -> String)? = null
    internal var queryRewrite: ((String?) -> String?)? = null
    internal var timeoutOverride: Duration? = null
    internal var onUpstreamResponse: (suspend (ProxyUpstreamResponse) -> Unit)? = null
    
    /**
     * Add a header to the proxied request.
     */
    fun header(name: String, value: String) {
        headersToAdd[name] = value
    }
    
    /**
     * Remove a header from the proxied request.
     */
    fun removeHeader(name: String) {
        headersToRemove.add(name)
    }
    
    /**
     * Replace the Authorization header with a different value.
     * Common use case: replace user's token with service-level API key.
     */
    fun authorization(value: String) {
        removeHeader("Authorization")
        header("Authorization", value)
    }
    
    /**
     * Set a bearer token for the Authorization header.
     */
    fun bearerToken(token: String) {
        authorization("Bearer $token")
    }
    
    /**
     * Rewrite the request path before proxying.
     */
    fun rewritePath(transform: (String) -> String) {
        pathRewrite = transform
    }
    
    /**
     * Rewrite the query string before proxying.
     */
    fun rewriteQuery(transform: (String?) -> String?) {
        queryRewrite = transform
    }
    
    /**
     * Override the timeout for this specific request.
     */
    fun timeout(duration: Duration) {
        timeoutOverride = duration
    }
    
    /**
     * Register a callback to be invoked when the upstream response headers are received.
     * Allows inspection of the response before streaming begins.
     * Throw an exception to abort the proxy operation.
     */
    fun onResponse(handler: suspend (ProxyUpstreamResponse) -> Unit) {
        onUpstreamResponse = handler
    }
}

/**
 * Represents the upstream response metadata before streaming.
 */
data class ProxyUpstreamResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val contentType: String?,
    val contentLength: Long?
) {
    /**
     * Check if this is a successful response (2xx status code).
     */
    val isSuccessful: Boolean get() = statusCode in 200..299
    
    /**
     * Check if this is a streaming response (chunked or SSE).
     */
    val isStreaming: Boolean get() = 
        contentType?.contains("text/event-stream") == true ||
        headers["Transfer-Encoding"]?.any { it.contains("chunked", ignoreCase = true) } == true
    
    /**
     * Get the model name from response headers (common in LLM APIs).
     */
    val model: String? get() = headers["X-Model"]?.firstOrNull() 
        ?: headers["x-model"]?.firstOrNull()
}

/**
 * DSL builder for ProxyRequestConfig.
 */
inline fun proxyRequest(block: ProxyRequestConfig.() -> Unit): ProxyRequestConfig {
    return ProxyRequestConfig().apply(block)
}
