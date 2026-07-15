package codes.yousef.aether.example

import codes.yousef.aether.auth.ApproveDeviceGrantRequest
import codes.yousef.aether.auth.Capability
import codes.yousef.aether.auth.ChallengeId
import codes.yousef.aether.auth.CredentialId
import codes.yousef.aether.auth.DeviceGrantId
import codes.yousef.aether.auth.DeviceGrantState
import codes.yousef.aether.auth.DeviceGrantView
import codes.yousef.aether.auth.InspectDeviceGrantRequest
import codes.yousef.aether.auth.InvitationId
import codes.yousef.aether.auth.MembershipId
import codes.yousef.aether.auth.OrganizationAccessView
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.PasskeyAuthenticationFinishRequest
import codes.yousef.aether.auth.PasskeyRegistrationFinishRequest
import codes.yousef.aether.auth.ServiceCredentialId
import codes.yousef.aether.auth.ServiceIdentityId
import codes.yousef.aether.auth.SessionId
import codes.yousef.aether.auth.summon.PasskeyBrowserClient
import codes.yousef.aether.auth.summon.PasskeyCeremonyClient
import codes.yousef.aether.auth.summon.PasskeyCeremonyGateway
import codes.yousef.aether.auth.webauthn.AuthenticationPublicKeyCredentialDto
import codes.yousef.aether.auth.webauthn.AuthenticatorAssertionResponseDto
import codes.yousef.aether.auth.webauthn.AuthenticatorAttestationResponseDto
import codes.yousef.aether.auth.webauthn.PublicKeyCredentialCreationOptions
import codes.yousef.aether.auth.webauthn.PublicKeyCredentialRequestOptions
import codes.yousef.aether.auth.webauthn.PublicKeyCredentialRpEntity
import codes.yousef.aether.auth.webauthn.PublicKeyCredentialUserEntity
import codes.yousef.aether.auth.webauthn.RegistrationPublicKeyCredentialDto
import codes.yousef.aether.auth.webauthn.WebAuthnAuthenticationStartResponse
import codes.yousef.aether.auth.webauthn.WebAuthnRegistrationStartResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentityExampleContractTest {
    private val organizationId = OrganizationId("018f47d2-8d4d-7abc-8def-1234567890ab")
    private val json = Json { encodeDefaults = true }

    @Test
    fun `example exposes fixed passkey organization and device routes`() {
        val contract = IdentityExampleContract()
        val encoded = json.encodeToString(contract).lowercase()

        assertTrue("/identity/v1/passkeys" in encoded)
        assertTrue("/identity/v1/passkeys/step-up/start" in encoded)
        assertTrue("/identity/v1/passkeys/step-up/finish" in encoded)
        assertTrue("/identity/v1/bootstrap" in encoded)
        assertTrue("/identity/v1/recovery/codes/use" in encoded)
        assertTrue("/identity/v1/recovery/codes/replace" in encoded)
        assertTrue("/identity/v1/invitations/enroll" in encoded)
        assertTrue("/identity/v1/device/approve" in encoded)
        assertTrue("/identity/v1/device/deny" in encoded)
        assertTrue("/oauth/device_authorization" in encoded)
        assertTrue("/oauth/token" in encoded)
        assertTrue(Capability("package.publish") in contract.serviceCredentialCapabilities)
        assertTrue(contract.serviceCredentialCapabilities.none {
            it in Capability.IDENTITY_MANAGEMENT || it == Capability.ACCOUNT_RECOVERY_ADMIN
        })
        assertEquals(
            "/identity/v1/organizations/${organizationId.value}/memberships",
            contract.memberships(organizationId)
        )
        assertEquals(
            "/identity/v1/organizations/${organizationId.value}/service-identities",
            contract.serviceIdentities(organizationId)
        )
        assertEquals("/identity/v1/passkeys/credential-id", contract.passkey(CredentialId("credential-id")))
        assertEquals("/identity/v1/sessions/session-id", contract.session(SessionId("session-id")))
        assertEquals(
            "/identity/v1/organizations/${organizationId.value}/memberships/membership-id",
            contract.membership(organizationId, MembershipId("membership-id"))
        )
        assertEquals(
            "/identity/v1/organizations/${organizationId.value}/invitations/invitation-id",
            contract.invitation(organizationId, InvitationId("invitation-id"))
        )
        assertEquals(
            "/identity/v1/organizations/${organizationId.value}/service-identities/service-id/credentials/" +
                "credential-id/rotate",
            contract.rotateServiceCredential(
                organizationId,
                ServiceIdentityId("service-id"),
                ServiceCredentialId("credential-id")
            )
        )
        assertEquals("/identity/bootstrap", contract.bootstrapUi)
        assertEquals("/identity/recovery", contract.recoveryUi)
        assertFalse("password" in encoded)
        assertFalse("jwt" in encoded)
        assertFalse("selectedorganization" in encoded)
    }

    @Test
    fun `wire requests exactly match the strict authority DTOs`() {
        val credential = RecordingBrowser().createCredential()
        val registration = PasskeyRegistrationFinishRequest(
            ceremonyId = ChallengeId("018f47d2-8d4d-7abc-8def-1234567890ac"),
            credentialName = "Laptop",
            credential = credential
        )
        val registrationJson = json.encodeToString(registration)
        assertTrue("\"credentialName\":\"Laptop\"" in registrationJson)

        val authentication = PasskeyAuthenticationFinishRequest(
            ceremonyId = ChallengeId("018f47d2-8d4d-7abc-8def-1234567890ad"),
            credential = RecordingBrowser().getCredential()
        )
        assertFalse("purpose" in json.encodeToString(authentication))

        val approval = ApproveDeviceGrantRequest(
            userCode = "ABCD-2345",
            organizationId = organizationId,
            capabilities = setOf(Capability("package.publish"))
        )
        assertEquals(
            "{\"userCode\":\"ABCD-2345\",\"organizationId\":\"${organizationId.value}\"," +
                "\"capabilities\":[\"package.publish\"]}",
            json.encodeToString(approval)
        )
        assertEquals(
            "{\"userCode\":\"ABCD-2345\"}",
            json.encodeToString(InspectDeviceGrantRequest("ABCD-2345"))
        )
    }

    @Test
    fun `reference client composes passkey organization and explicit device approval flows`() = runBlocking {
        val gateway = RecordingGateway(organizationId)
        val browser = RecordingBrowser()
        val client = IdentityExampleClient(PasskeyCeremonyClient(gateway, browser), gateway)

        client.registerPasskey("Laptop")
        client.discoverableSignIn()
        val organizations = client.organizations()
        val pendingGrant = client.resolveDevice("ABCD-2345")
        client.approveDevice("ABCD-2345", organizationId, setOf(Capability("package.publish")))

        assertEquals("Laptop", gateway.registrationName)
        assertTrue(gateway.registrationFinished)
        assertTrue(gateway.authenticationFinished)
        assertEquals(1, organizations.size)
        assertEquals("ABCD-2345", gateway.inspection?.userCode)
        assertEquals("Aether CLI", pendingGrant.clientName)
        assertEquals(organizationId, gateway.approval?.organizationId)
        assertEquals(setOf(Capability("package.publish")), gateway.approval?.capabilities)
    }

    private class RecordingGateway(
        private val organizationId: OrganizationId
    ) : PasskeyCeremonyGateway, IdentityExampleApi {
        var registrationName: String? = null
        var registrationFinished = false
        var authenticationFinished = false
        var inspection: InspectDeviceGrantRequest? = null
        var approval: ApproveDeviceGrantRequest? = null

        override suspend fun startRegistration(passkeyName: String): WebAuthnRegistrationStartResponse {
            registrationName = passkeyName
            return WebAuthnRegistrationStartResponse(
                ceremonyId = ChallengeId("018f47d2-8d4d-7abc-8def-1234567890ac"),
                publicKey = PublicKeyCredentialCreationOptions(
                    challenge = "AQID",
                    rp = PublicKeyCredentialRpEntity("localhost", "Aether example"),
                    user = PublicKeyCredentialUserEntity("dXNlcg", "user@example.test", "Example User"),
                    timeout = 300_000
                )
            )
        }

        override suspend fun finishRegistration(
            ceremonyId: ChallengeId,
            credential: RegistrationPublicKeyCredentialDto
        ) {
            registrationFinished = true
        }

        override suspend fun startAuthentication(
            purpose: codes.yousef.aether.auth.summon.PasskeyAuthenticationPurpose
        ): WebAuthnAuthenticationStartResponse = WebAuthnAuthenticationStartResponse(
            ceremonyId = ChallengeId("018f47d2-8d4d-7abc-8def-1234567890ad"),
            publicKey = PublicKeyCredentialRequestOptions(
                challenge = "BAUG",
                timeout = 300_000,
                rpId = "localhost"
            )
        )

        override suspend fun finishAuthentication(
            ceremonyId: ChallengeId,
            purpose: codes.yousef.aether.auth.summon.PasskeyAuthenticationPurpose,
            credential: AuthenticationPublicKeyCredentialDto
        ) {
            authenticationFinished = true
        }

        override suspend fun listOrganizations(): List<OrganizationAccessView> = listOf(
                OrganizationAccessView(
                    id = organizationId,
                    name = "Example Organization",
                    slug = "example-org",
                    role = "publisher"
                )
        )

        override suspend fun inspectDevice(request: InspectDeviceGrantRequest): DeviceGrantView {
            inspection = request
            return DeviceGrantView(
                id = DeviceGrantId("018f47d2-8d4d-7abc-8def-1234567890ae"),
                clientName = "Aether CLI",
                requestedCapabilities = setOf(Capability("package.publish")),
                state = DeviceGrantState.PENDING,
                createdAt = Instant.parse("2026-07-15T00:00:00Z"),
                expiresAt = Instant.parse("2026-07-15T00:10:00Z")
            )
        }

        override suspend fun approveDevice(request: ApproveDeviceGrantRequest) {
            approval = request
        }
    }

    private class RecordingBrowser : PasskeyBrowserClient {
        fun createCredential() = RegistrationPublicKeyCredentialDto(
            id = "AQID",
            rawId = "AQID",
            type = "public-key",
            response = AuthenticatorAttestationResponseDto("AQID", "AQID")
        )

        fun getCredential() = AuthenticationPublicKeyCredentialDto(
            id = "AQID",
            rawId = "AQID",
            type = "public-key",
            response = AuthenticatorAssertionResponseDto("AQID", "AQID", "AQID")
        )

        override suspend fun create(options: PublicKeyCredentialCreationOptions) = createCredential()

        override suspend fun get(options: PublicKeyCredentialRequestOptions) = getCredential()
    }
}
