package codes.yousef.aether.auth

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class IdentityStoreErrorCode {
    @SerialName("not_found") NOT_FOUND,
    @SerialName("already_exists") ALREADY_EXISTS,
    @SerialName("unique_constraint") UNIQUE_CONSTRAINT,
    @SerialName("version_conflict") VERSION_CONFLICT,
    @SerialName("invalid_transition") INVALID_TRANSITION,
    @SerialName("challenge_not_pending") CHALLENGE_NOT_PENDING,
    @SerialName("challenge_expired") CHALLENGE_EXPIRED,
    @SerialName("session_not_active") SESSION_NOT_ACTIVE,
    @SerialName("session_expired") SESSION_EXPIRED,
    @SerialName("federation_provider_disabled") FEDERATION_PROVIDER_DISABLED,
    @SerialName("recovery_code_not_active") RECOVERY_CODE_NOT_ACTIVE,
    @SerialName("last_owner") LAST_OWNER,
    @SerialName("replay_detected") REPLAY_DETECTED,
    @SerialName("idempotency_conflict") IDEMPOTENCY_CONFLICT,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("internal") INTERNAL
}

/** Safe adapter failure. Provider, SQL, document, token, and exception text must stay out of this type. */
@Serializable
data class IdentityStoreError(
    val code: IdentityStoreErrorCode,
    val retryable: Boolean = false
) {
    init {
        if (retryable) {
            require(code == IdentityStoreErrorCode.VERSION_CONFLICT || code == IdentityStoreErrorCode.UNAVAILABLE) {
                "Only version conflicts and unavailable stores are retryable"
            }
        }
    }
}

sealed interface StoreResult<out T> {
    data class Success<T>(val value: T) : StoreResult<T>
    data class Failure(val error: IdentityStoreError) : StoreResult<Nothing>

    fun valueOrNull(): T? = (this as? Success<T>)?.value
}

@Serializable
data class CreateChallengeCommand(
    val challenge: Challenge,
    val auditEvent: AuditEvent? = null,
    val federationProviderLease: FederationProviderLease? = challenge.federationProviderLease
) {
    init {
        require(challenge.state == ChallengeState.PENDING && challenge.version == 0L) {
            "New challenge must be pending at version zero"
        }
        require(federationProviderLease == challenge.federationProviderLease) {
            "Challenge creation must use its exact persisted federation provider lease"
        }
    }
}

@Serializable
data class ConsumeChallengeCommand(
    val challengeId: ChallengeId,
    val expectedVersion: Long,
    val terminalState: ChallengeState,
    val consumedAt: Instant,
    val auditEvent: AuditEvent? = null,
    val federationProviderLease: FederationProviderLease? = null
) {
    init {
        require(expectedVersion >= 0) { "Expected version must not be negative" }
        require(terminalState == ChallengeState.CONSUMED || terminalState == ChallengeState.FAILED ||
            terminalState == ChallengeState.EXPIRED
        ) {
            "Challenge may only be consumed, failed, or expired"
        }
    }
}

@Serializable
data class CompleteCredentialRegistrationCommand(
    val challengeId: ChallengeId,
    val expectedChallengeVersion: Long,
    val credential: Credential,
    val user: User? = null,
    val expectedUserVersion: Long? = null,
    val auditEvent: AuditEvent,
    val rejectionAuditEvent: AuditEvent
) {
    init {
        require(expectedChallengeVersion >= 0) { "Expected challenge version must not be negative" }
        require(credential.state == CredentialState.ACTIVE && credential.version == 0L) {
            "Registered credential must be active at version zero"
        }
        require((user == null) == (expectedUserVersion == null)) {
            "User replacement and expected user version must either both be present or both be absent"
        }
        require(expectedUserVersion == null || expectedUserVersion >= -1) {
            "Expected user version must be -1 for insertion or non-negative for replacement"
        }
        require(user == null || user.id == credential.userId) { "Credential and user must match" }
        require(auditEvent.action == AuditAction.CREDENTIAL_REGISTERED) { "Registration requires a credential-registered audit event" }
        requireWebAuthnStoreRejectionAudit(challengeId, auditEvent.occurredAt, rejectionAuditEvent)
    }
}

@Serializable
data class CredentialRegistrationCommit(
    val challenge: Challenge,
    val credential: Credential,
    val user: User?,
    val auditEvent: AuditEvent
)

@Serializable
data class CompleteCredentialAuthenticationCommand(
    val challengeId: ChallengeId,
    val expectedChallengeVersion: Long,
    val credentialId: CredentialId,
    val expectedCredentialVersion: Long,
    val newSignCount: Long,
    val backupEligible: Boolean,
    val backedUp: Boolean,
    val authenticatedAt: Instant,
    val session: IdentitySession,
    val replacedSessionId: SessionId? = null,
    val expectedReplacedSessionVersion: Long? = null,
    val auditEvent: AuditEvent,
    val rejectionAuditEvent: AuditEvent
) {
    init {
        require(expectedChallengeVersion >= 0 && expectedCredentialVersion >= 0) { "Expected versions must not be negative" }
        require(newSignCount in 0..4_294_967_295L) { "WebAuthn signCount must fit an unsigned 32-bit value" }
        require(!backedUp || backupEligible) { "A backed-up credential must be backup eligible" }
        require((replacedSessionId == null) == (expectedReplacedSessionVersion == null)) {
            "Replaced session ID and expected version must either both be present or both be absent"
        }
        require(expectedReplacedSessionVersion == null || expectedReplacedSessionVersion >= 0) {
            "Expected replaced-session version must not be negative"
        }
        require(session.createdAt == authenticatedAt) { "Authentication session must be created at authenticatedAt" }
        require(auditEvent.action == AuditAction.CREDENTIAL_AUTHENTICATED) {
            "Authentication requires a credential-authenticated audit event"
        }
        requireWebAuthnStoreRejectionAudit(challengeId, authenticatedAt, rejectionAuditEvent)
    }
}

