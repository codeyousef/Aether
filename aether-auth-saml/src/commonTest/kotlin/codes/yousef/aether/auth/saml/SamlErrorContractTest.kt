package codes.yousef.aether.auth.saml

import codes.yousef.aether.auth.ExternalSubject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

class SamlErrorContractTest {
    @Test
    fun `protocol errors own their stable message and retryability`() {
        val failure = SamlError(SamlErrorCode.STORE_UNAVAILABLE)

        assertEquals("The identity service is temporarily unavailable.", failure.message)
        assertTrue(failure.retryable)
        val encoded = Json.encodeToString(failure)
        assertTrue("\"message\":\"The identity service is temporarily unavailable.\"" in encoded)
        assertTrue("\"retryable\":true" in encoded)
        assertEquals(failure, Json.decodeFromString<SamlError>(encoded))
        assertFailsWith<IllegalArgumentException> {
            SamlError(SamlErrorCode.STORE_UNAVAILABLE, "assertion exception: secret", true)
        }
        assertFailsWith<IllegalArgumentException> {
            SamlError(SamlErrorCode.RESPONSE_INVALID, retryable = true)
        }
    }

    @Test
    fun `HTTP errors preserve the complete stable wire envelope`() {
        val failure = SamlFederationHttpError(
            code = SamlFederationHttpErrorCode.SERVICE_UNAVAILABLE,
            requestId = "request-123"
        )

        val encoded = Json.encodeToString(failure)

        assertTrue("\"code\":\"service_unavailable\"" in encoded)
        assertTrue("\"message\":\"The identity service is temporarily unavailable.\"" in encoded)
        assertTrue("\"requestId\":\"request-123\"" in encoded)
        assertTrue("\"retryable\":true" in encoded)
        assertEquals(failure, Json.decodeFromString<SamlFederationHttpError>(encoded))
        assertFailsWith<IllegalArgumentException> {
            SamlFederationHttpError(
                code = SamlFederationHttpErrorCode.REQUEST_INVALID,
                message = "assertion exception: secret",
                requestId = "request-123",
                retryable = false
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SamlFederationHttpError(
                code = SamlFederationHttpErrorCode.REQUEST_INVALID,
                requestId = "request-123",
                retryable = true
            )
        }
    }

    @Test
    fun `SAML verified-claim diagnostics redact claim PII`() {
        val marker = "never-print-this-saml-pii"
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val claims = SamlVerifiedClaims(
            issuer = "https://idp.example",
            subject = ExternalSubject(marker),
            nameIdFormat = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent",
            issuedAt = now,
            authenticatedAt = now,
            expiresAt = now,
            sessionIndex = marker,
            authenticationContext = marker,
            attributes = mapOf("email" to listOf("$marker@example.com"))
        )

        assertFalse(marker in claims.toString())
    }
}
