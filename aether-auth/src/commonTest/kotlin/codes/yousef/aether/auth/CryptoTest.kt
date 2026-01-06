package codes.yousef.aether.auth

import codes.yousef.aether.auth.crypto.Base64Url
import codes.yousef.aether.auth.crypto.HmacSha256
import codes.yousef.aether.auth.crypto.PureJwt
import codes.yousef.aether.auth.crypto.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {

    @Test
    fun testBase64UrlEncode() {
        // Test basic encoding
        assertEquals("SGVsbG8", Base64Url.encodeToString("Hello"))
        assertEquals("SGVsbG8gV29ybGQ", Base64Url.encodeToString("Hello World"))
        assertEquals("", Base64Url.encode(ByteArray(0)))
    }

    @Test
    fun testBase64UrlDecode() {
        // Test basic decoding
        assertEquals("Hello", Base64Url.decodeToString("SGVsbG8"))
        assertEquals("Hello World", Base64Url.decodeToString("SGVsbG8gV29ybGQ"))
    }

    @Test
    fun testBase64UrlRoundTrip() {
        val testCases = listOf(
            "Hello",
            "Hello World!",
            "The quick brown fox jumps over the lazy dog",
            """{"sub":"user123","iat":1234567890}""",
            "a",
            "ab",
            "abc"
        )

        for (input in testCases) {
            val encoded = Base64Url.encodeToString(input)
            val decoded = Base64Url.decodeToString(encoded)
            assertEquals(input, decoded, "Round trip failed for: $input")
        }
    }

    @Test
    fun testSha256KnownVector() {
        // Known test vector: SHA-256 of empty string
        val emptyHash = Sha256.hash(ByteArray(0))
        val emptyHashHex = emptyHash.toHexString()
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            emptyHashHex
        )

        // SHA-256 of "abc"
        val abcHash = Sha256.hash("abc".encodeToByteArray())
        val abcHashHex = abcHash.toHexString()
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            abcHashHex
        )
    }

    @Test
    fun testHmacSha256KnownVector() {
        // Known test vector from RFC 4231
        val key = "key".encodeToByteArray()
        val data = "The quick brown fox jumps over the lazy dog".encodeToByteArray()
        val hmac = HmacSha256.compute(key, data)
        val hmacHex = hmac.toHexString()
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            hmacHex
        )
    }

    private fun ByteArray.toHexString(): String = joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

class PureJwtTest {

    @Test
    fun testGenerateToken() {
        val token = PureJwt.generate(
            subject = "user123",
            secret = "testsecret",
            issuer = "test-issuer",
            expirationMillis = 3600_000,
            issuedAtMillis = 1704067200000, // 2024-01-01 00:00:00 UTC
            claims = mapOf("role" to "admin")
        )

        assertNotNull(token)
        assertTrue(token.contains("."))
        assertEquals(3, token.split(".").size)
    }

    @Test
    fun testVerifyValidToken() {
        val secret = "testsecret"
        val issuer = "test-issuer"
        val issuedAt = 1704067200000L // 2024-01-01 00:00:00 UTC
        val expiration = 3600_000L // 1 hour

        val token = PureJwt.generate(
            subject = "user123",
            secret = secret,
            issuer = issuer,
            expirationMillis = expiration,
            issuedAtMillis = issuedAt,
            claims = mapOf("role" to "admin")
        )

        // Verify with time before expiration
        val verifyTime = issuedAt + 1800_000 // 30 minutes later
        val result = PureJwt.verify(token, secret, issuer, verifyTime)

        assertNotNull(result)
        assertEquals("user123", result.subject)
        assertEquals(issuer, result.issuer)
        assertEquals("admin", result.claims["role"])
    }

    @Test
    fun testVerifyExpiredToken() {
        val secret = "testsecret"
        val issuedAt = 1704067200000L
        val expiration = 3600_000L // 1 hour

        val token = PureJwt.generate(
            subject = "user123",
            secret = secret,
            issuer = null,
            expirationMillis = expiration,
            issuedAtMillis = issuedAt,
            claims = emptyMap()
        )

        // Verify with time after expiration
        val verifyTime = issuedAt + 7200_000 // 2 hours later
        val result = PureJwt.verify(token, secret, null, verifyTime)

        assertNull(result, "Expired token should not verify")
    }

    @Test
    fun testVerifyWrongSecret() {
        val issuedAt = 1704067200000L

        val token = PureJwt.generate(
            subject = "user123",
            secret = "correct-secret",
            issuer = null,
            expirationMillis = 3600_000,
            issuedAtMillis = issuedAt,
            claims = emptyMap()
        )

        val result = PureJwt.verify(token, "wrong-secret", null, issuedAt + 1000)

        assertNull(result, "Token with wrong secret should not verify")
    }

    @Test
    fun testVerifyWrongIssuer() {
        val secret = "testsecret"
        val issuedAt = 1704067200000L

        val token = PureJwt.generate(
            subject = "user123",
            secret = secret,
            issuer = "issuer-a",
            expirationMillis = 3600_000,
            issuedAtMillis = issuedAt,
            claims = emptyMap()
        )

        val result = PureJwt.verify(token, secret, "issuer-b", issuedAt + 1000)

        assertNull(result, "Token with wrong issuer should not verify")
    }

    @Test
    fun testVerifyMalformedToken() {
        val result1 = PureJwt.verify("not.a.valid.token.at.all", "secret", null, 0)
        assertNull(result1)

        val result2 = PureJwt.verify("invalid", "secret", null, 0)
        assertNull(result2)

        val result3 = PureJwt.verify("", "secret", null, 0)
        assertNull(result3)
    }
}