@Serializable
data class CredentialAuthenticationCommit(
    val challenge: Challenge,
    val credential: Credential,
    val session: IdentitySession,
    val replacedSession: IdentitySession? = null,
    val auditEvent: AuditEvent
)

/**
 * Atomically consumes an authentication ceremony and quarantines a credential whose non-zero
 * signature counter failed to advance. No session may be issued by this transition.
 */
@Serializable
data class QuarantineCredentialAuthenticationCommand(
    val challengeId: ChallengeId,
    val expectedChallengeVersion: Long,
    val credentialId: CredentialId,
    val expectedCredentialVersion: Long,
    val observedSignCount: Long,
    val backupEligible: Boolean,
    val backedUp: Boolean,
    val detectedAt: Instant,
    val auditEvent: AuditEvent,
    val rejectionAuditEvent: AuditEvent
) {
    init {
        require(expectedChallengeVersion >= 0 && expectedCredentialVersion >= 0) {
            "Expected versions must not be negative"
        }
        require(observedSignCount in 1..4_294_967_295L) {
            "A counter anomaly requires a non-zero unsigned 32-bit observed counter"
        }
        require(!backedUp || backupEligible) { "A backed-up credential must be backup eligible" }
        require(auditEvent.action == AuditAction.CREDENTIAL_QUARANTINED) {
            "Counter quarantine requires a credential-quarantined audit event"
        }
        require(auditEvent.outcome == AuditOutcome.DENIED) {
            "Counter quarantine must record a denied authentication"
        }
        requireWebAuthnStoreRejectionAudit(challengeId, detectedAt, rejectionAuditEvent)
    }
}

@Serializable
data class CredentialQuarantineCommit(
    val challenge: Challenge,
    val credential: Credential,
    val auditEvent: AuditEvent
)

/** A deterministic store rejection that committed only the terminal ceremony and redacted audit. */
@Serializable
data class WebAuthnCeremonyRejectionCommit(
    val challenge: Challenge,
    val error: IdentityStoreError,
    val auditEvent: AuditEvent
) {
    init {
        require(challenge.state == ChallengeState.FAILED && challenge.consumedAt != null) {
            "A rejected WebAuthn attempt must terminally fail its challenge"
        }
        requireWebAuthnStoreRejectionAudit(challenge.id, challenge.consumedAt, auditEvent)
        require(error.code != IdentityStoreErrorCode.UNAVAILABLE && error.code != IdentityStoreErrorCode.INTERNAL) {
            "Infrastructure failures cannot be represented as committed WebAuthn rejections"
        }
        require(!error.retryable) { "A terminally rejected WebAuthn attempt is not retryable" }
    }
}

/** Exactly one of [completion] and [rejection] is present. */
@Serializable
data class WebAuthnCeremonyAttemptCommit<T : Any>(
    val completion: T? = null,
    val rejection: WebAuthnCeremonyRejectionCommit? = null
) {
    init {
        require((completion == null) != (rejection == null)) {
            "A WebAuthn attempt must contain exactly one terminal outcome"
        }
    }

    companion object {
        fun <T : Any> completed(value: T): WebAuthnCeremonyAttemptCommit<T> =
            WebAuthnCeremonyAttemptCommit(completion = value)

        fun <T : Any> rejected(value: WebAuthnCeremonyRejectionCommit): WebAuthnCeremonyAttemptCommit<T> =
            WebAuthnCeremonyAttemptCommit(rejection = value)
    }
}

const val WEBAUTHN_STORE_REJECTION_REASON_CODE: String = "webauthn_store_rejected"

internal fun requireWebAuthnStoreRejectionAudit(
    challengeId: ChallengeId,
    occurredAt: Instant,
    event: AuditEvent
) {
    require(event.action == AuditAction.WEBAUTHN_CEREMONY_REJECTED &&
        event.outcome == AuditOutcome.DENIED &&
        event.target?.type == AuditTargetType.CHALLENGE &&
        event.target.id == challengeId.value &&
        event.occurredAt == occurredAt &&
        event.reasonCode == WEBAUTHN_STORE_REJECTION_REASON_CODE
    ) { "WebAuthn store rejection requires a redacted challenge-targeted audit event" }
}

@Serializable
data class MutateCredentialCommand(
    val credentialId: CredentialId,
    val expectedVersion: Long,
    val replacement: Credential,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedVersion >= 0) { "Expected version must not be negative" }
        require(replacement.id == credentialId && replacement.version == expectedVersion + 1) {
            "Replacement credential must preserve ID and advance version exactly once"
        }
        require(auditEvent.action == AuditAction.CREDENTIAL_RENAMED ||
            auditEvent.action == AuditAction.CREDENTIAL_REVOKED
        ) { "Credential mutation requires a renamed or revoked audit event" }
        require(auditEvent.target?.type == AuditTargetType.CREDENTIAL &&
            auditEvent.target.id == credentialId.value
        ) { "Credential mutation audit must target the credential" }
    }
}

@Serializable
data class CreateSessionCommand(
    val session: IdentitySession,
    val auditEvent: AuditEvent
) {
    init {
        require(session.state == SessionState.ACTIVE && session.version == 0L) { "New session must be active at version zero" }
        require(auditEvent.action == AuditAction.SESSION_CREATED) { "Session creation requires a session-created audit event" }
    }
}

/**
 * Atomically advances the activity window of one active session without emitting an audit event.
 * Routine request touches are intentionally excluded from the durable audit stream.
 */
@Serializable
data class TouchIdentitySessionCommand(
    val sessionId: SessionId,
    val expectedVersion: Long,
    val lastUsedAt: Instant,
    val idleExpiresAt: Instant
) {
    init {
        require(expectedVersion >= 0) { "Expected version must not be negative" }
        require(idleExpiresAt >= lastUsedAt) { "Idle expiration must not precede the touch time" }
    }
}

