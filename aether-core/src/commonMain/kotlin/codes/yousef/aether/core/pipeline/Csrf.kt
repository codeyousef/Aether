@file:Suppress("DEPRECATION")

package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.security.CsrfConfig as SessionCsrfConfig
import codes.yousef.aether.core.security.CsrfMiddleware as SessionCsrfMiddleware
import codes.yousef.aether.core.security.CsrfTokenAttributeKey

/**
 * Compatibility adapter for the original pipeline CSRF API.
 *
 * The implementation now delegates to the session-bound CSRF middleware so
 * Aether has one validation path. Session middleware must be installed before
 * this middleware.
 */
@Deprecated(
    message = "Use codes.yousef.aether.core.security.CsrfMiddleware",
    replaceWith = ReplaceWith("CsrfMiddleware(config)", "codes.yousef.aether.core.security.CsrfMiddleware")
)
class CsrfProtection(private val config: CsrfConfig) {
    private val delegate = SessionCsrfMiddleware(
        SessionCsrfConfig(
            headerName = config.headerName,
            allowedOrigins = config.allowedOrigins,
            sessionCookieNames = config.sessionCookieNames
        )
    ).asMiddleware()

    suspend operator fun invoke(exchange: Exchange, next: suspend () -> Unit) {
        delegate(exchange) {
            exchange.attributes.get(CsrfTokenAttributeKey)?.let { token ->
                exchange.attributes.put(CsrfTokenKey, token)
            }
            next()
        }
    }

    companion object {
        /** Retained for compatibility with the original pipeline API. */
        val CsrfTokenKey = AttributeKey("CsrfToken", String::class)
    }
}

/**
 * Compatibility configuration for [CsrfProtection].
 *
 * [cookieName] and [secureCookie] describe the removed double-submit cookie
 * implementation and are ignored. Tokens now live only in the server-side
 * session and must be returned in [headerName].
 */
@Deprecated("Use codes.yousef.aether.core.security.CsrfConfig")
data class CsrfConfig(
    var cookieName: String = "csrftoken",
    var headerName: String = "X-CSRF-Token",
    var secureCookie: Boolean = false,
    var allowedOrigins: Set<String> = emptySet(),
    var sessionCookieNames: Set<String> = setOf(
        "AETHER_SESSION",
        "__Host-aether_session"
    )
)

/**
 * Installs the compatibility CSRF adapter.
 *
 * Prefer `security.installCsrf`, which accepts the canonical immutable config.
 */
@Deprecated(
    message = "Use codes.yousef.aether.core.security.installCsrf",
    replaceWith = ReplaceWith("installCsrf()", "codes.yousef.aether.core.security.installCsrf")
)
fun Pipeline.installCsrfProtection(configure: CsrfConfig.() -> Unit = {}) {
    val config = CsrfConfig().apply(configure)
    use(CsrfProtection(config)::invoke)
}
