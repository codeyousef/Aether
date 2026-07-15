package codes.yousef.aether.example

import codes.yousef.aether.auth.IdentityErrorEnvelope
import codes.yousef.aether.auth.OAuthDeviceErrorCode
import codes.yousef.aether.auth.OAuthDeviceErrorResponse
import codes.yousef.aether.auth.PasskeyAuthenticationFinishRequest
import codes.yousef.aether.auth.webauthn.WebAuthnRegistrationStartResponse
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeenIdentityContractFixtureTest {
    private val json = Json { ignoreUnknownKeys = false }
    private val prefix = "seen-fel-634/"

    @Test
    fun `manifest enumerates every published Seen fixture`() {
        val manifest = decode<FixtureManifest>("manifest.json")

        assertEquals(1, manifest.schemaVersion)
        assertEquals("0.6.0.0", manifest.identityRelease)
        assertEquals("FEL-634", manifest.consumerIssue)
        assertEquals(EXPECTED_FIXTURES, manifest.files.toSet())
        manifest.files.forEach { resource(it) }
    }

    @Test
    fun `protocol fixtures decode with public identity DTOs`() {
        val registration = decode<WebAuthnRegistrationStartResponse>("passkey-registration-start.json")
        val authentication = decode<PasskeyAuthenticationFinishRequest>("passkey-authentication-finish.json")
        val pending = decode<OAuthDeviceErrorResponse>("device-authorization-pending.json")
        val notFound = decode<IdentityErrorEnvelope>("not-found-error.json")

        assertEquals("required", registration.publicKey.authenticatorSelection.residentKey)
        assertTrue(registration.publicKey.authenticatorSelection.requireResidentKey)
        assertEquals("required", registration.publicKey.authenticatorSelection.userVerification)
        assertEquals(-7, registration.publicKey.pubKeyCredParams.single().alg)
        assertEquals("public-key", authentication.credential.type)
        assertEquals(OAuthDeviceErrorCode.AUTHORIZATION_PENDING, pending.error)
        assertEquals(OAuthDeviceErrorCode.AUTHORIZATION_PENDING.publicMessage, pending.message)
        assertTrue(pending.retryable)
        assertTrue(pending.requestId.startsWith("req_"))
        assertEquals("not_found", notFound.error.code.wireName)
    }

    @Test
    fun `authorization fixtures are explicit organization scoped and secret free`() {
        val user = decode<SeenAuthorizationFixture>("user-publisher-context.json")
        val device = decode<SeenAuthorizationFixture>("device-publisher-context.json")
        val service = decode<SeenAuthorizationFixture>("service-publisher-context.json")

        assertEquals("publisher", user.organization.role)
        assertEquals("user", user.principal.kind)
        assertEquals("device", device.principal.kind)
        assertEquals("service", service.principal.kind)
        listOf(user, device, service).forEach { fixture ->
            assertEquals(1, fixture.schemaVersion)
            assertTrue("package.publish" in fixture.capabilities)
            assertEquals(fixture.capabilities.sorted(), fixture.capabilities)
            Instant.parse(fixture.principal.authenticatedAt)
        }

        EXPECTED_FIXTURES.forEach { name ->
            val element = json.parseToJsonElement(resource(name))
            assertSecretFree(element)
        }
    }

    private inline fun <reified T> decode(name: String): T = json.decodeFromString(resource(name))

    private fun resource(name: String): String = requireNotNull(
        javaClass.classLoader.getResource(prefix + name)
    ) { "Missing identity contract fixture $name" }.readText()

    private fun assertSecretFree(element: JsonElement) {
        if (element is JsonObject) {
            val normalizedKeys = element.keys.map { it.lowercase() }
            FORBIDDEN_KEYS.forEach { forbidden ->
                assertFalse(normalizedKeys.any { it == forbidden }, "Fixture exposed forbidden field $forbidden")
            }
        }
        when (element) {
            is JsonObject -> element.values.forEach(::assertSecretFree)
            is kotlinx.serialization.json.JsonArray -> element.forEach(::assertSecretFree)
            else -> Unit
        }
    }

    @Serializable
    private data class FixtureManifest(
        val schemaVersion: Int,
        val identityRelease: String,
        val consumerIssue: String,
        val files: List<String>
    )

    @Serializable
    private data class SeenAuthorizationFixture(
        val schemaVersion: Int,
        val principal: SeenPrincipalFixture,
        val organization: SeenOrganizationFixture,
        val capabilities: List<String>
    )

    @Serializable
    private data class SeenPrincipalFixture(
        val kind: String,
        val userId: String? = null,
        val serviceIdentityId: String? = null,
        val displayName: String,
        val assurance: String,
        val authenticatedAt: String
    )

    @Serializable
    private data class SeenOrganizationFixture(val id: String, val role: String? = null)

    private companion object {
        val EXPECTED_FIXTURES = setOf(
            "passkey-registration-start.json",
            "passkey-authentication-finish.json",
            "user-publisher-context.json",
            "device-publisher-context.json",
            "service-publisher-context.json",
            "device-authorization-pending.json",
            "not-found-error.json"
        )
        val FORBIDDEN_KEYS = setOf(
            "password",
            "passwordhash",
            "token",
            "accesstoken",
            "refreshtoken",
            "tokendigest",
            "secretdigest",
            "recoverycode",
            "assertion",
            "rawip"
        )
    }
}
