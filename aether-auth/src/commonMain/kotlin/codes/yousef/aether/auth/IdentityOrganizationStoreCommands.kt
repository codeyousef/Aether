package codes.yousef.aether.auth

import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable

@Serializable
data class CreateOrganizationCommand(
    val organization: Organization,
    val ownerMembership: Membership,
    val auditEvent: AuditEvent
) {
    init {
        require(organization.version == 0L && organization.state == OrganizationState.ACTIVE) {
            "A new organization must be active at version zero"
        }
        require(ownerMembership.version == 0L && ownerMembership.state == MembershipState.ACTIVE &&
            ownerMembership.role == OrganizationRole.OWNER && ownerMembership.organizationId == organization.id
        ) { "Organization creation requires one active owner membership at version zero" }
        require(auditEvent.action == AuditAction.ORGANIZATION_CREATED &&
            auditEvent.target == AuditTarget(AuditTargetType.ORGANIZATION, organization.id.value)
        ) { "Organization creation requires an organization-created audit event" }
    }
}

@Serializable
data class OrganizationCreationCommit(
    val organization: Organization,
    val ownerMembership: Membership,
    val auditEvent: AuditEvent
)

@Serializable
data class MutateOrganizationCommand(
    val organizationId: OrganizationId,
    val expectedVersion: Long,
    val replacement: Organization,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedVersion >= 0) { "Expected version must not be negative" }
        require(replacement.id == organizationId && replacement.version == expectedVersion + 1) {
            "Replacement organization must preserve ID and advance version exactly once"
        }
        val expectedAction = if (replacement.state == OrganizationState.DELETED) {
            AuditAction.ORGANIZATION_DELETED
        } else {
            AuditAction.ORGANIZATION_CHANGED
        }
        require(auditEvent.action == expectedAction &&
            auditEvent.target == AuditTarget(AuditTargetType.ORGANIZATION, organizationId.value)
        ) { "Organization mutation requires the matching organization audit event" }
    }
}

@Serializable
data class CreateInvitationCommand(
    val invitation: Invitation,
    val auditEvent: AuditEvent
) {
    init {
        require(invitation.version == 0L && invitation.state == InvitationState.PENDING) {
            "A new invitation must be pending at version zero"
        }
        require(auditEvent.action == AuditAction.INVITATION_CREATED &&
            auditEvent.target == AuditTarget(AuditTargetType.INVITATION, invitation.id.value)
        ) { "Invitation creation requires an invitation-created audit event" }
    }
}

@Serializable
data class MutateInvitationCommand(
    val invitationId: InvitationId,
    val expectedVersion: Long,
    val replacement: Invitation,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedVersion >= 0) { "Expected version must not be negative" }
        require(replacement.id == invitationId && replacement.version == expectedVersion + 1) {
            "Replacement invitation must preserve ID and advance version exactly once"
        }
        require(replacement.state == InvitationState.REVOKED && auditEvent.action == AuditAction.INVITATION_REVOKED) {
            "Invitation mutation currently supports audited revocation only"
        }
        require(auditEvent.target == AuditTarget(AuditTargetType.INVITATION, invitationId.value)) {
            "Invitation audit must target the invitation"
        }
    }
}

/**
 * Atomically consumes an invitation while establishing a brand-new passkey-enrollment identity.
 * The token digest is rechecked under the invitation lock so a lookup and commit cannot diverge.
 */
@Serializable
data class EnrollInvitationCommand(
    val invitationId: InvitationId,
    val expectedInvitationVersion: Long,
    val expectedTokenDigest: SecretDigest,
    val user: User,
    val membership: Membership,
    val enrollmentSession: IdentitySession,
    val enrolledAt: Instant,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedInvitationVersion >= 0) { "Expected invitation version must not be negative" }
        require(expectedTokenDigest.algorithm == DigestAlgorithm.HMAC_SHA256) {
            "Invitation enrollment requires a keyed token digest"
        }
        require(user.version == 0L && user.sessionEpoch == 0L &&
            user.state == UserState.ACTIVE && user.primaryEmail != null &&
            user.createdAt == enrolledAt && user.updatedAt == enrolledAt && user.activatedAt == enrolledAt
        ) { "Invitation enrollment requires a new active user" }
        require(membership.version == 0L && membership.state == MembershipState.ACTIVE &&
            membership.userId == user.id && membership.createdAt == enrolledAt && membership.updatedAt == enrolledAt
        ) { "Invitation enrollment requires a matching active membership" }
        require(enrollmentSession.version == 0L && enrollmentSession.state == SessionState.ACTIVE &&
            enrollmentSession.id == enrollmentSession.familyId && enrollmentSession.rotationCounter == 0L &&
            enrollmentSession.userId == user.id && enrollmentSession.userSessionEpoch == user.sessionEpoch &&
            enrollmentSession.assurance == AuthenticationAssurance.RECOVERY &&
            enrollmentSession.authenticationMethod == SessionAuthenticationMethod.INVITATION &&
            enrollmentSession.createdAt == enrolledAt && enrollmentSession.authenticatedAt == enrolledAt &&
            enrollmentSession.idleExpiresAt == enrolledAt + INVITATION_ENROLLMENT_LIFETIME &&
            enrollmentSession.absoluteExpiresAt == enrolledAt + INVITATION_ENROLLMENT_LIFETIME
        ) { "Invitation enrollment requires one fresh 15-minute restricted session" }
        require(auditEvent.action == AuditAction.INVITATION_ACCEPTED &&
            auditEvent.target == AuditTarget(AuditTargetType.INVITATION, invitationId.value) &&
            auditEvent.occurredAt == enrolledAt
        ) { "Invitation enrollment requires a matching invitation-accepted audit event" }
    }
}

