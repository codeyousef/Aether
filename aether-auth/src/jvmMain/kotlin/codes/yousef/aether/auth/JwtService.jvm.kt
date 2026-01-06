package codes.yousef.aether.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

actual object JwtService {
    actual fun generateToken(
        subject: String,
        secret: String,
        issuer: String?,
        expirationMillis: Long,
        claims: Map<String, String>
    ): String {
        val algorithm = Algorithm.HMAC256(secret)
        val now = System.currentTimeMillis()
        val builder = JWT.create()
            .withSubject(subject)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + expirationMillis))

        if (issuer != null) {
            builder.withIssuer(issuer)
        }

        claims.forEach { (k, v) -> builder.withClaim(k, v) }

        return builder.sign(algorithm)
    }

    actual fun verifyToken(token: String, secret: String, issuer: String?): JwtPayload? {
        try {
            val algorithm = Algorithm.HMAC256(secret)
            val verifierBuilder = JWT.require(algorithm)

            if (issuer != null) {
                verifierBuilder.withIssuer(issuer)
            }

            val verifier = verifierBuilder.build()
            val jwt = verifier.verify(token)

            val claimsMap = jwt.claims
                .filterKeys { it !in setOf("sub", "iss", "exp", "iat") }
                .mapValues { it.value.asString() ?: it.value.toString() }

            return JwtPayload(
                subject = jwt.subject,
                issuer = jwt.issuer,
                expiresAt = jwt.expiresAt?.time,
                issuedAt = jwt.issuedAt?.time,
                claims = claimsMap
            )
        } catch (e: Exception) {
            return null
        }
    }
}