@Serializable
data class RotateSessionCommand(
    val sessionId: SessionId,
    val expectedVersion: Long,
    val replacement: IdentitySession,
    val rotatedAt: Instant,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedVersion >= 0) { "Expected version must not be negative" }
        require(replacement.state == SessionState.ACTIVE && replacement.version == 0L) {
            "Replacement session must be active at version zero"
        }
        require(replacement.rotatedFromId == sessionId) { "Replacement must identify the rotated session" }
        require(auditEvent.action == AuditAction.SESSION_ROTATED) { "Session rotation requires a session-rotated audit event" }
    }
}

@Serializable
data class SessionRotationCommit(
    val previous: IdentitySession,
    val replacement: IdentitySession,
    val auditEvent: AuditEvent
)

@Serializable
data class RevokeSessionCommand(
    val sessionId: SessionId,
    val expectedVersion: Long,
    val revokedAt: Instant,
    val reasonCode: String,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedVersion >= 0) { "Expected version must not be negative" }
        require(reasonCode.isNotBlank() && reasonCode.length <= 200) { "Invalid session revocation reason code" }
        require(auditEvent.action == AuditAction.SESSION_REVOKED) { "Session revocation requires a session-revoked audit event" }
    }
}

@Serializable
data class RevokeUserSessionsCommand(
    val userId: UserId,
    val expectedUserVersion: Long,
    val expectedSessionEpoch: Long,
    val newSessionEpoch: Long,
    val exceptSessionId: SessionId? = null,
    val revokedAt: Instant,
    val reasonCode: String,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedUserVersion >= 0 && expectedSessionEpoch >= 0) { "Expected versions must not be negative" }
        require(newSessionEpoch == expectedSessionEpoch + 1) { "Session epoch must advance exactly once" }
        require(reasonCode.isNotBlank() && reasonCode.length <= 200) { "Invalid session revocation reason code" }
        require(auditEvent.action == AuditAction.SESSION_REVOKED) { "Session revocation requires a session-revoked audit event" }
    }
}

@Serializable
data class RevokeUserSessionsCommit(
    val user: User,
    val revokedSessionIds: List<SessionId>,
    val auditEvent: AuditEvent
)

@Serializable
data class ReplaceRecoveryCodesCommand(
    val userId: UserId,
    val expectedGeneration: Long?,
    val newGeneration: Long,
    val codes: List<RecoveryCode>,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedGeneration == null || expectedGeneration >= 0) { "Expected recovery generation must not be negative" }
        require(newGeneration == (expectedGeneration?.plus(1) ?: 0L)) { "Recovery generation must advance exactly once" }
        require(codes.isNotEmpty()) { "At least one recovery code is required" }
        require(codes.all { it.userId == userId && it.generation == newGeneration && it.state == RecoveryCodeState.ACTIVE }) {
            "Every replacement recovery code must be active and match the user and generation"
        }
        require(codes.map { it.id }.toSet().size == codes.size) { "Recovery-code IDs must be unique" }
        require(codes.map { it.publicSelector }.toSet().size == codes.size) { "Recovery-code selectors must be unique" }
        require(codes.size == 10) { "A recovery-code generation must contain exactly ten codes" }
        require(auditEvent.action == AuditAction.RECOVERY_CODES_REPLACED) {
            "Recovery-code replacement requires a codes-replaced audit event"
        }
    }
}

@Serializable
data class RecoveryCodeReplacementCommit(
    val generation: Long,
    val codes: List<RecoveryCode>,
    val auditEvent: AuditEvent
)

@Serializable
data class ConsumeRecoveryCodeCommand(
    val recoveryCodeId: RecoveryCodeId,
    val expectedVersion: Long,
    val consumedAt: Instant,
    val recoverySession: IdentitySession,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedVersion >= 0) { "Expected version must not be negative" }
        require(recoverySession.assurance == AuthenticationAssurance.RECOVERY) {
            "Recovery code must create a constrained recovery session"
        }
        require(auditEvent.action == AuditAction.RECOVERY_CODE_USED) { "Recovery requires a code-used audit event" }
    }
}

@Serializable
data class RecoveryCodeConsumptionCommit(
    val recoveryCode: RecoveryCode,
    val recoverySession: IdentitySession,
    val auditEvent: AuditEvent
)

@Serializable
data class CreateMembershipCommand(
    val membership: Membership,
    val invitationId: InvitationId? = null,
    val expectedInvitationVersion: Long? = null,
    val auditEvent: AuditEvent
) {
    init {
        require(membership.version == 0L && membership.state == MembershipState.ACTIVE) {
            "New membership must be active at version zero"
        }
        require((invitationId == null) == (expectedInvitationVersion == null)) {
            "Invitation ID and expected version must either both be present or both be absent"
        }
        require(expectedInvitationVersion == null || expectedInvitationVersion >= 0) {
            "Expected invitation version must not be negative"
        }
        require(auditEvent.action == AuditAction.MEMBERSHIP_CREATED ||
            (invitationId != null && auditEvent.action == AuditAction.INVITATION_ACCEPTED)
        ) { "Membership creation requires a membership-created or invitation-accepted audit event" }
    }
}

@Serializable
data class MutateMembershipCommand(
    val membershipId: MembershipId,
    val expectedVersion: Long,
    val replacement: Membership,
    val auditEvent: AuditEvent,
    val expectedUserVersion: Long? = null,
    val expectedSessionEpoch: Long? = null,
    val newSessionEpoch: Long? = null,
    val sessionsRevokedAt: Instant? = null,
    val sessionRevocationReasonCode: String? = null
) {
    init {
        require(expectedVersion >= 0) { "Expected version must not be negative" }
        require(replacement.id == membershipId && replacement.version == expectedVersion + 1) {
            "Replacement membership must preserve ID and advance version exactly once"
        }
        require(auditEvent.action == AuditAction.MEMBERSHIP_CHANGED) { "Membership mutation requires a membership-changed audit event" }
        val epochValues = listOf(expectedUserVersion, expectedSessionEpoch, newSessionEpoch)
        require(epochValues.all { it == null } || epochValues.all { it != null }) {
            "Membership session-epoch mutation fields must be supplied together"
        }
        if (expectedUserVersion != null) {
            require(expectedUserVersion >= 0 && expectedSessionEpoch!! >= 0 &&
                newSessionEpoch == expectedSessionEpoch + 1
            ) { "Membership privilege changes must advance the user's session epoch exactly once" }
            require(sessionsRevokedAt != null && !sessionRevocationReasonCode.isNullOrBlank() &&
                sessionRevocationReasonCode.length <= 200
            ) { "Membership privilege changes require bounded session revocation metadata" }
        } else {
            require(sessionsRevokedAt == null && sessionRevocationReasonCode == null) {
                "Session revocation metadata requires an epoch mutation"
            }
        }
    }
}

