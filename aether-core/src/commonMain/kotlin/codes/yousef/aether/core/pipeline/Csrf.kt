package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Cookie
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.HttpMethod
import kotlin.random.Random

/**
 * Middleware that provides Cross-Site Request Forgery (CSRF) protection.
 *
 * It ensures that unsafe requests (POST, PUT, DELETE, PATCH) originate from the same site
 * by verifying a secret token.
 */
class CsrfProtection(private val config: CsrfConfig) {
    
    suspend operator fun invoke(exchange: Exchange, next: suspend () -> Unit) {
        // 1. Get or generate CSRF token
        val existingCookie = exchange.request.cookies[config.cookieName]
        val token = existingCookie?.value ?: generateToken()
        
        // 2. Set cookie if it didn't exist
        if (existingCookie == null) {
            exchange.response.setCookie(
                Cookie(
                    name = config.cookieName,
                    value = token,
                    path = "/",
                    httpOnly = false, // Needs to be readable by JS often, or at least sent back
                    secure = config.secureCookie
                )
            )
        }
        
        // 3. Expose token to the application (e.g. for form rendering)
        exchange.attributes.put(CsrfTokenKey, token)
        
        // 4. Verify token for unsafe methods
        if (exchange.request.method in unsafeMethods) {
            val requestToken = getRequestToken(exchange)
            
            if (requestToken == null || requestToken != token) {
                exchange.respond(403, "CSRF token missing or incorrect")
                return
            }
        }
        
        next()
    }
    
    private fun generateToken(): String {
        val charPool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..32)
            .map { Random.nextInt(0, charPool.length) }
            .map(charPool::get)
            .joinToString("")
    }
    
    private suspend fun getRequestToken(exchange: Exchange): String? {
        // Check header
        val headerToken = exchange.request.headers[config.headerName]
        if (headerToken != null) return headerToken
        
        // Check form data if content type is form-urlencoded
        val contentType = exchange.request.headers["Content-Type"]
        if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
            val bodyText = exchange.request.bodyText()
            val params = parseQueryString(bodyText)
            return params[config.cookieName]?.firstOrNull()
        }
        
        return null
    }

    private fun parseQueryString(query: String): Map<String, List<String>> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index > 0) {
                    val name = part.substring(0, index)
                    val value = part.substring(index + 1)
                    // Simple URL decoding might be needed here, but for the token (alphanumeric) it's fine.
                    // Ideally we should use a proper URL decoder.
                    name to value
                } else {
                    null
                }
            }
            .groupBy({ it.first }, { it.second })
    }
    
    companion object {
        val CsrfTokenKey = AttributeKey("CsrfToken", String::class)
        private val unsafeMethods = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)
    }
}

data class CsrfConfig(
    val cookieName: String = "csrftoken",
    val headerName: String = "X-CSRF-Token",
    val secureCookie: Boolean = false
)

/**
 * Installs CSRF protection middleware.
 */
fun Pipeline.installCsrfProtection(configure: CsrfConfig.() -> Unit = {}) {
    val config = CsrfConfig().apply(configure)
    use(CsrfProtection(config)::invoke)
}
