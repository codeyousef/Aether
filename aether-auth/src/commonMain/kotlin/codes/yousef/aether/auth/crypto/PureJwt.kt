package codes.yousef.aether.auth.crypto

import kotlinx.serialization.json.*

/**
 * Pure Kotlin JWT implementation for platforms without native JWT libraries.
 * Supports HS256 (HMAC-SHA256) algorithm only.
 */
object PureJwt {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Generate a JWT token with HS256 algorithm.
     */
    fun generate(
        subject: String,
        secret: String,
        issuer: String?,
        expirationMillis: Long,
        issuedAtMillis: Long,
        claims: Map<String, String>
    ): String {
        // Build header
        val header = buildJsonObject {
            put("alg", "HS256")
            put("typ", "JWT")
        }

        // Build payload
        val payload = buildJsonObject {
            put("sub", subject)
            put("iat", issuedAtMillis / 1000) // JWT uses seconds
            put("exp", (issuedAtMillis + expirationMillis) / 1000)
            if (issuer != null) {
                put("iss", issuer)
            }
            claims.forEach { (key, value) ->
                put(key, value)
            }
        }

        // Encode header and payload
        val headerEncoded = Base64Url.encodeToString(header.toString())
        val payloadEncoded = Base64Url.encodeToString(payload.toString())

        // Create signature
        val signingInput = "$headerEncoded.$payloadEncoded"
        val signature = HmacSha256.compute(secret, signingInput)
        val signatureEncoded = Base64Url.encode(signature)

        return "$headerEncoded.$payloadEncoded.$signatureEncoded"
    }

    /**
     * Verify a JWT token and return the payload if valid.
     * Returns null if verification fails.
     */
    fun verify(
        token: String,
        secret: String,
        expectedIssuer: String?,
        currentTimeMillis: Long
    ): JwtVerifyResult? {
        try {
            val parts = token.split(".")
            if (parts.size != 3) return null

            val (headerEncoded, payloadEncoded, signatureEncoded) = parts

            // Verify signature
            val signingInput = "$headerEncoded.$payloadEncoded"
            val expectedSignature = HmacSha256.compute(secret, signingInput)
            val actualSignature = Base64Url.decode(signatureEncoded)

            if (!expectedSignature.contentEquals(actualSignature)) {
                return null // Invalid signature
            }

            // Decode and parse header
            val headerJson = Base64Url.decodeToString(headerEncoded)
            val header = json.parseToJsonElement(headerJson).jsonObject
            val alg = header["alg"]?.jsonPrimitive?.content
            if (alg != "HS256") {
                return null // Unsupported algorithm
            }

            // Decode and parse payload
            val payloadJson = Base64Url.decodeToString(payloadEncoded)
            val payload = json.parseToJsonElement(payloadJson).jsonObject

            // Extract standard claims
            val subject = payload["sub"]?.jsonPrimitive?.content ?: return null
            val issuer = payload["iss"]?.jsonPrimitive?.contentOrNull
            val exp = payload["exp"]?.jsonPrimitive?.longOrNull
            val iat = payload["iat"]?.jsonPrimitive?.longOrNull

            // Verify issuer if expected
            if (expectedIssuer != null && issuer != expectedIssuer) {
                return null // Issuer mismatch
            }

            // Verify expiration
            if (exp != null) {
                val expirationMillis = exp * 1000
                if (currentTimeMillis > expirationMillis) {
                    return null // Token expired
                }
            }

            // Extract custom claims (exclude standard claims)
            val standardClaims = setOf("sub", "iss", "exp", "iat", "aud", "nbf", "jti")
            val customClaims = payload.entries
                .filter { it.key !in standardClaims }
                .associate { it.key to (it.value.jsonPrimitive.contentOrNull ?: it.value.toString()) }

            return JwtVerifyResult(
                subject = subject,
                issuer = issuer,
                expiresAt = exp?.let { it * 1000 },
                issuedAt = iat?.let { it * 1000 },
                claims = customClaims
            )
        } catch (@Suppress("UNUSED_PARAMETER") e: Exception) {
            return null
        }
    }
}

/**
 * Result of JWT verification.
 */
data class JwtVerifyResult(
    val subject: String,
    val issuer: String?,
    val expiresAt: Long?,
    val issuedAt: Long?,
    val claims: Map<String, String>
)

