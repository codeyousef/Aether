package codes.yousef.aether.core.proxy

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlin.time.Duration

/**
 * Extension function to proxy the current request to an upstream URL.
 * 
 * This is the most flexible API for proxying - it allows pre/post processing
 * around the proxy operation.
 * 
 * Example:
 * ```kotlin
 * router {
 *     post("/chat/completions") { exchange ->
 *         exchange.proxyTo("http://litellm:4000/chat/completions") {
 *             bearerToken(System.getenv("LITELLM_API_KEY"))
 *             timeout(120.seconds)
 *         }
 *     }
 * }
 * ```
 * 
 * @param url The upstream URL to proxy to
 * @param config Optional proxy configuration (defaults to [ProxyConfig.Default])
 * @param configure Optional request configuration block
 */
suspend fun Exchange.proxyTo(
    url: String,
    config: ProxyConfig = ProxyConfig.Default,
    configure: ProxyRequestConfig.() -> Unit = {}
) {
    val requestConfig = ProxyRequestConfig().apply(configure)
    
    // Get or create the circuit breaker for this upstream
    val circuitBreaker = config.circuitBreaker?.let { cbConfig ->
        CircuitBreakerRegistry.getOrCreate(extractHost(url), cbConfig)
    }
    
    // Check circuit breaker state
    circuitBreaker?.let { cb ->
        if (!cb.allowRequest()) {
            throw ProxyCircuitOpenException(url, cb.name)
        }
    }
    
    val client = StreamingProxyClient(config)
    
    try {
        // Build the proxy request
        val proxyRequest = buildProxyRequest(this, url, config, requestConfig)
        
        // Execute the proxy request
        val result = client.execute(proxyRequest)
        
        // Invoke the response callback if configured
        requestConfig.onUpstreamResponse?.invoke(result.toUpstreamResponse())
        
        // Forward the response to the client
        forwardResponse(this, result, config)
        
        // Record success for circuit breaker
        circuitBreaker?.recordSuccess()
        
    } catch (e: ProxyException) {
        // Record failure for circuit breaker
        circuitBreaker?.recordFailure(e)
        throw e
    } finally {
        client.close()
    }
}

/**
 * Proxy the request and return the upstream response for inspection.
 * The response must be manually forwarded using [ProxyResponse.forward].
 * 
 * Example:
 * ```kotlin
 * router {
 *     post("/chat/completions") { exchange ->
 *         val response = exchange.proxyRequest("http://litellm:4000/chat/completions") {
 *             bearerToken(System.getenv("LITELLM_API_KEY"))
 *         }
 *         
 *         // Log or process before forwarding
 *         logRequest(exchange, response.statusCode)
 *         
 *         // Forward the response (streams automatically)
 *         response.forward(exchange)
 *     }
 * }
 * ```
 */
suspend fun Exchange.proxyRequest(
    url: String,
    config: ProxyConfig = ProxyConfig.Default,
    configure: ProxyRequestConfig.() -> Unit = {}
): ProxyResponse {
    val requestConfig = ProxyRequestConfig().apply(configure)
    
    // Get or create the circuit breaker for this upstream
    val circuitBreaker = config.circuitBreaker?.let { cbConfig ->
        CircuitBreakerRegistry.getOrCreate(extractHost(url), cbConfig)
    }
    
    // Check circuit breaker state
    circuitBreaker?.let { cb ->
        if (!cb.allowRequest()) {
            throw ProxyCircuitOpenException(url, cb.name)
        }
    }
    
    val client = StreamingProxyClient(config)
    
    try {
        val proxyRequest = buildProxyRequest(this, url, config, requestConfig)
        val result = client.execute(proxyRequest)
        
        // Invoke the response callback if configured
        requestConfig.onUpstreamResponse?.invoke(result.toUpstreamResponse())
        
        return ProxyResponse(
            result = result,
            client = client,
            config = config,
            circuitBreaker = circuitBreaker
        )
    } catch (e: ProxyException) {
        circuitBreaker?.recordFailure(e)
        client.close()
        throw e
    }
}

/**
 * Represents a proxy response that can be inspected before forwarding.
 */
