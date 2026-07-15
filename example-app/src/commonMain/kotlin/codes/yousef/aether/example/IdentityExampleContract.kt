package codes.yousef.aether.example

import codes.yousef.aether.auth.ApproveDeviceGrantRequest
import codes.yousef.aether.auth.Capability
import codes.yousef.aether.auth.ChallengeId
import codes.yousef.aether.auth.CredentialId
import codes.yousef.aether.auth.DeviceGrantView
import codes.yousef.aether.auth.InspectDeviceGrantRequest
import codes.yousef.aether.auth.InvitationId
import codes.yousef.aether.auth.MembershipId
import codes.yousef.aether.auth.OrganizationAccessView
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.ServiceCredentialId
import codes.yousef.aether.auth.ServiceIdentityId
import codes.yousef.aether.auth.SessionId
import codes.yousef.aether.auth.summon.PasskeyAuthenticationPurpose
import codes.yousef.aether.auth.summon.PasskeyCeremonyClient
import kotlinx.serialization.Serializable

/**
 * Public, secret-free routes consumed by the JVM-rendered and wasmJs-hydrated example.
 *
 * Organization IDs are always explicit path segments. There is intentionally no selected-
 * organization endpoint or workspace-global bearer token.
 */
@Serializable
data class IdentityExampleContract(
    val identityApi: String = "/identity/v1",
    val identityUi: String = "/identity",
    val bootstrapUi: String = "/identity/bootstrap",
    val recoveryUi: String = "/identity/recovery",
    val clientConfig: String = "/identity/v1/client-config",
    val me: String = "/identity/v1/me",
    val bootstrap: String = "/identity/v1/bootstrap",
    val recoveryCodeUse: String = "/identity/v1/recovery/codes/use",
    val recoveryCodesReplace: String = "/identity/v1/recovery/codes/replace",
    val invitationEnrollment: String = "/identity/v1/invitations/enroll",
    val registrationStart: String = "/identity/v1/passkeys/registration/start",
    val registrationFinish: String = "/identity/v1/passkeys/registration/finish",
    val authenticationStart: String = "/identity/v1/passkeys/authentication/start",
    val authenticationFinish: String = "/identity/v1/passkeys/authentication/finish",
    val stepUpStart: String = "/identity/v1/passkeys/step-up/start",
    val stepUpFinish: String = "/identity/v1/passkeys/step-up/finish",
    val passkeys: String = "/identity/v1/passkeys",
    val sessions: String = "/identity/v1/sessions",
    val logout: String = "/identity/v1/logout",
    val revokeOtherSessions: String = "/identity/v1/sessions/revoke-others",
    val revokeAllSessions: String = "/identity/v1/sessions/revoke-all",
    val administrativeRecoveryTickets: String = "/identity/v1/recovery/admin/tickets",
    val organizations: String = "/identity/v1/organizations",
    val deviceVerification: String = "/identity/v1/device",
    val deviceApproval: String = "/identity/v1/device/approve",
    val deviceDenial: String = "/identity/v1/device/deny",
    val deviceAuthorizationEndpoint: String = "/oauth/device_authorization",
    val deviceTokenEndpoint: String = "/oauth/token",
    val administrativeRecoveryEnabled: Boolean = false,
    val serviceCredentialCapabilities: Set<Capability> = setOf(
        Capability.CONTENT_READ,
        Capability.CONTENT_PUBLISH,
        Capability("package.publish")
    )
) {
    init {
        require(serviceCredentialCapabilities.isNotEmpty()) {
            "The public service-credential capability allowlist must not be empty"
        }
        require(serviceCredentialCapabilities.none {
            it in Capability.IDENTITY_MANAGEMENT || it == Capability.ACCOUNT_RECOVERY_ADMIN
        }) {
            "The public service-credential allowlist may contain application capabilities only"
        }
        listOf(
            identityApi,
            identityUi,
            bootstrapUi,
            recoveryUi,
            clientConfig,
            me,
            bootstrap,
            recoveryCodeUse,
            recoveryCodesReplace,
            invitationEnrollment,
            registrationStart,
            registrationFinish,
            authenticationStart,
            authenticationFinish,
            stepUpStart,
            stepUpFinish,
            passkeys,
            sessions,
            logout,
            revokeOtherSessions,
            revokeAllSessions,
            administrativeRecoveryTickets,
            organizations,
            deviceVerification,
            deviceApproval,
            deviceDenial,
            deviceAuthorizationEndpoint,
            deviceTokenEndpoint
        ).forEach { require(it.startsWith('/') && !it.contains("//")) { "Identity routes must be absolute paths" } }
    }

    fun organization(organizationId: OrganizationId): String = "$organizations/${organizationId.value}"

    fun passkey(credentialId: CredentialId): String = "$passkeys/${credentialId.value}"

    fun session(sessionId: SessionId): String = "$sessions/${sessionId.value}"

    fun administrativeRecoveryTicket(ticketId: ChallengeId): String =
        "$administrativeRecoveryTickets/${ticketId.value}"

    fun memberships(organizationId: OrganizationId): String = "${organization(organizationId)}/memberships"

    fun membership(organizationId: OrganizationId, membershipId: MembershipId): String =
        "${memberships(organizationId)}/${membershipId.value}"

    fun invitations(organizationId: OrganizationId): String = "${organization(organizationId)}/invitations"

    fun invitation(organizationId: OrganizationId, invitationId: InvitationId): String =
        "${invitations(organizationId)}/${invitationId.value}"

    fun serviceIdentities(organizationId: OrganizationId): String =
        "${organization(organizationId)}/service-identities"

    fun serviceIdentity(organizationId: OrganizationId, serviceIdentityId: ServiceIdentityId): String =
        "${serviceIdentities(organizationId)}/${serviceIdentityId.value}"

    fun serviceCredentials(organizationId: OrganizationId, serviceIdentityId: ServiceIdentityId): String =
        "${serviceIdentity(organizationId, serviceIdentityId)}/credentials"

    fun serviceCredential(
        organizationId: OrganizationId,
        serviceIdentityId: ServiceIdentityId,
        credentialId: ServiceCredentialId
    ): String = "${serviceCredentials(organizationId, serviceIdentityId)}/${credentialId.value}"

    fun rotateServiceCredential(
        organizationId: OrganizationId,
        serviceIdentityId: ServiceIdentityId,
        credentialId: ServiceCredentialId
    ): String = "${serviceCredential(organizationId, serviceIdentityId, credentialId)}/rotate"
}

