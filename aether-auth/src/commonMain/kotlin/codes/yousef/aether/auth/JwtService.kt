package codes.yousef.aether.auth

import kotlinx.serialization.Serializable

@Serializable
data class JwtPayload(
    val subject: String,
    val issuer: String?,
    val expiresAt: Long?,
    val issuedAt: Long?,
    val claims: Map<String, String> = emptyMap()
)

expect object JwtService {
    /**
     * Generate a JWT token.
     * @param subject The subject (user ID, username, etc.)
     * @param secret The secret key to sign the token
     * @param issuer The issuer claim (optional)
     * @param expirationMillis Expiration time in milliseconds (default 1 hour)
     * @param claims Additional custom claims
     */
    fun generateToken(
        subject: String,
        secret: String,
        issuer: String? = null,
        expirationMillis: Long = 3600_000,
        claims: Map<String, String> = emptyMap()
    ): String

    /**
     * Verify a JWT token.
     * Returns the payload if valid, null otherwise.
     * @param token The JWT token string
     * @param secret The secret key used to sign the token
     * @param issuer The expected issuer (optional)
     */
    fun verifyToken(token: String, secret: String, issuer: String? = null): JwtPayload?
}

