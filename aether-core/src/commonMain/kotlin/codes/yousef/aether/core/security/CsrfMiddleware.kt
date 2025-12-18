package codes.yousef.aether.core.security

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.pipeline.Middleware
import codes.yousef.aether.core.session.Session
import codes.yousef.aether.core.session.SessionAttributeKey
import codes.yousef.aether.core.session.generateSecureSessionId

/**
 * Configuration for CSRF protection.
 */
data class CsrfConfig(
    /**
     * Name of the session key where the CSRF token is stored.
     */
    val sessionKey: String = "_csrf_token",

    /**
     * Name of the HTTP header containing the CSRF token.
     */
    val headerName: String = "X-CSRF-Token",

    /**
     * Name of the form field containing the CSRF token.
     */
    val formFieldName: String = "_csrf",

    /**
     * Name of the query parameter containing the CSRF token (optional).
     */
    val queryParamName: String = "_csrf",

    /**
     * HTTP methods that require CSRF validation.
     */
    val protectedMethods: Set<HttpMethod> = setOf(
        HttpMethod.POST,
        HttpMethod.PUT,
        HttpMethod.DELETE,
        HttpMethod.PATCH
    ),

    /**
     * Paths to exclude from CSRF protection (e.g., API endpoints using other auth).
     */
    val excludedPaths: Set<String> = emptySet(),

    /**
     * Path prefixes to exclude from CSRF protection.
     */
    val excludedPathPrefixes: Set<String> = emptySet(),

    /**
     * Length of the CSRF token in bytes.
     */
    val tokenLength: Int = 32,

    /**
     * Error message to return when CSRF validation fails.
     */
    val errorMessage: String = "CSRF token validation failed",

    /**
     * HTTP status code to return when CSRF validation fails.
     */
    val errorStatusCode: Int = 403,

    /**
     * Whether to generate a new token on each request (double-submit cookie pattern).
     */
    val rotateTokenOnRequest: Boolean = false
)

/**
 * Attribute key for accessing the CSRF token from an Exchange.
 */
val CsrfTokenAttributeKey = AttributeKey<String>("aether.csrf.token", String::class)

/**
 * CSRF protection middleware.
 *
 * This middleware:
 * 1. Generates and stores a CSRF token in the session
 * 2. Validates CSRF tokens on protected HTTP methods
 * 3. Makes the token available via exchange attributes
 */
class CsrfMiddleware(
    private val config: CsrfConfig = CsrfConfig()
) {
    /**
     * Create the middleware function.
     */
    fun asMiddleware(): Middleware = { exchange, next ->
        val session = exchange.attributes.get(SessionAttributeKey)
            ?: throw IllegalStateException("CSRF middleware requires session middleware to be installed first")

        // Get or generate CSRF token
        var token = session.getString(config.sessionKey)
        if (token == null || config.rotateTokenOnRequest) {
            token = generateSecureSessionId(config.tokenLength)
            session.set(config.sessionKey, token)
        }

        // Store token in attributes for use in templates
        exchange.attributes.put(CsrfTokenAttributeKey, token)

        // Check if this request needs CSRF validation
        if (shouldValidateCsrf(exchange)) {
            val requestToken = extractToken(exchange)
            if (requestToken == null || !secureCompare(token, requestToken)) {
                exchange.respond(config.errorStatusCode, config.errorMessage)
            } else {
                next()
            }
        } else {
            next()
        }
    }

    private fun shouldValidateCsrf(exchange: Exchange): Boolean {
        // Check if method requires validation
        if (exchange.request.method !in config.protectedMethods) {
            return false
        }

        val path = exchange.request.path

        // Check excluded paths
        if (path in config.excludedPaths) {
            return false
        }

        // Check excluded path prefixes
        for (prefix in config.excludedPathPrefixes) {
            if (path.startsWith(prefix)) {
                return false
            }
        }

        return true
    }

    private suspend fun extractToken(exchange: Exchange): String? {
        // Try header first
        val headerToken = exchange.request.headers.get(config.headerName)
        if (headerToken != null) {
            return headerToken
        }

        // Try query parameter
        val queryToken = exchange.request.queryParameter(config.queryParamName)
        if (queryToken != null) {
            return queryToken
        }

        // Try form field (requires body parsing)
        val contentType = exchange.request.headers.get("Content-Type") ?: ""
        if (contentType.contains("application/x-www-form-urlencoded")) {
            val body = exchange.request.bodyText()
            val params = parseFormUrlEncoded(body)
            return params[config.formFieldName]
        }

        return null
    }

    private fun parseFormUrlEncoded(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        
        return body.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index > 0) {
                    val name = decodeUrlComponent(part.substring(0, index))
                    val value = decodeUrlComponent(part.substring(index + 1))
                    name to value
                } else {
                    null
                }
            }
            .toMap()
    }

    private fun decodeUrlComponent(encoded: String): String {
        return encoded
            .replace('+', ' ')
            .replace(Regex("%([0-9A-Fa-f]{2})")) { match ->
                val hex = match.groupValues[1]
                hex.toInt(16).toChar().toString()
            }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private fun secureCompare(a: String, b: String): Boolean {
        if (a.length != b.length) {
            return false
        }
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}

/**
 * Extension function to get the CSRF token from an Exchange.
 */
fun Exchange.csrfToken(): String? = attributes.get(CsrfTokenAttributeKey)

/**
 * Extension function to get the CSRF token from an Exchange, throwing if not available.
 */
fun Exchange.requireCsrfToken(): String =
    csrfToken() ?: throw IllegalStateException("CSRF token not available. Is CsrfMiddleware installed?")

/**
 * Install CSRF middleware on a Pipeline.
 * Note: Session middleware must be installed before CSRF middleware.
 */
fun codes.yousef.aether.core.pipeline.Pipeline.installCsrf(
    config: CsrfConfig = CsrfConfig()
) {
    val middleware = CsrfMiddleware(config)
    use(middleware.asMiddleware())
}
