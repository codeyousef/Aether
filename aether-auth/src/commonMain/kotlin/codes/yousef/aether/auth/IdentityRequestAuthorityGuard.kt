package codes.yousef.aether.auth

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.pipeline.Middleware
import codes.yousef.aether.core.TrustedProxyResolver
import kotlinx.coroutines.CancellationException

/**
 * Protects application callbacks on their original Exchange after IdentityMiddleware resolution.
 * This is request authority and CSRF protection; applications still enforce resource capabilities.
 * Native credentials must be resolved by CompositeIdentityContextResolver before invoking this guard.
 */
class IdentityRequestAuthorityGuard(
    private val runtime: IdentityRuntime,
    private val identityConfig: IdentityConfig,
    private val httpConfig: IdentityHttpApiConfig = IdentityHttpApiConfig()
) {
    private val authority = IdentityHttpRequestAuthority(runtime, identityConfig, httpConfig)

    /** Returns false after writing a canonical denial; invokes [handler] exactly once on success. */
    suspend fun protect(exchange: Exchange, handler: suspend (Exchange) -> Unit): Boolean {
        val requestId = exchange.ensureIdentityRequestId(runtime.secureRandom, httpConfig.requestIdHeader)
        exchange.response.setHeader("Cache-Control", "no-store")
        val failure = try {
            authority.failure(exchange) ?: authenticatedCredentialFailure(exchange)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { IdentityErrorCode.SERVICE_UNAVAILABLE }
        if (failure != null) {
            exchange.respondIdentityError(failure, requestId)
            return false
        }
        handler(exchange)
        return true
    }

    fun asMiddleware(): Middleware = { exchange, next -> protect(exchange) { next() }; Unit }

    private fun authenticatedCredentialFailure(exchange: Exchange): IdentityErrorCode? {
        val context = exchange.identityContext
        if (!context.isAuthenticated || !context.isSessionUsableAt(runtime.clock.now()) ||
            context.session?.assurance == AuthenticationAssurance.RECOVERY) return IdentityErrorCode.AUTHENTICATION_REQUIRED
        val cookie = exchange.request.cookies.contains(identityConfig.cookie.name)
        val authorization = exchange.request.headers.getAll("Authorization")
        return if (context.session != null) {
            if (cookie && authorization.isEmpty()) null else IdentityErrorCode.INVALID_CREDENTIALS
        } else {
            if (!cookie && authorization.size == 1 && context.principal?.kind in
                setOf(IdentityPrincipalKind.DEVICE, IdentityPrincipalKind.SERVICE)) null else IdentityErrorCode.INVALID_CREDENTIALS
        }
    }
}

/** One canonical implementation used by identity operations and application guards. */
internal class IdentityHttpRequestAuthority(
    private val runtime: IdentityRuntime,
    private val identityConfig: IdentityConfig,
    private val httpConfig: IdentityHttpApiConfig
) {
    private val trustedProxyResolver = TrustedProxyResolver(identityConfig.trustedProxy.trustedCidrs)
    private val enforceExternalAuthority = identityConfig.environment in setOf(IdentityEnvironment.PRODUCTION, IdentityEnvironment.STAGING)
    private val publicScheme = identityConfig.publicBaseUrl.substringBefore("://").lowercase()
    private val publicHost = identityConfig.publicBaseUrl.substringAfter("://").lowercase()

    suspend fun failure(exchange: Exchange): IdentityErrorCode? = when {
        hasAmbiguousIdentityCredentials(exchange, identityConfig) -> IdentityErrorCode.INVALID_CREDENTIALS
        !hasValidRequestAuthority(exchange) -> IdentityErrorCode.REQUEST_INVALID
        containsQueryCsrf(exchange) || !validateCsrf(exchange) -> IdentityErrorCode.CSRF_INVALID
        else -> null
    }

    private suspend fun validateCsrf(exchange: Exchange): Boolean {
        if (exchange.request.method !in PROTECTED_METHODS) return true
        val hasSessionCookie = exchange.request.cookies.contains(identityConfig.cookie.name)
        val session = exchange.identityContext.session
        if (!hasSessionCookie && session == null) return true
        if (!hasSessionCookie || session == null) return false
        val origin = exchange.request.headers.getAll("Origin").singleOrNull()
        val encoded = exchange.request.headers.getAll(httpConfig.csrfHeader).singleOrNull()
        if (origin !in identityConfig.relyingParty.allowedOrigins || encoded == null ||
            session.csrfDigest.algorithm != DigestAlgorithm.HMAC_SHA256
        ) return false
        val provided = runCatching { Base64Url.decode(encoded, maximumBytes = CSRF_TOKEN_BYTES) }.getOrNull()
            ?: return false
        if (provided.size != CSRF_TOKEN_BYTES) {
            provided.fill(0)
            return false
        }
        return try {
            val reference = identityConfig.keys.sessionPepper(session.csrfDigest.keyVersion) ?: return false
            val expected = runCatching {
                Base64Url.decode(session.csrfDigest.encoded, maximumBytes = 32)
            }.getOrNull() ?: return false
            try {
                if (expected.size != 32) return false
                val actual = runtime.crypto.hmacSha256(runtime.secrets.resolve(reference), provided)
                try {
                    actual.size == 32 && runtime.crypto.constantTimeEquals(expected, actual)
                } finally {
                    actual.fill(0)
                }
            } finally {
                expected.fill(0)
            }
        } finally {
            provided.fill(0)
        }
    }

    private fun containsQueryCsrf(exchange: Exchange): Boolean {
        return containsIdentityCsrfQueryToken(exchange.request.query, httpConfig.csrfHeader)
    }

    private fun hasValidRequestAuthority(exchange: Exchange): Boolean {
        if (!enforceExternalAuthority) return true
        val resolved = trustedProxyResolver.resolve(exchange)
        return resolved.scheme?.lowercase() == publicScheme && resolved.host?.lowercase() == publicHost
    }

}

private const val CSRF_TOKEN_BYTES = 32
private val PROTECTED_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.CONNECT)

internal fun hasAmbiguousIdentityCredentials(exchange: Exchange, config: IdentityConfig): Boolean {
    val authorization = exchange.request.headers.getAll("Authorization")
    val cookieHeaders = exchange.request.headers.getAll("Cookie")
    val cookieCount = cookieHeaders.flatMap { it.split(';') }.count { it.substringBefore('=').trim() == config.cookie.name }
    return authorization.size > 1 || cookieHeaders.size > 1 || cookieCount > 1 ||
        exchange.request.cookies.contains(config.cookie.name) && authorization.isNotEmpty()
}