class ProxyResponse internal constructor(
    private val result: ProxyResult,
    private val client: StreamingProxyClient,
    private val config: ProxyConfig,
    private val circuitBreaker: CircuitBreaker?
) {
    /** HTTP status code from upstream */
    val statusCode: Int get() = result.statusCode
    
    /** Status message from upstream */
    val statusMessage: String? get() = result.statusMessage
    
    /** Response headers from upstream */
    val headers: Map<String, List<String>> get() = result.headers
    
    /** Content-Type header */
    val contentType: String? get() = result.contentType
    
    /** Content-Length if known */
    val contentLength: Long get() = result.contentLength
    
    /** Whether this is a streaming response */
    val isStreaming: Boolean get() = result.isStreaming
    
    /** Whether this is a successful response (2xx) */
    val isSuccessful: Boolean get() = result.isSuccessful
    
    /** Get a specific header value */
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.firstOrNull()
    
    /**
     * Forward the response to the client.
     * This streams the response body without buffering.
     */
    suspend fun forward(exchange: Exchange) {
        try {
            forwardResponse(exchange, result, config)
            circuitBreaker?.recordSuccess()
        } catch (e: Exception) {
            circuitBreaker?.recordFailure(e)
            throw e
        } finally {
            client.close()
        }
    }
    
    /**
     * Consume the response body as a flow of chunks.
     * Use this for custom processing instead of forwarding.
     */
    fun bodyFlow(): Flow<ByteArray> = result.bodyFlow
    
    /**
     * Close without forwarding (e.g., if you handled the response differently).
     */
    fun close() {
        client.close()
    }
}

/**
 * Build the proxy request from the exchange.
 */
private suspend fun buildProxyRequest(
    exchange: Exchange,
    url: String,
    config: ProxyConfig,
    requestConfig: ProxyRequestConfig
): StreamingProxyRequest {
    // Build the target URL with path/query rewriting
    val targetUrl = buildTargetUrl(url, exchange, requestConfig)
    
    // Build headers
    val headers = buildProxyHeaders(exchange, config, requestConfig)
    
    // Get request body
    val bodyBytes = if (exchange.request.method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)) {
        exchange.request.bodyBytes()
    } else {
        null
    }
    
    // Create body flow if there's a body
    val bodyFlow: Flow<ByteArray>? = bodyBytes?.let { bytes ->
        // For now, emit the entire body as a single chunk
        // Future optimization: stream the body in chunks
        flow { emit(bytes) }
    }
    
    return StreamingProxyRequest(
        method = exchange.request.method.name,
        url = targetUrl,
        headers = headers,
        bodyFlow = bodyFlow,
        bodySize = bodyBytes?.size?.toLong(),
        timeout = requestConfig.timeoutOverride
    )
}

/**
 * Build the target URL with path and query rewriting.
 */
private fun buildTargetUrl(
    baseUrl: String,
    exchange: Exchange,
    requestConfig: ProxyRequestConfig
): String {
    return buildString {
        // Parse the URL to separate scheme://host:port from path
        val urlParts = parseUrlComponents(baseUrl)
        append(urlParts.base)
        
        // Capture the path rewriter to avoid smart cast issues
        val pathRewriter = requestConfig.pathRewrite
        
        // Determine the path to use
        val path = when {
            // If path rewriter is configured, use its result
            pathRewriter != null -> {
                pathRewriter(exchange.request.path)
            }
            // If base URL already has a path, use that (don't add request path)
            urlParts.path.isNotEmpty() -> {
                urlParts.path
            }
            // Otherwise, use the original request path
            else -> {
                exchange.request.path
            }
        }
        
        if (path.isNotEmpty() && !path.startsWith("/")) {
            append("/")
        }
        append(path)
        
        // Apply query rewriting if configured, otherwise preserve original
        val query = requestConfig.queryRewrite?.invoke(exchange.request.query) ?: exchange.request.query
        if (!query.isNullOrEmpty()) {
            append("?")
            append(query)
        }
    }
}

/**
 * Parse URL into base (scheme://host:port) and path components.
 */
private data class UrlComponents(val base: String, val path: String)

