package codes.yousef.aether.core.proxy

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.Middleware
import codes.yousef.aether.core.pipeline.Pipeline
import kotlin.time.Duration

/**
 * Proxy middleware that automatically forwards requests matching a path prefix to an upstream server.
 * 
 * Example:
 * ```kotlin
 * pipeline.installProxy {
 *     pathPrefix = "/api/v1/"
 *     upstream = "http://backend:8080"
 *     rewritePath { path -> path.removePrefix("/api/v1") }
 *     timeout = 60.seconds
 *     
 *     // Add authentication to all proxied requests
 *     headers {
 *         set("Authorization", "Bearer ${System.getenv("API_KEY")}")
 *     }
 * }
 * ```
 */
class ProxyMiddleware(
    private val config: ProxyMiddlewareConfig
) {
    /**
     * Create the middleware function.
     */
    fun middleware(): Middleware = { exchange, next ->
        val path = exchange.request.path
        
        // Check if the path matches our prefix
        if (shouldProxy(path)) {
            proxyRequest(exchange)
        } else {
            // Not our path, pass to next middleware
            next()
        }
    }
    
    /**
     * Check if the request path should be proxied.
     */
    private fun shouldProxy(path: String): Boolean {
        val prefix = config.pathPrefix
        val matcher = config.pathMatcher
        return when {
            prefix != null -> path.startsWith(prefix)
            matcher != null -> matcher.invoke(path)
            else -> true  // Proxy everything if no matcher configured
        }
    }
    
    /**
     * Execute the proxy operation.
     */
    private suspend fun proxyRequest(exchange: Exchange) {
        // Calculate the stripped path for use in path rewriting
        val strippedPath = getStrippedPath(exchange)
        
        // Build target URL - if we have a path rewriter, don't include path in URL
        // because the rewriter will provide the final path
        val targetUrl = buildTargetUrl(exchange, hasPathRewriter = config.pathRewriter != null)
        
        exchange.proxyTo(targetUrl, config.proxyConfig) {
            // Apply path rewriting - pass the STRIPPED path to the rewriter
            config.pathRewriter?.let { rewriter ->
                rewritePath { rewriter(strippedPath) }
            }
            
            // Apply query rewriting
            config.queryRewriter?.let { rewriter ->
                rewriteQuery { rewriter(it) }
            }
            
            // Apply header modifications
            config.headerModifier?.invoke(this, exchange)
            
            // Apply timeout override
            config.timeout?.let { timeout(it) }
            
            // Apply additional configuration
            config.requestConfigurer?.invoke(this, exchange)
        }
    }
    
    /**
     * Get the path with prefix stripped (if configured).
     */
    private fun getStrippedPath(exchange: Exchange): String {
        val prefix = config.pathPrefix
        return if (prefix != null && config.stripPrefix) {
            exchange.request.path.removePrefix(prefix.trimEnd('/'))
        } else {
            exchange.request.path
        }
    }
    
    /**
     * Build the target URL for the upstream request.
     */
    private fun buildTargetUrl(exchange: Exchange, hasPathRewriter: Boolean): String {
        val upstream = config.upstream.trimEnd('/')
        
        // If we have a path rewriter, return just the base URL
        // The rewriter will provide the path
        if (hasPathRewriter) {
            return upstream
        }
        
        // Otherwise, build URL with path included
        val path = getStrippedPath(exchange)
        
        return if (config.includePath) {
            "$upstream$path"
        } else {
            upstream
        }
    }
}

/**
 * Configuration for ProxyMiddleware.
 */
class ProxyMiddlewareConfig {
    /** Base URL of the upstream server (e.g., "http://backend:8080") */
    var upstream: String = ""
    
    /** Path prefix to match for proxying (e.g., "/api/v1/") */
    var pathPrefix: String? = null
    
    /** Custom path matcher function */
    var pathMatcher: ((String) -> Boolean)? = null
    
    /** Whether to strip the path prefix from the proxied request */
    var stripPrefix: Boolean = true
    
