package codes.yousef.aether.auth

import kotlinx.serialization.Serializable

@Serializable
data class RenamePasskeyRequest(val name: String)

@Serializable
data class IdentityMeResponse(
    val userId: UserId,
    val displayName: String,
    val assuranceLevel: AuthenticationAssurance
)

@Serializable
data class ReplaceRecoveryCodesRequest(val expectedGeneration: Long? = null) {
    init { require(expectedGeneration == null || expectedGeneration >= 0) { "Invalid recovery generation" } }
}

@Serializable
data class RecoveryCodesResponse(val generation: Long, val codes: List<String>) {
    init { require(generation >= 0 && codes.size == 10) { "Invalid recovery-code response" } }

    override fun toString(): String =
        "RecoveryCodesResponse(generation=$generation, codes=<redacted>)"
}

@Serializable
data class AdministrativeRecoveryIssueRequest(val userId: UserId)

@Serializable
data class AdministrativeRecoveryRedeemRequest(
    val token: String,
    val deviceLabel: String? = null,
    val devicePlatform: String? = null
) {
    init {
        require(token.length in 20..512) { "Invalid administrative recovery ticket" }
        require(deviceLabel == null || deviceLabel.length <= 200) { "Device label is too long" }
        require(devicePlatform == null || devicePlatform.length <= 200) { "Device platform is too long" }
    }

    override fun toString(): String =
        "AdministrativeRecoveryRedeemRequest(token=<redacted>, " +
            "deviceLabel=${if (deviceLabel == null) "none" else "<redacted>"}, " +
            "devicePlatform=${if (devicePlatform == null) "none" else "<redacted>"})"
}

@Serializable
data class CreateOrganizationRequest(val name: String, val slug: String)

@Serializable
data class UpdateOrganizationRequest(val name: String)

@Serializable
data class UpdateMembershipRoleRequest(val role: OrganizationRole)

@Serializable
data class CreateInvitationRequest(val email: EmailAddress, val role: OrganizationRole)

@Serializable
data class AcceptInvitationRequest(val token: String) {
    init { require(token.length in 20..512) { "Invalid invitation token" } }

    override fun toString(): String = "AcceptInvitationRequest(token=<redacted>)"
}

@Serializable
data class EnrollInvitationRequest(val token: String, val displayName: String) {
    init {
        require(token.length in 20..512) { "Invalid invitation token" }
        require(displayName.isNotBlank() && displayName.length <= 200) { "Invalid display name" }
    }

    override fun toString(): String =
        "EnrollInvitationRequest(token=<redacted>, displayName=<redacted>)"
}

/** Safe onboarding response; session and invitation credentials are never serialized. */
@Serializable
data class InvitationEnrollmentResponse(
    val userId: UserId,
    val organizationId: OrganizationId,
    val membership: Membership,
    val sessionId: SessionId,
    val assurance: AuthenticationAssurance,
    val authenticatedAt: kotlin.time.Instant,
    val idleExpiresAt: kotlin.time.Instant,
    val absoluteExpiresAt: kotlin.time.Instant,
    val csrfToken: String
) {
    override fun toString(): String =
        "InvitationEnrollmentResponse(userId=$userId, organizationId=$organizationId, " +
            "membership=${membership.id}, sessionId=$sessionId, assurance=$assurance, " +
            "authenticatedAt=$authenticatedAt, idleExpiresAt=$idleExpiresAt, " +
            "absoluteExpiresAt=$absoluteExpiresAt, csrfToken=<redacted>)"
}

/** Bounded safe tenant audit response. The cursor is opaque and contains no credential material. */
@Serializable
data class OrganizationAuditEventsResponse(
    val events: List<AuditEventView>,
    val nextCursor: String? = null
) {
    init {
        require(events.size <= OrganizationAuditEventPageRequest.MAXIMUM_LIMIT) { "Audit page is too large" }
        require(nextCursor == null || OrganizationAuditEventCursor.fromOpaqueToken(nextCursor) != null) {
            "Invalid audit page cursor"
        }
    }
}

@Serializable
data class IssuedInvitationResponse(val invitation: InvitationView, val token: String) {
    override fun toString(): String =
        "IssuedInvitationResponse(invitation=${invitation.id}, token=<redacted>)"
}