/**
 * Host-owned JSON boundary for organization and browser approval actions. Opaque CLI access and
 * refresh tokens never enter the browser UI client.
 */
interface IdentityExampleApi {
    suspend fun listOrganizations(): List<OrganizationAccessView>
    suspend fun inspectDevice(request: InspectDeviceGrantRequest): DeviceGrantView
    suspend fun approveDevice(request: ApproveDeviceGrantRequest)
}

/** Compile-tested reference flow shared by JVM tests and the wasmJs browser entry point. */
class IdentityExampleClient(
    private val passkeys: PasskeyCeremonyClient,
    private val api: IdentityExampleApi
) {
    suspend fun registerPasskey(name: String) = passkeys.register(name)

    suspend fun discoverableSignIn() = passkeys.authenticate(PasskeyAuthenticationPurpose.DISCOVERABLE_SIGN_IN)

    suspend fun stepUp() = passkeys.authenticate(PasskeyAuthenticationPurpose.STEP_UP)

    suspend fun organizations(): List<OrganizationAccessView> = api.listOrganizations()

    suspend fun resolveDevice(userCode: String): DeviceGrantView =
        api.inspectDevice(InspectDeviceGrantRequest(userCode))

    suspend fun approveDevice(
        userCode: String,
        organizationId: OrganizationId,
        capabilities: Set<Capability>
    ) = api.approveDevice(
        ApproveDeviceGrantRequest(
            userCode = userCode,
            organizationId = organizationId,
            capabilities = capabilities.sortedBy { it.wireName }.toCollection(linkedSetOf())
        )
    )
}
