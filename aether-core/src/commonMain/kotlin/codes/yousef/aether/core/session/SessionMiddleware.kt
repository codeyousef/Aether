package codes.yousef.aether.core.session

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Cookie
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.Middleware

/**
 * Attribute key for accessing the session from an Exchange.
 */
val SessionAttributeKey = AttributeKey<Session>("aether.session", Session::class)

/**
 * Get the current session from the exchange.
 * Returns null if no session is active (e.g. middleware not installed).
 */
fun Exchange.session(): Session? = attributes.get(SessionAttributeKey)

/**
 * Session middleware that manages session creation, loading, and persistence.
 *
 * This middleware:
 * 1. Reads the session cookie from the request
 * 2. Loads or creates a session
 * 3. Stores the session in exchange attributes
 * 4. After the request, saves the session if modified
 * 5. Sets the session cookie on the response
 */
class SessionMiddleware(
    private val store: SessionStore,
    private val config: SessionConfig = SessionConfig()
) {
    /**
     * Create the middleware function.
     */
    fun asMiddleware(): Middleware = { exchange, next ->
        // Get or create session
        val sessionId = exchange.request.cookies[config.cookieName]?.value
        var session: Session? = null

        if (sessionId != null) {
            session = store.get(sessionId)
        }

        if (session == null) {
            // Create new session
            val newSessionId = generateSecureSessionId(config.sessionIdLength)
            session = DefaultSession(
                id = newSessionId,
                createdAt = currentTimeMillis()
            )
        } else {
            // Mark existing session as accessed
            session.markAccessed()
        }

        // Store session in attributes
        exchange.attributes.put(SessionAttributeKey, session)

        try {
            // Execute next middleware/handler
            next()
        } finally {
            // Save session and set cookie
            val currentSession = exchange.attributes.get(SessionAttributeKey)
            if (currentSession != null) {
                if (currentSession.isInvalidated) {
                    // Delete session and clear cookie
                    store.delete(currentSession.id)
                    exchange.response.setCookie(createExpiredCookie())
                } else {
                    // Save session and set/update cookie
                    store.save(currentSession)
                    exchange.response.setCookie(createSessionCookie(currentSession.id))
                }
            }
        }
    }

    private fun createSessionCookie(sessionId: String): Cookie {
        return Cookie(
            name = config.cookieName,
            value = sessionId,
            path = config.path,
            domain = config.domain,
            maxAge = config.maxAge,
            secure = config.secure,
            httpOnly = config.httpOnly,
            sameSite = when (config.sameSite) {
                SameSitePolicy.STRICT -> Cookie.SameSite.STRICT
                SameSitePolicy.LAX -> Cookie.SameSite.LAX
                SameSitePolicy.NONE -> Cookie.SameSite.NONE
            }
        )
    }

    private fun createExpiredCookie(): Cookie {
        return Cookie(
            name = config.cookieName,
            value = "",
            path = config.path,
            domain = config.domain,
            maxAge = 0,
            secure = config.secure,
            httpOnly = config.httpOnly,
            sameSite = when (config.sameSite) {
                SameSitePolicy.STRICT -> Cookie.SameSite.STRICT
                SameSitePolicy.LAX -> Cookie.SameSite.LAX
                SameSitePolicy.NONE -> Cookie.SameSite.NONE
            }
        )
    }
}

/**
 * Extension function to get the session from an Exchange, throwing if not available.
 */
fun Exchange.requireSession(): Session = 
    session() ?: throw IllegalStateException("Session not available. Is SessionMiddleware installed?")

/**
 * Install session middleware on a Pipeline.
 */
fun codes.yousef.aether.core.pipeline.Pipeline.installSession(
    store: SessionStore,
    config: SessionConfig = SessionConfig()
) {
    val middleware = SessionMiddleware(store, config)
    use(middleware.asMiddleware())
}