@Serializable
data class CompareAndSetDeviceGrantCommand(
    val expectedVersion: Long?,
    val replacement: DeviceGrant,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedVersion == null || expectedVersion >= 0) { "Expected version must not be negative" }
        require(replacement.version == (expectedVersion?.plus(1) ?: 0L)) {
            "Device-grant version must be zero on insert or advance exactly once"
        }
        require(auditEvent.action == AuditAction.DEVICE_GRANT_CHANGED) { "Device grant CAS requires a device-grant audit event" }
    }
}

@Serializable
data class RotateServiceCredentialCommand(
    val credentialId: ServiceCredentialId,
    val expectedVersion: Long,
    val replacement: ServiceCredential,
    val rotatedAt: Instant,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedVersion >= 0) { "Expected version must not be negative" }
        require(replacement.version == 0L && replacement.state == ServiceCredentialState.ACTIVE) {
            "Replacement service credential must be active at version zero"
        }
        require(replacement.id != credentialId) { "Replacement service credential must have a new ID" }
        require(auditEvent.action == AuditAction.SERVICE_CREDENTIAL_ROTATED) {
            "Service credential rotation requires a service-credential-rotated audit event"
        }
    }
}

@Serializable
data class ServiceCredentialRotationCommit(
    val previous: ServiceCredential,
    val replacement: ServiceCredential,
    val auditEvent: AuditEvent
)

@Serializable
data class ExchangeDeviceGrantCommand(
    val deviceGrantId: DeviceGrantId,
    val expectedDeviceGrantVersion: Long,
    val family: DeviceTokenFamily,
    val accessToken: DeviceAccessToken,
    val refreshToken: DeviceRefreshToken,
    val exchangedAt: Instant,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedDeviceGrantVersion >= 0) { "Expected device-grant version must not be negative" }
        require(family.version == 0L && family.state == DeviceTokenFamilyState.ACTIVE &&
            family.deviceGrantId == deviceGrantId
        ) { "A device exchange requires a new active token family for the grant" }
        require(accessToken.version == 0L && accessToken.state == DeviceAccessTokenState.ACTIVE &&
            accessToken.familyId == family.id
        ) { "A device exchange requires a new active access token" }
        require(refreshToken.version == 0L && refreshToken.state == DeviceRefreshTokenState.ACTIVE &&
            refreshToken.familyId == family.id && refreshToken.rotationCounter == 0L
        ) { "A device exchange requires the first active refresh token" }
        require(accessToken.publicSelector != refreshToken.publicSelector &&
            accessToken.secretDigest != refreshToken.secretDigest
        ) { "Access and refresh credentials must be independently generated" }
        require(family.membershipVersion >= 0) { "Device exchange membership version must not be negative" }
        require(auditEvent.action == AuditAction.DEVICE_TOKEN_ISSUED) {
            "Device exchange requires a token-issued audit event"
        }
        require(auditEvent.organizationId == family.organizationId &&
            auditEvent.target == AuditTarget(AuditTargetType.DEVICE_GRANT, deviceGrantId.value)
        ) { "Device exchange audit must target the tenant-scoped device grant" }
    }
}

@Serializable
data class DeviceTokenIssuanceCommit(
    val deviceGrant: DeviceGrant,
    val family: DeviceTokenFamily,
    val accessToken: DeviceAccessToken,
    val refreshToken: DeviceRefreshToken,
    val auditEvent: AuditEvent
)

@Serializable
data class RotateDeviceRefreshTokenCommand(
    val refreshTokenId: DeviceRefreshTokenId,
    val expectedRefreshTokenVersion: Long,
    val expectedFamilyVersion: Long,
    val replacementAccessToken: DeviceAccessToken,
    val replacementRefreshToken: DeviceRefreshToken,
    val rotatedAt: Instant,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedRefreshTokenVersion >= 0 && expectedFamilyVersion >= 0) {
            "Expected device-token versions must not be negative"
        }
        require(replacementAccessToken.version == 0L &&
            replacementAccessToken.state == DeviceAccessTokenState.ACTIVE
        ) { "Refresh rotation requires a new active access token" }
        require(replacementRefreshToken.version == 0L &&
            replacementRefreshToken.state == DeviceRefreshTokenState.ACTIVE
        ) { "Refresh rotation requires a new active refresh token" }
        require(replacementAccessToken.familyId == replacementRefreshToken.familyId) {
            "Replacement access and refresh tokens must share a family"
        }
        require(replacementAccessToken.publicSelector != replacementRefreshToken.publicSelector &&
            replacementAccessToken.secretDigest != replacementRefreshToken.secretDigest
        ) { "Replacement access and refresh credentials must be independently generated" }
        require(auditEvent.action == AuditAction.DEVICE_TOKEN_REFRESHED) {
            "Refresh rotation requires a token-refreshed audit event"
        }
        require(auditEvent.target?.type == AuditTargetType.DEVICE_GRANT &&
            auditEvent.organizationId != null
        ) { "Refresh rotation audit must target a tenant-scoped device grant" }
    }
}

@Serializable
data class DeviceTokenRotationCommit(
    val family: DeviceTokenFamily,
    val previousRefreshToken: DeviceRefreshToken,
    val accessToken: DeviceAccessToken,
    val refreshToken: DeviceRefreshToken,
    val auditEvent: AuditEvent
)

