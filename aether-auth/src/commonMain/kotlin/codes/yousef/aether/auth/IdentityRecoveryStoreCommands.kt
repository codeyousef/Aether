package codes.yousef.aether.auth

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ActivateAdministrativeRecoveryTicketCommand(
    val challengeId: ChallengeId,
    val expectedChallengeVersion: Long,
    val activatedAt: Instant,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedChallengeVersion >= 0) { "Expected challenge version must not be negative" }
        require(auditEvent.action == AuditAction.RECOVERY_ADMIN_TICKET_DELIVERED &&
            auditEvent.outcome == AuditOutcome.SUCCEEDED && auditEvent.occurredAt == activatedAt &&
            auditEvent.target?.type == AuditTargetType.USER
        ) { "Administrative recovery activation requires a successful durable delivery audit event" }
    }
}

@Serializable
data class AdministrativeRecoveryTicketActivationCommit(
    val challenge: Challenge,
    val auditEvent: AuditEvent
)

@Serializable
data class RedeemAdministrativeRecoveryTicketCommand(
    val challengeId: ChallengeId,
    val expectedChallengeVersion: Long,
    val redeemedAt: Instant,
    val recoverySession: IdentitySession,
    val auditEvent: AuditEvent
) {
    init {
        require(expectedChallengeVersion >= 0) { "Expected challenge version must not be negative" }
        require(recoverySession.assurance == AuthenticationAssurance.RECOVERY &&
            recoverySession.createdAt == redeemedAt
        ) { "Administrative recovery tickets create only a fresh restricted recovery session" }
        require(auditEvent.action == AuditAction.RECOVERY_ADMIN_TICKET_USED) {
            "Administrative recovery redemption requires a ticket-used audit event"
        }
    }
}

@Serializable
data class AdministrativeRecoveryTicketRedemptionCommit(
    val challenge: Challenge,
    val recoverySession: IdentitySession,
    val auditEvent: AuditEvent
)

@Serializable
data class CompleteRecoveryEnrollmentCommand(
    val challengeId: ChallengeId,
    val expectedChallengeVersion: Long,
    val credential: Credential,
    val recoverySessionId: SessionId,
    val expectedRecoverySessionVersion: Long,
    val expectedUserVersion: Long,
    val expectedSessionEpoch: Long,
    val newSessionEpoch: Long,
    val expectedRecoveryGeneration: Long?,
    val newRecoveryGeneration: Long,
    val replacementRecoveryCodes: List<RecoveryCode>,
    val completedAt: Instant,
    val auditEvent: AuditEvent,
    val rejectionAuditEvent: AuditEvent
) {
    init {
        require(expectedChallengeVersion >= 0 && expectedRecoverySessionVersion >= 0 &&
            expectedUserVersion >= 0 && expectedSessionEpoch >= 0
        ) { "Expected recovery-enrollment versions must not be negative" }
        require(newSessionEpoch == expectedSessionEpoch + 1) {
            "Recovery enrollment must advance the user session epoch exactly once"
        }
        require(newRecoveryGeneration == (expectedRecoveryGeneration?.plus(1) ?: 0L)) {
            "Recovery enrollment must advance the recovery-code generation exactly once"
        }
        require(credential.version == 0L && credential.state == CredentialState.ACTIVE) {
            "Recovery enrollment requires a new active credential"
        }
        require(replacementRecoveryCodes.size == 10 && replacementRecoveryCodes.all {
            it.userId == credential.userId && it.generation == newRecoveryGeneration &&
                it.version == 0L && it.state == RecoveryCodeState.ACTIVE
        }) { "Recovery enrollment must install exactly ten active replacement codes" }
        require(replacementRecoveryCodes.map { it.id }.toSet().size == 10 &&
            replacementRecoveryCodes.map { it.publicSelector }.toSet().size == 10 &&
            replacementRecoveryCodes.map { it.secretDigest }.toSet().size == 10
        ) { "Recovery-enrollment code IDs, selectors, and digests must be unique" }
        require(auditEvent.action == AuditAction.RECOVERY_ENROLLMENT_COMPLETED) {
            "Recovery enrollment requires an enrollment-completed audit event"
        }
        requireWebAuthnStoreRejectionAudit(challengeId, completedAt, rejectionAuditEvent)
    }
}

@Serializable
data class RecoveryEnrollmentCommit(
    val challenge: Challenge,
    val credential: Credential,
    val user: User,
    val revokedSessionIds: List<SessionId>,
    val recoveryGeneration: Long,
    val recoveryCodes: List<RecoveryCode>,
    val auditEvent: AuditEvent
)