/** Safe projection shown after entering an RFC 8628 user code. */
@Serializable
data class DeviceGrantView(
    val id: DeviceGrantId,
    val clientName: String,
    val requestedCapabilities: Set<Capability>,
    val state: DeviceGrantState,
    val createdAt: kotlin.time.Instant,
    val expiresAt: kotlin.time.Instant
)

/** Bounded, redacted browser lookup for a pending RFC 8628 grant. */
@Serializable
data class InspectDeviceGrantRequest(val userCode: String) {
    init {
        require(userCode.isNotBlank() && userCode.length <= 64) { "Invalid device user code" }
    }

    override fun toString(): String = "InspectDeviceGrantRequest(userCode=<redacted>)"
}

@Serializable
data class ApproveDeviceGrantRequest(
    val userCode: String,
    val organizationId: OrganizationId,
    val capabilities: Set<Capability>
) {
    override fun toString(): String =
        "ApproveDeviceGrantRequest(userCode=<redacted>, organizationId=$organizationId, capabilities=$capabilities)"
}

@Serializable
data class DenyDeviceGrantRequest(val userCode: String) {
    override fun toString(): String = "DenyDeviceGrantRequest(userCode=<redacted>)"
}

@Serializable
data class CancelDeviceGrantRequest(val deviceCode: String) {
    override fun toString(): String = "CancelDeviceGrantRequest(deviceCode=<redacted>)"
}

@Serializable
data class CreateServiceIdentityRequest(
    val name: String,
    val description: String? = null,
    val capabilities: Set<Capability>,
    val lifetimeSeconds: Long? = null
) {
    init { require(lifetimeSeconds == null || lifetimeSeconds > 0) { "Invalid credential lifetime" } }
}

@Serializable
data class CreateServiceCredentialRequest(
    val capabilities: Set<Capability>,
    val lifetimeSeconds: Long? = null
) {
    init { require(lifetimeSeconds == null || lifetimeSeconds > 0) { "Invalid credential lifetime" } }
}

@Serializable
data class RotateServiceCredentialRequest(val lifetimeSeconds: Long? = null) {
    init { require(lifetimeSeconds == null || lifetimeSeconds > 0) { "Invalid credential lifetime" } }
}

@Serializable
data class IssuedServiceIdentityResponse(
    val identity: ServiceIdentity,
    val credential: ServiceCredentialView,
    val token: String
) {
    override fun toString(): String =
        "IssuedServiceIdentityResponse(identity=${identity.id}, credential=${credential.id}, token=<redacted>)"
}

@Serializable
data class IssuedServiceCredentialResponse(val credential: ServiceCredentialView, val token: String) {
    override fun toString(): String =
        "IssuedServiceCredentialResponse(credential=${credential.id}, token=<redacted>)"
}

@Serializable
data class BootstrapIdentityRequest(
    val secret: String,
    val displayName: String,
    val primaryEmail: EmailAddress,
    val organizationName: String,
    val organizationSlug: String
) {
    override fun toString(): String =
        "BootstrapIdentityRequest(secret=<redacted>, displayName=<redacted>, primaryEmail=<redacted>, " +
            "organizationName=$organizationName, organizationSlug=$organizationSlug)"
}

/** Bootstrap never serializes session epochs, digests, or any configured bootstrap material. */
@Serializable
data class BootstrapIdentityResponse(
    val userId: UserId,
    val organization: Organization,
    val ownerMembership: Membership,
    val sessionId: SessionId,
    val idleExpiresAt: kotlin.time.Instant,
    val absoluteExpiresAt: kotlin.time.Instant,
    val csrfToken: String
) {
    override fun toString(): String =
        "BootstrapIdentityResponse(userId=$userId, organization=${organization.id}, " +
            "ownerMembership=${ownerMembership.id}, sessionId=$sessionId, " +
            "idleExpiresAt=$idleExpiresAt, absoluteExpiresAt=$absoluteExpiresAt, csrfToken=<redacted>)"
}

/** Optional core management services, kept separate to preserve the original API constructor. */
data class IdentityHttpManagementServices(
    val organizations: IdentityOrganizationService? = null,
    val serviceIdentities: IdentityServiceIdentityService? = null,
    val administrativeRecovery: IdentityAdministrativeRecoveryService? = null,
    val bootstrap: IdentityBootstrapService? = null
)