@Serializable
data class RevokeDeviceTokenFamilyCommand(
    val familyId: DeviceTokenFamilyId,
    val expectedFamilyVersion: Long,
    val revokedAt: Instant,
    val reasonCode: String,
    val replayDetected: Boolean = false,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedFamilyVersion >= 0) { "Expected token-family version must not be negative" }
        require(reasonCode.isNotBlank() && reasonCode.length <= 200) { "Invalid token-family revocation reason" }
        require(auditEvent.action == if (replayDetected) {
            AuditAction.DEVICE_TOKEN_REPLAY_DETECTED
        } else AuditAction.DEVICE_TOKEN_REVOKED) { "Incorrect token-family revocation audit action" }
    }
}

@Serializable
data class DeviceTokenFamilyRevocationCommit(
    val family: DeviceTokenFamily,
    val revokedAccessTokenIds: List<DeviceAccessTokenId>,
    val revokedRefreshTokenIds: List<DeviceRefreshTokenId>,
    val auditEvent: AuditEvent
)

@Serializable
data class FederationJitProvisioning(
    val user: User,
    val membership: Membership
) {
    init {
        require(user.state == UserState.ACTIVE && user.version == 0L && user.primaryEmail == null) {
            "Federation JIT provisioning requires a new active user without an email identity key"
        }
        require(membership.userId == user.id &&
            membership.role == OrganizationRole.VIEWER &&
            membership.state == MembershipState.ACTIVE &&
            membership.version == 0L
        ) { "Federation JIT provisioning requires a new active viewer membership for its user" }
    }
}

@Serializable
data class LinkExternalIdentityCommand(
    val identity: ExternalIdentity,
    val replayReceipt: ExternalIdentityReplayReceipt,
    val federationProviderLease: FederationProviderLease,
    val auditEvent: AuditEvent,
    val jitProvisioning: FederationJitProvisioning? = null
) {
    init {
        require(identity.provider == replayReceipt.provider) { "Identity and replay receipt providers must match" }
        require(identity.provider == federationProviderLease.storageKey) {
            "External identity must match its federation provider lease"
        }
        require(identity.version == 0L && identity.state == ExternalIdentityState.ACTIVE) {
            "Linked external identity must be active at version zero"
        }
        require(auditEvent.action == AuditAction.EXTERNAL_IDENTITY_LINKED) {
            "External identity linking requires an external-identity-linked audit event"
        }
        require(auditEvent.organizationId == federationProviderLease.organizationId) {
            "External identity linking audit must match the provider organization"
        }
        jitProvisioning?.let { provisioning ->
            require(identity.userId == provisioning.user.id &&
                provisioning.membership.organizationId == federationProviderLease.organizationId
            ) { "Federation JIT provisioning must match the linked user and provider tenant" }
            require(identity.createdAt == auditEvent.occurredAt &&
                identity.updatedAt == auditEvent.occurredAt &&
                provisioning.user.createdAt == auditEvent.occurredAt &&
                provisioning.user.updatedAt == auditEvent.occurredAt &&
                provisioning.user.activatedAt == auditEvent.occurredAt &&
                provisioning.membership.createdAt == auditEvent.occurredAt &&
                provisioning.membership.updatedAt == auditEvent.occurredAt
            ) { "Federation JIT entities must share the atomic link creation time" }
        }
    }
}

@Serializable
data class ExternalIdentityLinkCommit(
    val identity: ExternalIdentity,
    val replayReceipt: ExternalIdentityReplayReceipt,
    val auditEvent: AuditEvent,
    val provisionedUser: User? = null,
    val provisionedMembership: Membership? = null
) {
    init {
        require((provisionedUser == null) == (provisionedMembership == null)) {
            "External identity link commit must contain both JIT entities or neither"
        }
        if (provisionedUser != null && provisionedMembership != null) {
            require(identity.userId == provisionedUser.id &&
                provisionedMembership.userId == provisionedUser.id
            ) { "External identity link commit JIT entities must match the linked user" }
        }
    }
}

@Serializable
data class RecordExternalIdentityReplayCommand(
    val replayReceipt: ExternalIdentityReplayReceipt,
    val federationProviderLease: FederationProviderLease
) {
    init {
        require(replayReceipt.provider == federationProviderLease.storageKey) {
            "External replay receipt must match its federation provider lease"
        }
    }
}

@Serializable
data class ApplyScimMutationCommand(
    val mutation: ScimMutation,
    val auditEvent: AuditEvent
) {
    init {
        require(auditEvent.action == AuditAction.SCIM_MUTATION_APPLIED) { "SCIM mutation requires a SCIM audit event" }
    }
}

@Serializable
data class ScimMutationCommit(
    val user: User? = null,
    val membership: Membership? = null,
    val alreadyApplied: Boolean,
    val auditEvent: AuditEvent? = null
)

/** Tenant-local credential invalidation coupled to a SCIM membership mutation. */
@Serializable
data class ScimTenantRevocation(
    val userId: UserId,
    val revokeSessions: Boolean = true,
    val revokeDeviceTokenFamilies: Boolean = true,
    val reasonCode: String
) {
    init {
        require(revokeSessions || revokeDeviceTokenFamilies) { "SCIM revocation must revoke at least one credential kind" }
        require(reasonCode.isNotBlank() && reasonCode.length <= 200) { "SCIM revocation reason must be bounded" }
    }
}

/**
 * One durable, idempotent SCIM identity operation.
 *
 * Implementations atomically apply every ordered mutation, the optional Group aggregate,
 * tenant-local session/device-token revocations, all child audits, the operation audit, and the
 * operation receipt. Reusing [operationId] with a non-identical command is an idempotency conflict.
 */
