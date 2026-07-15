package codes.yousef.aether.auth

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** Safe public passkey projection. Credential IDs and public keys never appear in wire DTOs. */
@Serializable
data class PasskeyView(
    val id: CredentialId,
    val name: String,
    val transports: Set<AuthenticatorTransport>,
    val backupEligible: Boolean,
    val backedUp: Boolean,
    val state: CredentialState,
    val createdAt: Instant,
    val lastUsedAt: Instant? = null
)

/** Safe public session projection. Token/CSRF digests and pseudonymous network values are omitted. */
@Serializable
data class IdentitySessionView(
    val id: SessionId,
    val label: String? = null,
    val platform: String? = null,
    val assurance: AuthenticationAssurance,
    val authenticationMethod: SessionAuthenticationMethod,
    val state: SessionState,
    val authenticatedAt: Instant,
    val lastUsedAt: Instant,
    val idleExpiresAt: Instant,
    val absoluteExpiresAt: Instant,
    val current: Boolean
)

@Serializable
data class SessionRevocationResult(
    val revokedSessionIds: List<SessionId>,
    val clearCurrentCookie: Boolean
)

class IdentityAccountManagementService(
    private val store: IdentityStore,
    private val runtime: IdentityRuntime,
    private val ids: IdentityIdFactory = IdentityIdFactory(runtime)
) {
    suspend fun listPasskeys(userId: UserId): IdentityOperationResult<List<PasskeyView>> =
        when (val result = store.listCredentialsForUser(userId)) {
            is StoreResult.Success -> IdentityOperationResult.Success(
                result.value.sortedBy { it.createdAt }.map(Credential::toView)
            )
            is StoreResult.Failure -> IdentityOperationResult.Failure(result.error.toIdentityErrorCode())
        }

    suspend fun renamePasskey(
        userId: UserId,
        credentialId: CredentialId,
        name: String,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<PasskeyView> {
        if (name.isBlank() || name.length > 200) {
            return IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID)
        }
        val existing = ownedCredential(userId, credentialId)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        if (existing.name == name) return IdentityOperationResult.Success(existing.toView())
        val now = runtime.clock.now()
        val audit = credentialAudit(existing, AuditAction.CREDENTIAL_RENAMED, now, request)
        return when (val result = store.mutateCredential(
            MutateCredentialCommand(
                credentialId = existing.id,
                expectedVersion = existing.version,
                replacement = existing.copy(
                    name = name,
                    version = existing.version + 1,
                    updatedAt = now
                ),
                auditEvent = audit
            )
        )) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value.toView())
            is StoreResult.Failure -> IdentityOperationResult.Failure(result.error.toIdentityErrorCode())
        }
    }

    suspend fun revokePasskey(
        userId: UserId,
        credentialId: CredentialId,
        reasonCode: String = "user_revoked",
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<PasskeyView> {
        if (reasonCode.isBlank() || reasonCode.length > 200) {
            return IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID)
        }
        val existing = ownedCredential(userId, credentialId)
            ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        if (existing.state == CredentialState.REVOKED) {
            return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        }
        val now = runtime.clock.now()
        val audit = credentialAudit(existing, AuditAction.CREDENTIAL_REVOKED, now, request, reasonCode)
        return when (val result = store.mutateCredential(
            MutateCredentialCommand(
                credentialId = existing.id,
                expectedVersion = existing.version,
                replacement = existing.copy(
                    state = CredentialState.REVOKED,
                    version = existing.version + 1,
                    updatedAt = now,
                    revokedAt = now,
                    revocationReasonCode = reasonCode
                ),
                auditEvent = audit
            )
        )) {
            is StoreResult.Success -> IdentityOperationResult.Success(result.value.toView())
            is StoreResult.Failure -> IdentityOperationResult.Failure(result.error.toIdentityErrorCode())
        }
    }

    suspend fun listSessions(
        userId: UserId,
        currentSessionId: SessionId
    ): IdentityOperationResult<List<IdentitySessionView>> = when (val result = store.listSessionsForUser(userId)) {
        is StoreResult.Success -> IdentityOperationResult.Success(
            result.value.sortedByDescending { it.lastUsedAt }.map { it.toView(currentSessionId) }
        )
        is StoreResult.Failure -> IdentityOperationResult.Failure(result.error.toIdentityErrorCode())
    }

    suspend fun logout(
        userId: UserId,
        sessionId: SessionId,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<SessionRevocationResult> = revokeOne(
        userId = userId,
        sessionId = sessionId,
        currentSessionId = sessionId,
        reasonCode = "logout",
        request = request
    )

    suspend fun revokeOne(
        userId: UserId,
        sessionId: SessionId,
        currentSessionId: SessionId,
        reasonCode: String = "user_revoked_device",
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<SessionRevocationResult> {
        if (reasonCode.isBlank() || reasonCode.length > 200) {
            return IdentityOperationResult.Failure(IdentityErrorCode.REQUEST_INVALID)
        }
        val session = when (val found = store.findSession(sessionId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return IdentityOperationResult.Failure(found.error.toIdentityErrorCode())
        } ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        if (session.userId != userId || session.state != SessionState.ACTIVE) {
            return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        }
        val now = runtime.clock.now()
        val audit = sessionAudit(userId, session.id, now, reasonCode, request)
        return when (val result = store.revokeSession(
            RevokeSessionCommand(session.id, session.version, now, reasonCode, audit)
        )) {
            is StoreResult.Success -> IdentityOperationResult.Success(
                SessionRevocationResult(listOf(session.id), session.id == currentSessionId)
            )
            is StoreResult.Failure -> IdentityOperationResult.Failure(result.error.toIdentityErrorCode())
        }
    }

    suspend fun revokeOtherSessions(
        userId: UserId,
        currentSessionId: SessionId,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<SessionRevocationResult> = revokeByEpoch(
        userId,
        exceptSessionId = currentSessionId,
        clearCurrentCookie = false,
        reasonCode = "user_revoked_other_devices",
        request = request
    )

    suspend fun revokeAllSessions(
        userId: UserId,
        request: AuditRequestMetadata? = null
    ): IdentityOperationResult<SessionRevocationResult> = revokeByEpoch(
        userId,
        exceptSessionId = null,
        clearCurrentCookie = true,
        reasonCode = "user_revoked_all_sessions",
        request = request
    )

    private suspend fun revokeByEpoch(
        userId: UserId,
        exceptSessionId: SessionId?,
        clearCurrentCookie: Boolean,
        reasonCode: String,
        request: AuditRequestMetadata?
    ): IdentityOperationResult<SessionRevocationResult> {
        val user = when (val found = store.findUser(userId)) {
            is StoreResult.Success -> found.value
            is StoreResult.Failure -> return IdentityOperationResult.Failure(found.error.toIdentityErrorCode())
        } ?: return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        if (exceptSessionId != null) {
            val current = when (val found = store.findSession(exceptSessionId)) {
                is StoreResult.Success -> found.value
                is StoreResult.Failure -> return IdentityOperationResult.Failure(found.error.toIdentityErrorCode())
            }
            if (current?.userId != user.id || current.state != SessionState.ACTIVE ||
                current.userSessionEpoch != user.sessionEpoch
            ) return IdentityOperationResult.Failure(IdentityErrorCode.NOT_FOUND)
        }
        val now = runtime.clock.now()
        val audit = AuditEvent(
            id = ids.newAuditEventId(),
            actor = AuditActor(AuditActorType.USER, userId = user.id),
            action = AuditAction.SESSION_REVOKED,
            target = AuditTarget(AuditTargetType.USER, user.id.value),
            outcome = AuditOutcome.SUCCEEDED,
            reasonCode = reasonCode,
            request = request,
            occurredAt = now
        )
        return when (val result = store.revokeUserSessions(
            RevokeUserSessionsCommand(
                userId = user.id,
                expectedUserVersion = user.version,
                expectedSessionEpoch = user.sessionEpoch,
                newSessionEpoch = user.sessionEpoch + 1,
                exceptSessionId = exceptSessionId,
                revokedAt = now,
                reasonCode = reasonCode,
                auditEvent = audit
            )
        )) {
            is StoreResult.Success -> IdentityOperationResult.Success(
                SessionRevocationResult(result.value.revokedSessionIds, clearCurrentCookie)
            )
            is StoreResult.Failure -> IdentityOperationResult.Failure(result.error.toIdentityErrorCode())
        }
    }

    private suspend fun ownedCredential(userId: UserId, id: CredentialId): Credential? =
        when (val result = store.findCredential(id)) {
            is StoreResult.Success -> result.value?.takeIf { it.userId == userId }
            is StoreResult.Failure -> null
        }

    private fun credentialAudit(
        credential: Credential,
        action: AuditAction,
        at: Instant,
        request: AuditRequestMetadata?,
        reasonCode: String? = null
    ): AuditEvent = AuditEvent(
        id = ids.newAuditEventId(),
        actor = AuditActor(AuditActorType.USER, userId = credential.userId),
        action = action,
        target = AuditTarget(AuditTargetType.CREDENTIAL, credential.id.value),
        outcome = AuditOutcome.SUCCEEDED,
        reasonCode = reasonCode,
        request = request,
        occurredAt = at
    )

    private fun sessionAudit(
        userId: UserId,
        sessionId: SessionId,
        at: Instant,
        reasonCode: String,
        request: AuditRequestMetadata?
    ): AuditEvent = AuditEvent(
        id = ids.newAuditEventId(),
        actor = AuditActor(AuditActorType.USER, userId = userId),
        action = AuditAction.SESSION_REVOKED,
        target = AuditTarget(AuditTargetType.SESSION, sessionId.value),
        outcome = AuditOutcome.SUCCEEDED,
        reasonCode = reasonCode,
        request = request,
        occurredAt = at
    )
}

private fun Credential.toView(): PasskeyView = PasskeyView(
    id = id,
    name = name,
    transports = transports,
    backupEligible = backupEligible,
    backedUp = backedUp,
    state = state,
    createdAt = createdAt,
    lastUsedAt = lastUsedAt
)

private fun IdentitySession.toView(currentSessionId: SessionId): IdentitySessionView = IdentitySessionView(
    id = id,
    label = device.label,
    platform = device.platform,
    assurance = assurance,
    authenticationMethod = authenticationMethod,
    state = state,
    authenticatedAt = authenticatedAt,
    lastUsedAt = lastUsedAt,
    idleExpiresAt = idleExpiresAt,
    absoluteExpiresAt = absoluteExpiresAt,
    current = id == currentSessionId
)