private fun parseUrlComponents(url: String): UrlComponents {
    // Find the scheme separator
    val schemeEnd = url.indexOf("://")
    if (schemeEnd == -1) {
        // No scheme, treat whole thing as path
        return UrlComponents("", url)
    }
    
    // Find the start of the path after the host
    val hostStart = schemeEnd + 3
    val pathStart = url.indexOf('/', hostStart)
    
    return if (pathStart == -1) {
        // No path in URL
        UrlComponents(url.trimEnd('/'), "")
    } else {
        // Has path
        UrlComponents(url.substring(0, pathStart), url.substring(pathStart))
    }
}

/**
 * Build the headers for the proxy request.
 */
private fun buildProxyHeaders(
    exchange: Exchange,
    config: ProxyConfig,
    requestConfig: ProxyRequestConfig
): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    
    // Copy headers from original request (excluding hop-by-hop and configured removals)
    val excludeHeaders = config.removeRequestHeaders + requestConfig.headersToRemove
    exchange.request.headers.entries().forEach { (name, values) ->
        if (!excludeHeaders.any { it.equals(name, ignoreCase = true) }) {
            // Take the first value for simplicity
            headers[name] = values.firstOrNull() ?: return@forEach
        }
    }
    
    // Modify Host header if not preserving
    if (!config.preserveHostHeader) {
        headers.remove("Host")
    }
    
    // Add X-Forwarded-* headers if configured
    if (config.addForwardedHeaders) {
        // X-Forwarded-For: append client IP
        val existingForwardedFor = exchange.request.headers["X-Forwarded-For"]
        val clientIp = exchange.attributes.get(ClientIpKey) ?: "unknown"
        headers["X-Forwarded-For"] = if (existingForwardedFor != null) {
            "$existingForwardedFor, $clientIp"
        } else {
            clientIp
        }
        
        // X-Forwarded-Proto
        val proto = exchange.attributes.get(ProtoKey) ?: "http"
        headers["X-Forwarded-Proto"] = proto
        
        // X-Forwarded-Host
        val host = exchange.request.headers["Host"]
        if (host != null) {
            headers["X-Forwarded-Host"] = host
        }
    }
    
    // Add additional headers from config
    headers.putAll(config.additionalRequestHeaders)
    
    // Add/override headers from request config
    headers.putAll(requestConfig.headersToAdd)
    
    return headers
}

/**
 * Forward the proxy result to the client response.
 */
private suspend fun forwardResponse(
    exchange: Exchange,
    result: ProxyResult,
    config: ProxyConfig
) {
    // Set status code
    exchange.response.statusCode = result.statusCode
    result.statusMessage?.let { exchange.response.statusMessage = it }
    
    // Forward headers (excluding hop-by-hop and configured removals)
    val excludeHeaders = config.removeResponseHeaders + ProxyConfig.DEFAULT_HOP_BY_HOP_HEADERS
    result.headers.forEach { (name, values) ->
        if (!excludeHeaders.any { it.equals(name, ignoreCase = true) }) {
            values.forEach { value ->
                exchange.response.addHeader(name, value)
            }
        }
    }
    
    // Add additional response headers from config
    config.additionalResponseHeaders.forEach { (name, value) ->
        exchange.response.setHeader(name, value)
    }
    
    // Stream the response body
    result.bodyFlow.collect { chunk ->
        exchange.response.write(chunk)
    }
    
    // End the response
    exchange.response.end()
}

/**
 * Extract the host from a URL for circuit breaker identification.
 */
private fun extractHost(url: String): String {
    return try {
        val withoutProtocol = url.substringAfter("://")
        withoutProtocol.substringBefore("/").substringBefore("?")
    } catch (e: Exception) {
        url
    }
}

// Attribute keys for proxy metadata
private val ClientIpKey = codes.yousef.aether.core.Attributes.key<String>("proxy.clientIp")
private val ProtoKey = codes.yousef.aether.core.Attributes.key<String>("proxy.proto")

/**
 * Set the client IP address for X-Forwarded-For header.
 */
fun Exchange.setClientIp(ip: String) {
    attributes.put(ClientIpKey, ip)
}

/**
 * Set the protocol (http/https) for X-Forwarded-Proto header.
 */
fun Exchange.setProto(proto: String) {
    attributes.put(ProtoKey, proto)
}
