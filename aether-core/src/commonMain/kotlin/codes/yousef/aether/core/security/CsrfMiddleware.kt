package codes.yousef.aether.core.security

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.pipeline.Middleware
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
     * Retained for source compatibility. Form tokens are not accepted because
     * identity CSRF protection requires the token in [headerName].
     */
    @Deprecated("CSRF tokens are accepted only from the configured header")
    val formFieldName: String = "_csrf",

    /**
     * Retained for source compatibility. Query-string CSRF tokens are never
     * accepted because URLs are commonly persisted in logs and browser history.
     */
    @Deprecated("Query-string CSRF tokens are not supported")
    val queryParamName: String = "_csrf",

    /**
     * HTTP methods that require CSRF validation.
     */
    val protectedMethods: Set<HttpMethod> = setOf(
        HttpMethod.POST,
        HttpMethod.PUT,
        HttpMethod.DELETE,
        HttpMethod.PATCH,
        HttpMethod.CONNECT
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
    val rotateTokenOnRequest: Boolean = false,

    /**
     * Exact serialized origins accepted for unsafe cookie-authenticated requests.
     * An empty set intentionally rejects every such request.
     */
    val allowedOrigins: Set<String> = emptySet(),

    /**
     * Cookies that indicate the request is using cookie authentication.
     * Bearer- or service-token-only requests are outside this middleware's scope.
     */
    val sessionCookieNames: Set<String> = setOf(
        "AETHER_SESSION",
        "__Host-aether_session"
    )
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

        // Get or generate a token bound to this server-side session.
        var token = session.getString(config.sessionKey)
        val requiresValidation = shouldValidateCsrf(exchange)
        if (token == null || (config.rotateTokenOnRequest && !requiresValidation)) {
            token = generateSecureSessionId(config.tokenLength)
            session.set(config.sessionKey, token)
        }

        // Store token in attributes for use in templates
        exchange.attributes.put(CsrfTokenAttributeKey, token)

        // Check if this request needs CSRF validation
        if (requiresValidation) {
            val requestOrigin = extractExactOrigin(exchange)
            val requestToken = extractHeaderToken(exchange)
            if (requestOrigin !in config.allowedOrigins ||
                requestToken == null ||
                !secureCompare(token, requestToken)
            ) {
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

        // CSRF applies to ambient cookie credentials. Requests authenticated
        // only by an explicit Authorization header do not need a CSRF token.
        if (config.sessionCookieNames.none(exchange.request.cookies::contains)) {
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

    private fun extractExactOrigin(exchange: Exchange): String? =
        exchange.request.headers.getAll("Origin").singleOrNull()
            ?.takeIf { it.isNotEmpty() && it != "null" }

    private fun extractHeaderToken(exchange: Exchange): String? =
        exchange.request.headers.getAll(config.headerName).singleOrNull()
            ?.takeIf { it.isNotEmpty() }

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
