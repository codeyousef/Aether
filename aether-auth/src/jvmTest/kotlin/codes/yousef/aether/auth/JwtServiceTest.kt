package codes.yousef.aether.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JwtServiceTest {
    @Test
    fun testGenerateAndVerifyToken() {
        val secret = "supersecretkey"
        val subject = "user123"
        val issuer = "aether-auth"
        val claims = mapOf("role" to "admin")

        // Generate token
        val token = JwtService.generateToken(
            subject = subject,
            secret = secret,
            issuer = issuer,
            claims = claims
        )

        assertNotNull(token)
        assertTrue(token.isNotEmpty())

        // Verify token
        val payload = JwtService.verifyToken(token, secret, issuer)
        assertNotNull(payload)
        assertEquals(subject, payload.subject)
        assertEquals(issuer, payload.issuer)
        assertEquals("admin", payload.claims["role"])
    }

    @Test
    fun testVerifyTokenWithWrongSecret() {
        val secret = "supersecretkey"
        val wrongSecret = "wrongsecret"
        val subject = "user123"

        val token = JwtService.generateToken(subject, secret)
        val payload = JwtService.verifyToken(token, wrongSecret)

        // Should return null (invalid signature)
        assertTrue(payload == null)
    }

    @Test
    fun testVerifyExpiredToken() {
        val secret = "secret"
        // Expired 1 second ago
        val token = JwtService.generateToken(
            subject = "user",
            secret = secret,
            expirationMillis = -1000
        )

        val payload = JwtService.verifyToken(token, secret)
        assertTrue(payload == null)
    }
}

