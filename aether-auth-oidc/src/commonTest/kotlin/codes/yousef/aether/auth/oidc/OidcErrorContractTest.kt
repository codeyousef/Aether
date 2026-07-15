package codes.yousef.aether.auth.oidc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OidcErrorContractTest {
    @Test
    fun `protocol errors own their stable message and retryability`() {
        val failure = OidcError(OidcErrorCode.STORE_UNAVAILABLE)

        assertEquals("The identity service is temporarily unavailable.", failure.message)
        assertTrue(failure.retryable)
        val encoded = Json.encodeToString(failure)
        assertTrue("\"message\":\"The identity service is temporarily unavailable.\"" in encoded)
        assertTrue("\"retryable\":true" in encoded)
        assertEquals(failure, Json.decodeFromString<OidcError>(encoded))
        assertFailsWith<IllegalArgumentException> {
            OidcError(OidcErrorCode.STORE_UNAVAILABLE, "provider exception: secret", true)
        }
        assertFailsWith<IllegalArgumentException> {
            OidcError(OidcErrorCode.INVALID_CALLBACK, retryable = true)
        }
    }

    @Test
    fun `HTTP errors preserve the complete stable wire envelope`() {
        val failure = OidcFederationHttpError(
            code = OidcFederationHttpErrorCode.SERVICE_UNAVAILABLE,
            requestId = "request-123"
        )

        val encoded = Json.encodeToString(failure)

        assertTrue("\"code\":\"service_unavailable\"" in encoded)
        assertTrue("\"message\":\"The identity service is temporarily unavailable.\"" in encoded)
        assertTrue("\"requestId\":\"request-123\"" in encoded)
        assertTrue("\"retryable\":true" in encoded)
        assertEquals(failure, Json.decodeFromString<OidcFederationHttpError>(encoded))
        assertFailsWith<IllegalArgumentException> {
            OidcFederationHttpError(
                code = OidcFederationHttpErrorCode.REQUEST_INVALID,
                message = "provider exception: secret",
                requestId = "request-123",
                retryable = false
            )
        }
        assertFailsWith<IllegalArgumentException> {
            OidcFederationHttpError(
                code = OidcFederationHttpErrorCode.REQUEST_INVALID,
                requestId = "request-123",
                retryable = true
            )
        }
    }
}
