package codes.yousef.aether.auth

import codes.yousef.aether.auth.crypto.PureJwt

actual object JwtService {
    actual fun generateToken(
        subject: String,
        secret: String,
        issuer: String?,
        expirationMillis: Long,
        claims: Map<String, String>
    ): String {
        val now = SystemClock.now()
        return PureJwt.generate(
            subject = subject,
            secret = secret,
            issuer = issuer,
            expirationMillis = expirationMillis,
            issuedAtMillis = now,
            claims = claims
        )
    }

    actual fun verifyToken(token: String, secret: String, issuer: String?): JwtPayload? {
        val now = SystemClock.now()
        val result = PureJwt.verify(
            token = token,
            secret = secret,
            expectedIssuer = issuer,
            currentTimeMillis = now
        ) ?: return null

        return JwtPayload(
            subject = result.subject,
            issuer = result.issuer,
            expiresAt = result.expiresAt,
            issuedAt = result.issuedAt,
            claims = result.claims
        )
    }
}
