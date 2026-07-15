package codes.yousef.aether.auth

import codes.yousef.aether.core.Exchange

/** Narrow credential verifier used to compose opaque bearer-token authorities. */
fun interface IdentityBearerAuthenticator {
    suspend fun authenticate(token: String): IdentityOperationResult<IdentityContext>
}

/**
 * Resolves exactly one identity credential: either the configured session cookie or one Bearer
 * token. Device access tokens are attempted before optional service credentials. Credential-kind
 * failures are intentionally collapsed so the Authorization boundary cannot be used as an oracle.
 */
class CompositeIdentityContextResolver(
    private val sessionResolver: IdentityContextResolver,
    private val config: IdentityConfig,
    private val deviceAuthenticator: IdentityBearerAuthenticator,
    private val serviceAuthenticator: IdentityBearerAuthenticator? = null
) : IdentityContextResolver {
    override suspend fun resolve(exchange: Exchange): IdentityResolutionResult {
        val hasSessionCookie = exchange.request.cookies.contains(config.cookie.name)
        val authorization = exchange.request.headers.entries()
            .filter { (name, _) -> name.equals(AUTHORIZATION_HEADER, ignoreCase = true) }
            .flatMap { it.value }

        if (hasSessionCookie && authorization.isNotEmpty()) return invalidCredentials()
        if (hasSessionCookie) return sessionResolver.resolve(exchange)
        if (authorization.isEmpty()) return IdentityResolutionResult.Anonymous
        if (authorization.size != 1) return invalidCredentials()

        val token = parseBearer(authorization.single()) ?: return invalidCredentials()
        return when (val device = deviceAuthenticator.authenticate(token)) {
            is IdentityOperationResult.Success -> IdentityResolutionResult.Authenticated(device.value)
            is IdentityOperationResult.Failure -> {
                if (device.code.isAuthorityUnavailable()) return unavailable()
                val service = serviceAuthenticator ?: return invalidCredentials()
                when (val result = service.authenticate(token)) {
                    is IdentityOperationResult.Success -> IdentityResolutionResult.Authenticated(result.value)
                    is IdentityOperationResult.Failure -> if (result.code.isAuthorityUnavailable()) {
                        unavailable()
                    } else {
                        invalidCredentials()
                    }
                }
            }
        }
    }

    private fun parseBearer(value: String): String? {
        if (value.length !in MIN_AUTHORIZATION_LENGTH..MAX_AUTHORIZATION_LENGTH ||
            !value.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)
        ) return null
        val token = value.substring(BEARER_PREFIX.length)
        if (token.length !in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH ||
            token.count { it == '.' } != 1 || !TOKEN_PATTERN.matches(token)
        ) return null
        return token
    }

    private fun IdentityErrorCode.isAuthorityUnavailable(): Boolean =
        this == IdentityErrorCode.SERVICE_UNAVAILABLE || this == IdentityErrorCode.INTERNAL_ERROR

    private fun invalidCredentials() = IdentityResolutionResult.Rejected(IdentityErrorCode.INVALID_CREDENTIALS)

    private fun unavailable() = IdentityResolutionResult.Rejected(IdentityErrorCode.SERVICE_UNAVAILABLE)

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val MIN_TOKEN_LENGTH = 20
        const val MAX_TOKEN_LENGTH = 512
        const val MIN_AUTHORIZATION_LENGTH = BEARER_PREFIX.length + MIN_TOKEN_LENGTH
        const val MAX_AUTHORIZATION_LENGTH = BEARER_PREFIX.length + MAX_TOKEN_LENGTH
        val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    }
}