@Serializable
data class ApplyScimBatchCommand(
    val operationId: ScimOperationId,
    val organizationId: OrganizationId,
    val provider: String,
    val mutations: List<ApplyScimMutationCommand> = emptyList(),
    val group: ScimGroup? = null,
    val expectedGroupVersion: Long? = null,
    val revocations: List<ScimTenantRevocation> = emptyList(),
    val auditEvent: AuditEvent
) {
    init {
        require(provider.isNotBlank() && provider.length <= 512) { "SCIM provider key must be bounded" }
        require(mutations.size <= 10_000 && revocations.size <= 10_000) { "SCIM batch is too large" }
        require(mutations.map { it.mutation.operationId }.toSet().size == mutations.size) {
            "SCIM mutation operation IDs must be unique within a batch"
        }
        require((mutations.map { it.auditEvent.id } + auditEvent.id).toSet().size == mutations.size + 1) {
            "SCIM audit event IDs must be unique within a batch"
        }
        require(revocations.map { it.userId }.toSet().size == revocations.size) {
            "SCIM tenant revocations must contain each user at most once"
        }
        require(mutations.mapNotNull { it.mutation.user?.id }.let { it.toSet().size == it.size } &&
            mutations.mapNotNull { it.mutation.membership?.id }.let { it.toSet().size == it.size }
        ) { "SCIM batch may mutate each User and Membership at most once" }
        require(mutations.all { command ->
            command.mutation.provider == provider &&
                command.mutation.membership?.organizationId?.let { it == organizationId } != false &&
                command.auditEvent.organizationId == organizationId
        }) { "SCIM mutations must belong to the batch tenant and provider" }
        require((group == null) == (expectedGroupVersion == null)) {
            "SCIM Group and expected version must either both be absent or both be present"
        }
        group?.let { aggregate ->
            val expectedVersion = requireNotNull(expectedGroupVersion)
            require(aggregate.organizationId == organizationId && aggregate.provider == provider) {
                "SCIM Group must belong to the batch tenant and provider"
            }
            require(expectedVersion == 0L || expectedVersion >= 1L) {
                "Expected SCIM Group version must be zero for creation or positive for replacement"
            }
            require(aggregate.version == expectedVersion + 1L) {
                "SCIM Group version must advance exactly once"
            }
            require(auditEvent.action == AuditAction.SCIM_GROUP_CHANGED &&
                auditEvent.target?.type == AuditTargetType.SCIM_GROUP &&
                auditEvent.target.id == aggregate.id
            ) { "SCIM Group batch requires a canonical Group audit event" }
        } ?: require(auditEvent.action == AuditAction.SCIM_MUTATION_APPLIED) {
            "SCIM user batch requires a SCIM mutation audit event"
        }
        require(auditEvent.organizationId == organizationId) { "SCIM batch audit must be tenant scoped" }
    }
}

@Serializable
data class ScimBatchCommit(
    val mutationCommits: List<ScimMutationCommit>,
    val group: ScimGroup? = null,
    val revokedSessionIds: List<SessionId> = emptyList(),
    val revokedDeviceTokenFamilyIds: List<DeviceTokenFamilyId> = emptyList(),
    val revokedDeviceAccessTokenIds: List<DeviceAccessTokenId> = emptyList(),
    val revokedDeviceRefreshTokenIds: List<DeviceRefreshTokenId> = emptyList(),
    val alreadyApplied: Boolean,
    val auditEvent: AuditEvent? = null
)

/**
 * Keyset cursor for tenant audit reads. Ordering is newest occurrence first, then descending event
 * ID so events sharing a timestamp remain deterministic across adapters.
 */
@Serializable
data class OrganizationAuditEventCursor(
    val organizationId: OrganizationId,
    val occurredAt: Instant,
    val id: AuditEventId
) {
    /** Versioned, canonical, unpadded base64url representation used by the HTTP API. */
    fun toOpaqueToken(): String {
        require(isCanonicalIdentityUuidV7(organizationId.value) && isCanonicalIdentityUuidV7(id.value)) {
            "Organization audit cursors require canonical UUIDv7 identifiers"
        }
        return Base64Url.encode(
            "$OPAQUE_VERSION\n${organizationId.value}\n$occurredAt\n${id.value}".encodeToByteArray()
        )
    }

    companion object {
        private const val OPAQUE_VERSION = "v1"
        private const val MAXIMUM_CURSOR_BYTES = 512
        private const val MAXIMUM_CURSOR_CHARACTERS = 1_024

        /** Returns null for every malformed, oversized, non-canonical, or unsupported cursor. */
        fun fromOpaqueToken(value: String): OrganizationAuditEventCursor? {
            if (value.isEmpty() || value.length > MAXIMUM_CURSOR_CHARACTERS) return null
            val bytes = runCatching { Base64Url.decode(value, MAXIMUM_CURSOR_BYTES) }.getOrNull() ?: return null
            return try {
                val fields = bytes.decodeToString(throwOnInvalidSequence = true).split('\n')
                if (fields.size != 4 || fields[0] != OPAQUE_VERSION) return null
                val instant = runCatching { Instant.parse(fields[2]) }.getOrNull() ?: return null
                if (instant.toString() != fields[2]) return null
                val cursor = runCatching {
                    OrganizationAuditEventCursor(
                        OrganizationId.parse(fields[1]),
                        instant,
                        AuditEventId.parse(fields[3])
                    )
                }.getOrNull() ?: return null
                cursor.takeIf { it.toOpaqueToken() == value }
            } catch (_: IllegalArgumentException) {
                null
            } finally {
                bytes.fill(0)
            }
        }
    }
}

/** Bounded, organization-only audit query. Adapters must use keyset pagination, never offsets. */
@Serializable
data class OrganizationAuditEventPageRequest(
    val organizationId: OrganizationId,
    val cursor: OrganizationAuditEventCursor? = null,
    val limit: Int = DEFAULT_LIMIT
) {
    init {
        require(limit in 1..MAXIMUM_LIMIT) {
            "Organization audit page size must be between 1 and $MAXIMUM_LIMIT"
        }
        require(cursor == null || cursor.organizationId == organizationId) {
            "Organization audit cursors are bound to one tenant"
        }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 50
        const val MAXIMUM_LIMIT: Int = 100
    }
}

