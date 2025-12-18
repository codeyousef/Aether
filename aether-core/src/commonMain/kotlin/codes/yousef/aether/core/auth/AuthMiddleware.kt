package codes.yousef.aether.core.auth

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.Middleware

/**
 * Attribute key for accessing the principal from an Exchange.
 */
val PrincipalAttributeKey = AttributeKey<Principal>("aether.auth.principal", Principal::class)

/**
 * Authentication middleware that uses one or more providers to authenticate requests.
 *
 * This middleware:
 * 1. Extracts credentials from the request using registered providers
 * 2. Authenticates the credentials
 * 3. Stores the principal in exchange attributes if successful
 * 4. Optionally rejects unauthenticated requests
 */
class AuthenticationMiddleware(
    private val providers: List<AuthenticationProvider>,
    private val config: AuthConfig = AuthConfig()
) {
    /**
     * Create the middleware function.
     */
    fun asMiddleware(): Middleware = { exchange, next ->
        // Check if this path is excluded
        if (isExcluded(exchange)) {
            next()
        } else {
            var authResult: AuthResult = AuthResult.NoCredentials
            var usedProvider: AuthenticationProvider? = null

            // Try each provider
            for (provider in providers) {
                val credentials = provider.extractCredentials(exchange)
                if (credentials != null) {
                    authResult = provider.authenticate(credentials)
                    usedProvider = provider
                    if (authResult.isSuccess) {
                        break
                    }
                }
            }

            when (authResult) {
                is AuthResult.Success -> {
                    // Store principal in attributes
                    exchange.attributes.put(PrincipalAttributeKey, authResult.principal)
                    next()
                }
                is AuthResult.Failure -> {
                    // Authentication failed
                    usedProvider?.onAuthenticationFailure(exchange, authResult)
                    exchange.unauthorized(authResult.message)
                }
                AuthResult.NoCredentials -> {
                    if (config.required) {
                        // No credentials and auth is required
                        providers.firstOrNull()?.onAuthenticationFailure(exchange, authResult)
                        exchange.unauthorized(config.unauthenticatedMessage)
                    } else {
                        // Auth is optional, continue without principal
                        next()
                    }
                }
            }
        }
    }

    private fun isExcluded(exchange: Exchange): Boolean {
        val path = exchange.request.path

        // Check excluded paths
        if (path in config.excludedPaths) {
            return true
        }

        // Check excluded path prefixes
        for (prefix in config.excludedPathPrefixes) {
            if (path.startsWith(prefix)) {
                return true
            }
        }

        return false
    }
}

/**
 * Authorization middleware that requires specific roles.
 */
class AuthorizationMiddleware(
    private val requiredRoles: Set<String>,
    private val requireAll: Boolean = false,
    private val errorMessage: String = "Access denied"
) {
    /**
     * Create the middleware function.
     */
    fun asMiddleware(): Middleware = { exchange, next ->
        val principal = exchange.attributes.get(PrincipalAttributeKey)

        if (principal == null) {
            exchange.unauthorized("Authentication required")
        } else {
            val hasAccess = if (requireAll) {
                principal.hasAllRoles(*requiredRoles.toTypedArray())
            } else {
                principal.hasAnyRole(*requiredRoles.toTypedArray())
            }

            if (hasAccess) {
                next()
            } else {
                exchange.forbidden(errorMessage)
            }
        }
    }
}

/**
 * Extension function to get the principal from an Exchange.
 * Returns null if not authenticated.
 */
fun Exchange.principal(): Principal? = attributes.get(PrincipalAttributeKey)

/**
 * Extension function to get the principal from an Exchange, throwing if not available.
 */
fun Exchange.requirePrincipal(): Principal =
    principal() ?: throw IllegalStateException("Not authenticated")

/**
 * Extension function to check if the request is authenticated.
 */
fun Exchange.isAuthenticated(): Boolean = principal() != null

/**
 * Extension function to check if the principal has a specific role.
 */
fun Exchange.hasRole(role: String): Boolean = principal()?.hasRole(role) == true

/**
 * Extension function to check if the principal has any of the specified roles.
 */
fun Exchange.hasAnyRole(vararg roles: String): Boolean = 
    principal()?.hasAnyRole(*roles) == true

/**
 * Extension function to check if the principal has all of the specified roles.
 */
fun Exchange.hasAllRoles(vararg roles: String): Boolean = 
    principal()?.hasAllRoles(*roles) == true

/**
 * Install authentication middleware on a Pipeline.
 */
fun codes.yousef.aether.core.pipeline.Pipeline.installAuthentication(
    vararg providers: AuthenticationProvider,
    config: AuthConfig = AuthConfig()
) {
    val middleware = AuthenticationMiddleware(providers.toList(), config)
    use(middleware.asMiddleware())
}

/**
 * Install authorization middleware on a Pipeline.
 */
fun codes.yousef.aether.core.pipeline.Pipeline.installAuthorization(
    vararg roles: String,
    requireAll: Boolean = false,
    errorMessage: String = "Access denied"
) {
    val middleware = AuthorizationMiddleware(roles.toSet(), requireAll, errorMessage)
    use(middleware.asMiddleware())
}

/**
 * Create a middleware that requires authentication.
 */
fun requireAuth(config: AuthConfig = AuthConfig()): Middleware = { exchange, next ->
    if (exchange.isAuthenticated()) {
        next()
    } else {
        exchange.unauthorized(config.unauthenticatedMessage)
    }
}

/**
 * Create a middleware that requires specific roles.
 */
fun requireRoles(vararg roles: String, requireAll: Boolean = false): Middleware = { exchange, next ->
    val hasAccess = if (requireAll) {
        exchange.hasAllRoles(*roles)
    } else {
        exchange.hasAnyRole(*roles)
    }

    if (hasAccess) {
        next()
    } else {
        exchange.forbidden("Access denied")
    }
}