@Serializable
data class InvitationEnrollmentCommit(
    val invitation: Invitation,
    val user: User,
    val membership: Membership,
    val enrollmentSession: IdentitySession,
    val auditEvent: AuditEvent
)

private val INVITATION_ENROLLMENT_LIFETIME = 15.minutes

@Serializable
data class CreateServiceIdentityCommand(
    val identity: ServiceIdentity,
    val initialCredential: ServiceCredential,
    val auditEvent: AuditEvent
) {
    init {
        require(identity.version == 0L && identity.state == ServiceIdentityState.ACTIVE) {
            "A new service identity must be active at version zero"
        }
        require(initialCredential.version == 0L && initialCredential.state == ServiceCredentialState.ACTIVE &&
            initialCredential.serviceIdentityId == identity.id &&
            identity.capabilities.containsAll(initialCredential.capabilities)
        ) { "A service identity requires a matching active initial credential" }
        require(auditEvent.action == AuditAction.SERVICE_IDENTITY_CREATED &&
            auditEvent.target == AuditTarget(AuditTargetType.SERVICE_IDENTITY, identity.id.value)
        ) { "Service identity creation requires a service-identity-created audit event" }
    }
}

@Serializable
data class ServiceIdentityCreationCommit(
    val identity: ServiceIdentity,
    val initialCredential: ServiceCredential,
    val auditEvent: AuditEvent
)

@Serializable
data class MutateServiceIdentityCommand(
    val serviceIdentityId: ServiceIdentityId,
    val expectedVersion: Long,
    val replacement: ServiceIdentity,
    val changedAt: Instant,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedVersion >= 0) { "Expected version must not be negative" }
        require(replacement.id == serviceIdentityId && replacement.version == expectedVersion + 1 &&
            replacement.updatedAt == changedAt
        ) { "Replacement service identity must preserve ID, advance version, and use changedAt" }
        val expectedAction = if (replacement.state == ServiceIdentityState.REVOKED) {
            AuditAction.SERVICE_IDENTITY_REVOKED
        } else {
            AuditAction.SERVICE_IDENTITY_CHANGED
        }
        require(auditEvent.action == expectedAction &&
            auditEvent.target == AuditTarget(AuditTargetType.SERVICE_IDENTITY, serviceIdentityId.value)
        ) { "Service identity mutation requires the matching audit event" }
    }
}

@Serializable
data class CreateServiceCredentialCommand(
    val credential: ServiceCredential,
    val auditEvent: AuditEvent
) {
    init {
        require(credential.version == 0L && credential.state == ServiceCredentialState.ACTIVE) {
            "A new service credential must be active at version zero"
        }
        require(auditEvent.action == AuditAction.SERVICE_CREDENTIAL_CREATED &&
            auditEvent.target == AuditTarget(AuditTargetType.SERVICE_CREDENTIAL, credential.id.value)
        ) { "Service credential creation requires a credential-created audit event" }
    }
}

@Serializable
data class RevokeServiceCredentialCommand(
    val credentialId: ServiceCredentialId,
    val expectedVersion: Long,
    val revokedAt: Instant,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedVersion >= 0) { "Expected version must not be negative" }
        require(auditEvent.action == AuditAction.SERVICE_CREDENTIAL_REVOKED &&
            auditEvent.target == AuditTarget(AuditTargetType.SERVICE_CREDENTIAL, credentialId.value)
        ) { "Service credential revocation requires a credential-revoked audit event" }
    }
}