    /** Whether to include the request path in the upstream URL */
    var includePath: Boolean = true
    
    /** Path rewriting function */
    var pathRewriter: ((String) -> String)? = null
    
    /** Query string rewriting function */
    var queryRewriter: ((String?) -> String?)? = null
    
    /** Header modification function */
    var headerModifier: (ProxyRequestConfig.(Exchange) -> Unit)? = null
    
    /** Additional request configuration */
    var requestConfigurer: (ProxyRequestConfig.(Exchange) -> Unit)? = null
    
    /** Request timeout override */
    var timeout: Duration? = null
    
    /** Underlying proxy configuration */
    var proxyConfig: ProxyConfig = ProxyConfig.Default
    
    /**
     * Configure path rewriting using a DSL.
     */
    fun rewritePath(transform: (String) -> String) {
        pathRewriter = transform
    }
    
    /**
     * Configure query rewriting using a DSL.
     */
    fun rewriteQuery(transform: (String?) -> String?) {
        queryRewriter = transform
    }
    
    /**
     * Configure header modifications using a DSL.
     */
    fun headers(block: ProxyRequestConfig.(Exchange) -> Unit) {
        headerModifier = block
    }
    
    /**
     * Configure the underlying proxy settings.
     */
    fun proxy(block: ProxyConfig.() -> ProxyConfig) {
        proxyConfig = ProxyConfig.Default.block()
    }
    
    /**
     * Configure additional request settings.
     */
    fun configure(block: ProxyRequestConfig.(Exchange) -> Unit) {
        requestConfigurer = block
    }
}

/**
 * Install the proxy middleware into the pipeline.
 */
fun Pipeline.installProxy(configure: ProxyMiddlewareConfig.() -> Unit) {
    val config = ProxyMiddlewareConfig().apply(configure)
    require(config.upstream.isNotBlank()) { "Upstream URL must be configured" }
    
    val middleware = ProxyMiddleware(config)
    use(middleware.middleware())
}

/**
 * Create a proxy middleware with the given configuration.
 */
fun proxyMiddleware(configure: ProxyMiddlewareConfig.() -> Unit): Middleware {
    val config = ProxyMiddlewareConfig().apply(configure)
    require(config.upstream.isNotBlank()) { "Upstream URL must be configured" }
    
    return ProxyMiddleware(config).middleware()
}

/**
 * Recovery middleware integration - handle proxy exceptions appropriately.
 */
fun codes.yousef.aether.core.pipeline.Recovery.handleProxyExceptions() {
    handleByName("ProxyConnectionException") { exchange, throwable ->
        exchange.response.statusCode = 502
        exchange.response.setHeader("Content-Type", "text/plain; charset=utf-8")
        exchange.response.write("Bad Gateway: Unable to connect to upstream server")
        exchange.response.end()
    }
    
    handleByName("ProxyTimeoutException") { exchange, throwable ->
        exchange.response.statusCode = 504
        exchange.response.setHeader("Content-Type", "text/plain; charset=utf-8")
        exchange.response.write("Gateway Timeout: Upstream server did not respond in time")
        exchange.response.end()
    }
    
    handleByName("ProxyCircuitOpenException") { exchange, throwable ->
        exchange.response.statusCode = 503
        exchange.response.setHeader("Content-Type", "text/plain; charset=utf-8")
        exchange.response.setHeader("Retry-After", "30")
        exchange.response.write("Service Unavailable: Upstream server is temporarily unavailable")
        exchange.response.end()
    }
    
    handleByName("ProxyPayloadTooLargeException") { exchange, throwable ->
        exchange.response.statusCode = 413
        exchange.response.setHeader("Content-Type", "text/plain; charset=utf-8")
        exchange.response.write("Payload Too Large")
        exchange.response.end()
    }
    
    handleByName("ProxySslException") { exchange, throwable ->
        exchange.response.statusCode = 502
        exchange.response.setHeader("Content-Type", "text/plain; charset=utf-8")
        exchange.response.write("Bad Gateway: SSL/TLS error connecting to upstream")
        exchange.response.end()
    }
}
