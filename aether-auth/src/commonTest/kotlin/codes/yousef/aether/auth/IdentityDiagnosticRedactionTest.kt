package codes.yousef.aether.auth

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class IdentityDiagnosticRedactionTest {
    @Test
    fun `public secret-bearing identity DTOs redact diagnostics`() {
        val marker = SECRET_MARKER
        val now = Instant.parse("2026-07-15T00:00:00Z")
        val later = Instant.parse("2026-07-15T01:00:00Z")
        val userId = UserId("usr_redaction")
        val organizationId = OrganizationId("org_redaction")
        val membership = Membership(
            id = MembershipId("mem_redaction"),
            organizationId = organizationId,
            userId = userId,
            role = OrganizationRole.OWNER,
            createdAt = now,
            updatedAt = now
        )
        val organization = Organization(
            id = organizationId,
            name = "Diagnostic organization",
            slug = "diagnostic-organization",
            createdAt = now,
            updatedAt = now
        )
        val invitation = InvitationView(
            id = InvitationId("inv_redaction"),
            organizationId = organizationId,
            email = EmailAddress("$marker@example.com"),
            role = OrganizationRole.VIEWER,
            state = InvitationState.PENDING,
            createdAt = now,
            expiresAt = later
        )
        val serviceIdentity = ServiceIdentity(
            id = ServiceIdentityId("svc_redaction"),
            organizationId = organizationId,
            name = "Diagnostic service",
            capabilities = setOf(Capability.ORGANIZATION_READ),
            createdAt = now,
            updatedAt = now
        )
        val serviceCredential = ServiceCredentialView(
            id = ServiceCredentialId("scc_redaction"),
            serviceIdentityId = serviceIdentity.id,
            publicPrefix = "public-prefix",
            capabilities = setOf(Capability.ORGANIZATION_READ),
            state = ServiceCredentialState.ACTIVE,
            createdAt = now,
            expiresAt = later
        )
        val passkey = PasskeyView(
            id = CredentialId("cred_redaction"),
            name = marker,
            transports = setOf(AuthenticatorTransport.USB),
            backupEligible = false,
            backedUp = false,
            state = CredentialState.ACTIVE,
            createdAt = now
        )
        val registrationCredential = RegistrationPublicKeyCredentialDto(
            id = marker,
            rawId = marker,
            type = "public-key",
            response = AuthenticatorAttestationResponseDto(marker, marker, listOf("usb"))
        )
        val authenticationCredential = AuthenticationPublicKeyCredentialDto(
            id = marker,
            rawId = marker,
            type = "public-key",
            response = AuthenticatorAssertionResponseDto(marker, marker, marker, marker)
        )

        val values = listOf(
            DeviceAuthorizationResponse(
                deviceCode = marker,
                userCode = marker,
                verificationUri = "https://identity.example/identity",
                expiresIn = 600,
                interval = 5
            ),
            OAuthDeviceTokenResponse(marker, expiresIn = 900, refreshToken = marker, scope = "organization.read"),
            RecoveryCodesResponse(1, List(10) { marker }),
            RecoveryCodeUseRequest(marker, marker, marker),
            AdministrativeRecoveryRedeemRequest(marker, marker, marker),
            AcceptInvitationRequest(marker),
            EnrollInvitationRequest(marker, marker),
            IssuedInvitationResponse(invitation, marker),
            InspectDeviceGrantRequest(marker),
            ApproveDeviceGrantRequest(marker, organizationId, setOf(Capability.ORGANIZATION_READ)),
            DenyDeviceGrantRequest(marker),
            CancelDeviceGrantRequest(marker),
            IssuedServiceIdentityResponse(serviceIdentity, serviceCredential, marker),
            IssuedServiceCredentialResponse(serviceCredential, marker),
            BootstrapIdentityRequest(
                secret = marker,
                displayName = marker,
                primaryEmail = EmailAddress("$marker@example.com"),
                organizationName = "Diagnostic organization",
                organizationSlug = "diagnostic-organization"
            ),
            InvitationEnrollmentResponse(
                userId = userId,
                organizationId = organizationId,
                membership = membership,
                sessionId = SessionId("ses_invitation"),
                assurance = AuthenticationAssurance.RECOVERY,
                authenticatedAt = now,
                idleExpiresAt = later,
                absoluteExpiresAt = later,
                csrfToken = marker
            ),
            BootstrapIdentityResponse(
                userId = userId,
                organization = organization,
                ownerMembership = membership,
                sessionId = SessionId("ses_bootstrap"),
                idleExpiresAt = later,
                absoluteExpiresAt = later,
                csrfToken = marker
            ),
            IdentitySessionCreatedResponse(
                userId = userId,
                sessionId = SessionId("ses_passkey"),
                assurance = AuthenticationAssurance.PASSKEY,
                authenticatedAt = now,
                idleExpiresAt = later,
                absoluteExpiresAt = later,
                csrfToken = marker
            ),
            PasskeyRegistrationResponse(passkey, List(10) { marker }),
            PasskeyRegistrationFinishRequest(ChallengeId("cha_registration"), marker, registrationCredential),
            PasskeyAuthenticationFinishRequest(
                ChallengeId("cha_authentication"), authenticationCredential, marker, marker
            ),
            registrationCredential,
            registrationCredential.response,
            authenticationCredential,
            authenticationCredential.response,
            DeviceMetadata(label = marker, platform = marker, userAgent = marker),
            PublicKeyCredentialUserEntity(marker, marker, marker),
            PublicKeyCredentialCreationOptions(
                challenge = marker,
                rp = PublicKeyCredentialRpEntity("identity.example", "Identity"),
                user = PublicKeyCredentialUserEntity(marker, marker, marker),
                timeout = 300_000
            ),
            PublicKeyCredentialRequestOptions(
                challenge = marker,
                timeout = 300_000,
                rpId = "identity.example"
            ),
            WebAuthnRegistrationStartResponse(
                ChallengeId("cha_registration_start"),
                PublicKeyCredentialCreationOptions(
                    challenge = marker,
                    rp = PublicKeyCredentialRpEntity("identity.example", "Identity"),
                    user = PublicKeyCredentialUserEntity(marker, marker, marker),
                    timeout = 300_000
                )
            ),
            WebAuthnAuthenticationStartResponse(
                ChallengeId("cha_authentication_start"),
                PublicKeyCredentialRequestOptions(marker, 300_000, "identity.example")
            )
        )

        values.forEach { value ->
            assertFalse(marker in value.toString(), "A public identity DTO leaked secret diagnostic material")
        }
    }

    @Test
    fun `diagnostic redaction does not change secret wire fields`() {
        val response = DeviceAuthorizationResponse(
            deviceCode = SECRET_MARKER,
            userCode = SECRET_MARKER,
            verificationUri = "https://identity.example/identity",
            expiresIn = 600,
            interval = 5
        )

        val encoded = Json.encodeToString(response)

        assertTrue(SECRET_MARKER in encoded)
        assertTrue("\"device_code\"" in encoded)
        assertTrue("\"user_code\"" in encoded)
        assertEquals(response, Json.decodeFromString<DeviceAuthorizationResponse>(encoded))
    }

    private companion object {
        const val SECRET_MARKER = "never-print-this-sensitive-value"
    }
}
