package codes.yousef.aether.auth

import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.pipeline.Middleware

data class IdentityCsrfConfig(
    val headerName: String = "X-CSRF-Token",
    val requestIdHeader: String = DEFAULT_IDENTITY_REQUEST_ID_HEADER,
    val protectedMethods: Set<HttpMethod> = setOf(
        HttpMethod.POST,
        HttpMethod.PUT,
        HttpMethod.PATCH,
        HttpMethod.DELETE,
        HttpMethod.CONNECT
    ),
    val tokenBytes: Int = 32
) {
    init {
        require(headerName.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Invalid CSRF header name" }
        require(requestIdHeader.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Invalid request-ID header name" }
        require(tokenBytes in 16..64) { "CSRF tokens must contain 16..64 random bytes" }
    }
}

/** Raw CSRF value returned only when creating or rotating a session. */
class IssuedIdentityCsrfToken internal constructor(
    val encoded: String,
    val digest: SecretDigest
) {
    override fun toString(): String = "IssuedIdentityCsrfToken(<redacted>)"
}

suspend fun issueIdentityCsrfToken(
    runtime: IdentityRuntime,
    identityConfig: IdentityConfig,
    csrfConfig: IdentityCsrfConfig = IdentityCsrfConfig()
): IssuedIdentityCsrfToken {
    val bytes = runtime.secureRandom.nextBytes(csrfConfig.tokenBytes)
    return try {
        val pepper = runtime.secrets.resolve(identityConfig.keys.sessionPepper)
        val digest = runtime.crypto.hmacSha256(pepper, bytes)
        try {
            IssuedIdentityCsrfToken(
                encoded = Base64Url.encode(bytes),
                digest = SecretDigest(
                    algorithm = DigestAlgorithm.HMAC_SHA256,
                    encoded = Base64Url.encode(digest),
                    keyVersion = identityConfig.keys.sessionPepper.version
                )
            )
        } finally {
            digest.fill(0)
        }
    } finally {
        bytes.fill(0)
    }
}

/**
 * Session-bound CSRF protection for identity cookies. Tokens are accepted only from one header;
 * query-string token names are rejected, and Origin must exactly match an allowed RP origin.
 */
class IdentityCsrfMiddleware(
    private val runtime: IdentityRuntime,
    private val identityConfig: IdentityConfig,
    private val csrfConfig: IdentityCsrfConfig = IdentityCsrfConfig()
) {
    fun asMiddleware(): Middleware = middleware@{ exchange, next ->
        exchange.ensureIdentityRequestId(runtime.secureRandom, csrfConfig.requestIdHeader)
        if (exchange.request.method !in csrfConfig.protectedMethods) {
            next()
            return@middleware
        }

        if (containsIdentityCsrfQueryToken(exchange.request.query, csrfConfig.headerName)) {
            exchange.respondIdentityError(IdentityErrorCode.CSRF_INVALID)
            return@middleware
        }

        val hasSessionCookie = exchange.request.cookies.contains(identityConfig.cookie.name)
        val session = exchange.identityContext.session
        if (!hasSessionCookie && session == null) {
            next()
            return@middleware
        }
        if (!hasSessionCookie || session == null) {
            exchange.respondIdentityError(IdentityErrorCode.CSRF_INVALID)
            return@middleware
        }

        val origin = exchange.request.headers.getAll("Origin").singleOrNull()
        val encodedToken = exchange.request.headers.getAll(csrfConfig.headerName).singleOrNull()
        if (origin !in identityConfig.relyingParty.allowedOrigins || encodedToken == null) {
            exchange.respondIdentityError(IdentityErrorCode.CSRF_INVALID)
            return@middleware
        }

        val provided = runCatching {
            Base64Url.decode(encodedToken, maximumBytes = csrfConfig.tokenBytes)
        }.getOrNull()
        if (provided == null || provided.size != csrfConfig.tokenBytes ||
            session.csrfDigest.algorithm != DigestAlgorithm.HMAC_SHA256
        ) {
            provided?.fill(0)
            exchange.respondIdentityError(IdentityErrorCode.CSRF_INVALID)
            return@middleware
        }

        try {
            val reference = identityConfig.keys.sessionPepper(session.csrfDigest.keyVersion)
            val expected = runCatching {
                Base64Url.decode(session.csrfDigest.encoded, maximumBytes = 64)
            }.getOrNull()
            if (reference == null || expected == null || expected.size != 32) {
                expected?.fill(0)
                exchange.respondIdentityError(IdentityErrorCode.CSRF_INVALID)
                return@middleware
            }
            try {
                val pepper = runtime.secrets.resolve(reference)
                val actual = runtime.crypto.hmacSha256(pepper, provided)
                try {
                    if (!runtime.crypto.constantTimeEquals(expected, actual)) {
                        exchange.respondIdentityError(IdentityErrorCode.CSRF_INVALID)
                        return@middleware
                    }
                } finally {
                    actual.fill(0)
                }
            } finally {
                expected.fill(0)
            }
        } finally {
            provided.fill(0)
        }
        next()
    }
}

internal fun containsIdentityCsrfQueryToken(query: String?, headerName: String): Boolean {
    query ?: return false
    if (query.length > MAX_IDENTITY_CSRF_QUERY_LENGTH) return true
    val forbiddenNames = setOf(
        "csrf",
        "_csrf",
        "csrftoken",
        "csrf_token",
        "csrf-token",
        headerName.lowercase()
    )
    return query.split('&').any { field ->
        val rawName = field.substringBefore('=')
        val name = decodeIdentityCsrfQueryComponent(rawName)?.lowercase() ?: return@any true
        name in forbiddenNames
    }
}

private fun decodeIdentityCsrfQueryComponent(encoded: String): String? {
    if (encoded.length > MAX_IDENTITY_CSRF_QUERY_LENGTH) return null
    val bytes = ByteArray(encoded.length)
    var read = 0
    var written = 0
    while (read < encoded.length) {
        when (val character = encoded[read]) {
            '+' -> {
                bytes[written++] = ' '.code.toByte()
                read++
            }
            '%' -> {
                if (read + 2 >= encoded.length) return null
                val high = encoded[read + 1].digitToIntOrNull(16) ?: return null
                val low = encoded[read + 2].digitToIntOrNull(16) ?: return null
                bytes[written++] = ((high shl 4) or low).toByte()
                read += 3
            }
            else -> {
                if (character.code !in 0x21..0x7e) return null
                bytes[written++] = character.code.toByte()
                read++
            }
        }
    }
    return try {
        bytes.decodeToString(0, written, throwOnInvalidSequence = true)
    } catch (_: IllegalArgumentException) {
        null
    } finally {
        bytes.fill(0)
    }
}

private const val MAX_IDENTITY_CSRF_QUERY_LENGTH = 8_192
