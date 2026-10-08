package codes.yousef.aether.core.security

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.pipeline.Middleware

/** Exact-origin CORS policy for credentialed private APIs. Wildcard origins are never accepted. */
data class ExactCorsConfig(
    val allowedOrigins: Set<String>,
    val allowedMethods: Set<HttpMethod>,
    val allowedHeaders: Set<String> = emptySet(),
    val exposedHeaders: Set<String> = emptySet(),
    val allowCredentials: Boolean = true,
    val preflightMaxAgeSeconds: Long = 600,
    val requireOriginForUnsafeMethods: Boolean = true
) {
    init {
        require(allowedOrigins.isNotEmpty() && "*" !in allowedOrigins) {
            "CORS requires a nonempty exact-origin allowlist"
        }
        allowedOrigins.forEach { origin ->
            require(isCanonicalOrigin(origin)) {
                "CORS origins must be canonical HTTP(S) origins"
            }
        }
        require(allowedMethods.isNotEmpty() && HttpMethod.OPTIONS !in allowedMethods) {
            "CORS application methods must be nonempty and omit OPTIONS"
        }
        (allowedHeaders + exposedHeaders).forEach { requireHttpToken(it, "CORS header") }
        require(preflightMaxAgeSeconds in 0..86_400) { "CORS preflight max age must be 0..86400 seconds" }
    }
}

class ExactCorsMiddleware(private val config: ExactCorsConfig) {
    private val allowedHeaders = config.allowedHeaders.mapTo(mutableSetOf()) { it.lowercase() }

    fun asMiddleware(): Middleware = middleware@{ exchange, next ->
        val origins = exchange.request.headers.getAll("Origin")
        if (origins.isEmpty()) {
            if (config.requireOriginForUnsafeMethods && exchange.request.method in UNSAFE_METHODS) {
                reject(exchange)
            } else {
                next()
            }
            return@middleware
        }
        val origin = origins.singleOrNull()
        if (origin == null || origin !in config.allowedOrigins) {
            reject(exchange)
            return@middleware
        }

        val requestedMethod = exchange.request.headers.getAll("Access-Control-Request-Method")
        if (exchange.request.method != HttpMethod.OPTIONS && requestedMethod.isNotEmpty()) {
            reject(exchange)
            return@middleware
        }
        val isPreflight = exchange.request.method == HttpMethod.OPTIONS && requestedMethod.isNotEmpty()
        if (!isPreflight) {
            if (exchange.request.method !in config.allowedMethods) {
                reject(exchange)
                return@middleware
            }
            addActualResponseHeaders(exchange, origin)
            next()
            return@middleware
        }

        val method = requestedMethod.singleOrNull()?.let(::parseMethod)
        val requestedHeaders = exchange.request.headers.getAll("Access-Control-Request-Headers")
        if (method == null || method !in config.allowedMethods || requestedHeaders.size > 1) {
            reject(exchange)
            return@middleware
        }
        val headers = requestedHeaders.singleOrNull()
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (headers.any { it !in allowedHeaders }) {
            reject(exchange)
            return@middleware
        }
        addActualResponseHeaders(exchange, origin)

        exchange.response.setHeader(
            "Access-Control-Allow-Methods",
            config.allowedMethods.map { it.name }.sorted().joinToString(", ")
        )
        if (config.allowedHeaders.isNotEmpty()) {
            exchange.response.setHeader("Access-Control-Allow-Headers", config.allowedHeaders.sorted().joinToString(", "))
        }
        exchange.response.setHeader("Access-Control-Max-Age", config.preflightMaxAgeSeconds.toString())
        exchange.response.statusCode = 204
        exchange.response.end()
    }

    private fun addActualResponseHeaders(exchange: Exchange, origin: String) {
        exchange.response.setHeader("Access-Control-Allow-Origin", origin)
        exchange.response.setHeader("Vary", "Origin")
        if (config.allowCredentials) exchange.response.setHeader("Access-Control-Allow-Credentials", "true")
        if (config.exposedHeaders.isNotEmpty()) {
            exchange.response.setHeader("Access-Control-Expose-Headers", config.exposedHeaders.sorted().joinToString(", "))
        }
    }

    private suspend fun reject(exchange: Exchange) {
        exchange.response.statusCode = 403
        exchange.response.setHeader("Content-Type", "text/plain; charset=utf-8")
        exchange.response.write("cors_denied")
        exchange.response.setHeader("Cache-Control", "no-store")
        exchange.response.end()
    }

    private fun parseMethod(value: String): HttpMethod? =
        HttpMethod.entries.singleOrNull { it.name == value }
}

private val CANONICAL_ORIGIN = Regex(
    "^(https?)://(\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?" +
        "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*)(?::([0-9]{1,5}))?$"
)

private fun isCanonicalOrigin(value: String): Boolean {
    if (value != value.trim() || '@' in value || '?' in value || '#' in value) return false
    val match = CANONICAL_ORIGIN.matchEntire(value) ?: return false
    val port = match.groups[3]?.value?.toIntOrNull() ?: return match.groups[3] == null
    return port in 1..65_535
}

private val UNSAFE_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)

private val HTTP_TOKEN_PATTERN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
private fun requireHttpToken(value: String, label: String) {
    require(HTTP_TOKEN_PATTERN.matches(value)) { "$label must be an HTTP token" }
}