/** Canonical adapter result. Events must be tenant-scoped and already ordered newest first. */
@Serializable
data class OrganizationAuditEventPage(
    val organizationId: OrganizationId,
    val events: List<AuditEvent>,
    val nextCursor: OrganizationAuditEventCursor? = null
) {
    init {
        require(events.size <= OrganizationAuditEventPageRequest.MAXIMUM_LIMIT) {
            "Organization audit page exceeds the public bound"
        }
        require(events.all { it.organizationId == organizationId }) {
            "Organization audit pages may contain only tenant-scoped events"
        }
        require(events.zipWithNext().all { (newer, older) ->
            newer.occurredAt > older.occurredAt ||
                newer.occurredAt == older.occurredAt && newer.id.value > older.id.value
        }) { "Organization audit events must be ordered newest first" }
        require(nextCursor == null || events.lastOrNull()?.toOrganizationAuditCursor() == nextCursor) {
            "The next audit cursor must identify the last returned event"
        }
    }
}

/** Bounded retention deletion. Events exactly at the cutoff are retained. */
@Serializable
data class PurgeAuditEventsCommand(
    val occurredBefore: Instant,
    val maximumEvents: Int = DEFAULT_MAXIMUM_EVENTS
) {
    init {
        require(maximumEvents in 1..MAXIMUM_EVENTS) {
            "Audit retention batches must contain 1..$MAXIMUM_EVENTS events"
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_EVENTS: Int = 250
        const val MAXIMUM_EVENTS: Int = 500
    }
}

/** Result of one atomic retention batch. */
@Serializable
data class PurgeAuditEventsCommit(
    val deletedCount: Int,
    val hasMore: Boolean
) {
    init { require(deletedCount in 0..PurgeAuditEventsCommand.MAXIMUM_EVENTS) }
}

fun AuditEvent.toOrganizationAuditCursor(): OrganizationAuditEventCursor =
    OrganizationAuditEventCursor(requireNotNull(organizationId), occurredAt, id)

/**
 * Storage-neutral identity persistence boundary.
 *
 * Every command method is one atomic operation. Implementations must not expose transaction
 * callbacks: PostgreSQL adapters use native transactions/locks, while document stores use native
 * transactions and version preconditions. Expected races are returned as [StoreResult.Failure].
 */
interface IdentityStore {
    suspend fun findUser(id: UserId): StoreResult<User?>
    suspend fun findUserByEmail(email: EmailAddress): StoreResult<User?>
    suspend fun findCredential(id: CredentialId): StoreResult<Credential?>
    suspend fun findCredentialByWebAuthnId(id: WebAuthnCredentialId): StoreResult<Credential?>
    suspend fun listCredentialsForUser(userId: UserId): StoreResult<List<Credential>>
    suspend fun findSession(id: SessionId): StoreResult<IdentitySession?>
    suspend fun listSessionsForUser(userId: UserId): StoreResult<List<IdentitySession>>
    suspend fun findOrganization(id: OrganizationId): StoreResult<Organization?>
    suspend fun findOrganizationBySlug(slug: String): StoreResult<Organization?>
    suspend fun listOrganizationsForUser(userId: UserId): StoreResult<List<Organization>>
    suspend fun findMembership(id: MembershipId): StoreResult<Membership?>
    suspend fun findMembershipForUser(userId: UserId, organizationId: OrganizationId): StoreResult<Membership?>
    suspend fun listMembershipsForOrganization(organizationId: OrganizationId): StoreResult<List<Membership>>
    suspend fun findInvitation(id: InvitationId): StoreResult<Invitation?>
    suspend fun findInvitationByTokenDigest(digest: SecretDigest): StoreResult<Invitation?>
    suspend fun listInvitationsForOrganization(organizationId: OrganizationId): StoreResult<List<Invitation>>
    suspend fun findServiceIdentity(id: ServiceIdentityId): StoreResult<ServiceIdentity?>
    suspend fun listServiceIdentitiesForOrganization(organizationId: OrganizationId): StoreResult<List<ServiceIdentity>>
    suspend fun findServiceCredentialByPrefix(publicPrefix: String): StoreResult<ServiceCredential?>
    suspend fun listServiceCredentialsForIdentity(serviceIdentityId: ServiceIdentityId): StoreResult<List<ServiceCredential>>
    suspend fun findExternalIdentity(provider: String, subject: ExternalSubject): StoreResult<ExternalIdentity?>
    suspend fun findFederationProviderControl(
        organizationId: OrganizationId,
        providerId: String
    ): StoreResult<FederationProviderControl?>
    suspend fun findFederationProviderControlByStorageKey(
        storageKey: String
    ): StoreResult<FederationProviderControl?>
    suspend fun findScimGroup(
        provider: String,
        organizationId: OrganizationId,
        id: String
    ): StoreResult<ScimGroup?>
    suspend fun findChallenge(id: ChallengeId): StoreResult<Challenge?>
    suspend fun findRecoveryCodeBySelector(publicSelector: String): StoreResult<RecoveryCode?>
    suspend fun listRecoveryCodesForUser(userId: UserId): StoreResult<List<RecoveryCode>>
    suspend fun findDeviceGrant(id: DeviceGrantId): StoreResult<DeviceGrant?>
    suspend fun findDeviceGrantByDeviceCodeDigest(digest: SecretDigest): StoreResult<DeviceGrant?>
    suspend fun findDeviceGrantByUserCodeDigest(digest: SecretDigest): StoreResult<DeviceGrant?>
    suspend fun findDeviceTokenFamily(id: DeviceTokenFamilyId): StoreResult<DeviceTokenFamily?>
    suspend fun findDeviceAccessTokenBySelector(publicSelector: String): StoreResult<DeviceAccessToken?>
    suspend fun findDeviceRefreshTokenBySelector(publicSelector: String): StoreResult<DeviceRefreshToken?>
    suspend fun listAuditEventsForOrganization(
        request: OrganizationAuditEventPageRequest
    ): StoreResult<OrganizationAuditEventPage>
    suspend fun purgeAuditEvents(command: PurgeAuditEventsCommand): StoreResult<PurgeAuditEventsCommit>

    suspend fun createChallenge(command: CreateChallengeCommand): StoreResult<Challenge>
    suspend fun consumeChallenge(command: ConsumeChallengeCommand): StoreResult<Challenge>
    suspend fun appendAuditEvent(event: AuditEvent): StoreResult<AuditEvent>

    suspend fun bootstrapIdentity(command: BootstrapIdentityCommand): StoreResult<BootstrapIdentityCommit>

    /** Atomically consumes the challenge, inserts the unique credential, optionally writes the user, and appends audit. */
    suspend fun completeCredentialRegistration(
        command: CompleteCredentialRegistrationCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<CredentialRegistrationCommit>>

    /** Atomically consumes the challenge, CAS-updates credential state, creates/rotates the session, and appends audit. */
    suspend fun completeCredentialAuthentication(
        command: CompleteCredentialAuthenticationCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<CredentialAuthenticationCommit>>

    /** Atomically consumes the challenge, marks the credential suspected-clone, and appends audit. */
    suspend fun quarantineCredentialAuthentication(
        command: QuarantineCredentialAuthenticationCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<CredentialQuarantineCommit>>

    suspend fun mutateCredential(command: MutateCredentialCommand): StoreResult<Credential>

    suspend fun createSession(command: CreateSessionCommand): StoreResult<IdentitySession>
    suspend fun touchIdentitySession(command: TouchIdentitySessionCommand): StoreResult<IdentitySession>
    suspend fun rotateSession(command: RotateSessionCommand): StoreResult<SessionRotationCommit>
    suspend fun revokeSession(command: RevokeSessionCommand): StoreResult<IdentitySession>
    suspend fun revokeUserSessions(command: RevokeUserSessionsCommand): StoreResult<RevokeUserSessionsCommit>
    suspend fun acquireFederationProviderLease(
        command: AcquireFederationProviderLeaseCommand
    ): StoreResult<FederationProviderLease>
    suspend fun validateFederationProviderLease(
        lease: FederationProviderLease
    ): StoreResult<FederationProviderLease>
    suspend fun compareAndSetFederationProviderState(
        command: CompareAndSetFederationProviderStateCommand
    ): StoreResult<FederationProviderStateCommit>

    suspend fun replaceRecoveryCodes(
        command: ReplaceRecoveryCodesCommand
    ): StoreResult<RecoveryCodeReplacementCommit>

    suspend fun consumeRecoveryCode(
        command: ConsumeRecoveryCodeCommand
    ): StoreResult<RecoveryCodeConsumptionCommit>

    /** Atomically makes a delivered ticket redeemable and records its successful delivery audit. */
    suspend fun activateAdministrativeRecoveryTicket(
        command: ActivateAdministrativeRecoveryTicketCommand
    ): StoreResult<AdministrativeRecoveryTicketActivationCommit>

    suspend fun redeemAdministrativeRecoveryTicket(
        command: RedeemAdministrativeRecoveryTicketCommand
    ): StoreResult<AdministrativeRecoveryTicketRedemptionCommit>

    suspend fun completeRecoveryEnrollment(
        command: CompleteRecoveryEnrollmentCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<RecoveryEnrollmentCommit>>

    suspend fun createOrganization(command: CreateOrganizationCommand): StoreResult<OrganizationCreationCommit>
    suspend fun mutateOrganization(command: MutateOrganizationCommand): StoreResult<Organization>

    suspend fun createInvitation(command: CreateInvitationCommand): StoreResult<Invitation>
    suspend fun mutateInvitation(command: MutateInvitationCommand): StoreResult<Invitation>
    suspend fun enrollInvitation(command: EnrollInvitationCommand): StoreResult<InvitationEnrollmentCommit>

    suspend fun createMembership(command: CreateMembershipCommand): StoreResult<Membership>

    /** Atomically mutates membership and rejects removal/demotion of the organization's last active owner. */
    suspend fun mutateMembership(command: MutateMembershipCommand): StoreResult<Membership>

    suspend fun createServiceIdentity(
        command: CreateServiceIdentityCommand
    ): StoreResult<ServiceIdentityCreationCommit>
    suspend fun mutateServiceIdentity(command: MutateServiceIdentityCommand): StoreResult<ServiceIdentity>
    suspend fun createServiceCredential(command: CreateServiceCredentialCommand): StoreResult<ServiceCredential>
    suspend fun revokeServiceCredential(command: RevokeServiceCredentialCommand): StoreResult<ServiceCredential>

    suspend fun compareAndSetDeviceGrant(command: CompareAndSetDeviceGrantCommand): StoreResult<DeviceGrant>
    suspend fun exchangeDeviceGrant(command: ExchangeDeviceGrantCommand): StoreResult<DeviceTokenIssuanceCommit>
    suspend fun rotateDeviceRefreshToken(command: RotateDeviceRefreshTokenCommand): StoreResult<DeviceTokenRotationCommit>
    suspend fun revokeDeviceTokenFamily(
        command: RevokeDeviceTokenFamilyCommand
    ): StoreResult<DeviceTokenFamilyRevocationCommit>
    suspend fun rotateServiceCredential(command: RotateServiceCredentialCommand): StoreResult<ServiceCredentialRotationCommit>
    suspend fun linkExternalIdentity(command: LinkExternalIdentityCommand): StoreResult<ExternalIdentityLinkCommit>
    suspend fun recordExternalIdentityReplay(
        command: RecordExternalIdentityReplayCommand
    ): StoreResult<ExternalIdentityReplayReceipt>

    /** Idempotently records the SCIM operation ID and applies its user or membership mutation in one commit. */
    suspend fun applyScimMutation(command: ApplyScimMutationCommand): StoreResult<ScimMutationCommit>

    /** Applies a whole SCIM resource operation, including Group fan-out and tenant revocation, atomically. */
    suspend fun applyScimBatch(command: ApplyScimBatchCommand): StoreResult<ScimBatchCommit>
}
