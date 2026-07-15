package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*

/** Seed data for entities that are read by [IdentityStore] but do not have a public create command. */
data class InMemoryIdentityStoreSeed(
    val bootstrapCompleted: Boolean = false,
    val users: List<User> = emptyList(),
    val credentials: List<Credential> = emptyList(),
    val sessions: List<IdentitySession> = emptyList(),
    val organizations: List<Organization> = emptyList(),
    val memberships: List<Membership> = emptyList(),
    val invitations: List<Invitation> = emptyList(),
    val serviceIdentities: List<ServiceIdentity> = emptyList(),
    val serviceCredentials: List<ServiceCredential> = emptyList(),
    val externalIdentities: List<ExternalIdentity> = emptyList(),
    val federationProviderControls: List<FederationProviderControl> = emptyList(),
    val challenges: List<Challenge> = emptyList(),
    val recoveryCodes: List<RecoveryCode> = emptyList(),
    val deviceGrants: List<DeviceGrant> = emptyList(),
    val deviceTokenFamilies: List<DeviceTokenFamily> = emptyList(),
    val deviceAccessTokens: List<DeviceAccessToken> = emptyList(),
    val deviceRefreshTokens: List<DeviceRefreshToken> = emptyList(),
    val replayReceipts: List<ExternalIdentityReplayReceipt> = emptyList(),
    val scimGroups: List<ScimGroup> = emptyList(),
    val auditEvents: List<AuditEvent> = emptyList()
)

/** Immutable diagnostic snapshot for assertions. */
data class InMemoryIdentityStoreSnapshot(
    val bootstrapCompleted: Boolean,
    val users: List<User>,
    val credentials: List<Credential>,
    val sessions: List<IdentitySession>,
    val organizations: List<Organization>,
    val memberships: List<Membership>,
    val invitations: List<Invitation>,
    val serviceIdentities: List<ServiceIdentity>,
    val serviceCredentials: List<ServiceCredential>,
    val externalIdentities: List<ExternalIdentity>,
    val federationProviderControls: List<FederationProviderControl>,
    val challenges: List<Challenge>,
    val recoveryCodes: List<RecoveryCode>,
    val deviceGrants: List<DeviceGrant>,
    val deviceTokenFamilies: List<DeviceTokenFamily>,
    val deviceAccessTokens: List<DeviceAccessToken>,
    val deviceRefreshTokens: List<DeviceRefreshToken>,
    val replayReceipts: List<ExternalIdentityReplayReceipt>,
    val scimGroups: List<ScimGroup>,
    val auditEvents: List<AuditEvent>,
    val appliedScimOperationIds: Set<ScimOperationId>,
    val appliedScimBatchOperationIds: Set<ScimOperationId>
)

/**
 * Deterministic coroutine-safe reference implementation of [IdentityStore].
 *
 * Every command runs against a copy of the current state under one lock. The copy is published
 * only after all checks pass, matching transactional rollback semantics expected from real
 * adapters. This implementation is intentionally optimized for conformance tests, not throughput.
 */
class InMemoryIdentityStore(
    seed: InMemoryIdentityStoreSeed = InMemoryIdentityStoreSeed()
) : IdentityStore {
    private val lock = CoroutineSafeLock()
    private var state: State = State.from(seed)

    override suspend fun findUser(id: UserId): StoreResult<User?> = read { it.users[id] }

    override suspend fun findUserByEmail(email: EmailAddress): StoreResult<User?> = read { current ->
        val normalized = normalizeEmail(email)
        current.users.values.firstOrNull { it.primaryEmail?.let(::normalizeEmail) == normalized }
    }

    override suspend fun findCredential(id: CredentialId): StoreResult<Credential?> = read { it.credentials[id] }

    override suspend fun findCredentialByWebAuthnId(id: WebAuthnCredentialId): StoreResult<Credential?> =
        read { current -> current.credentials.values.firstOrNull { it.webAuthnId == id } }

    override suspend fun listCredentialsForUser(userId: UserId): StoreResult<List<Credential>> = read { current ->
        current.credentials.values.filter { it.userId == userId }.sortedBy { it.id.value }
    }

    override suspend fun findSession(id: SessionId): StoreResult<IdentitySession?> = read { it.sessions[id] }

    override suspend fun listSessionsForUser(userId: UserId): StoreResult<List<IdentitySession>> = read { current ->
        current.sessions.values.filter { it.userId == userId }.sortedBy { it.id.value }
    }

    override suspend fun findOrganization(id: OrganizationId): StoreResult<Organization?> = read { it.organizations[id] }

    override suspend fun findOrganizationBySlug(slug: String): StoreResult<Organization?> =
        read { current -> current.organizations.values.firstOrNull { it.slug == slug } }

    override suspend fun listOrganizationsForUser(userId: UserId): StoreResult<List<Organization>> = read { current ->
        current.memberships.values.asSequence()
            .filter { it.userId == userId && it.state == MembershipState.ACTIVE }
            .mapNotNull { current.organizations[it.organizationId] }
            .filter { it.state == OrganizationState.ACTIVE }
            .distinctBy { it.id }
            .sortedBy { it.id.value }
            .toList()
    }

    override suspend fun findMembership(id: MembershipId): StoreResult<Membership?> = read { it.memberships[id] }

    override suspend fun findMembershipForUser(
        userId: UserId,
        organizationId: OrganizationId
    ): StoreResult<Membership?> = read { current ->
        current.memberships.values.firstOrNull {
            it.userId == userId && it.organizationId == organizationId
        }
    }

    override suspend fun listMembershipsForOrganization(
        organizationId: OrganizationId
    ): StoreResult<List<Membership>> = read { current ->
        current.memberships.values.filter { it.organizationId == organizationId }.sortedBy { it.id.value }
    }

    override suspend fun findInvitation(id: InvitationId): StoreResult<Invitation?> = read { it.invitations[id] }

    override suspend fun findInvitationByTokenDigest(digest: SecretDigest): StoreResult<Invitation?> =
        read { current -> current.invitations.values.firstOrNull { it.tokenDigest == digest } }

    override suspend fun listInvitationsForOrganization(
        organizationId: OrganizationId
    ): StoreResult<List<Invitation>> = read { current ->
        current.invitations.values.filter { it.organizationId == organizationId }.sortedBy { it.id.value }
    }

    override suspend fun findServiceIdentity(id: ServiceIdentityId): StoreResult<ServiceIdentity?> =
        read { it.serviceIdentities[id] }

    override suspend fun listServiceIdentitiesForOrganization(
        organizationId: OrganizationId
    ): StoreResult<List<ServiceIdentity>> = read { current ->
        current.serviceIdentities.values.filter { it.organizationId == organizationId }.sortedBy { it.id.value }
    }

    override suspend fun findServiceCredentialByPrefix(publicPrefix: String): StoreResult<ServiceCredential?> =
        read { current -> current.serviceCredentials.values.firstOrNull { it.publicPrefix == publicPrefix } }

    override suspend fun listServiceCredentialsForIdentity(
        serviceIdentityId: ServiceIdentityId
    ): StoreResult<List<ServiceCredential>> = read { current ->
        current.serviceCredentials.values.filter { it.serviceIdentityId == serviceIdentityId }.sortedBy { it.id.value }
    }

    override suspend fun findExternalIdentity(
        provider: String,
        subject: ExternalSubject
    ): StoreResult<ExternalIdentity?> = read { current ->
        current.externalIdentities.values.firstOrNull { it.provider == provider && it.subject == subject }
    }

    override suspend fun findFederationProviderControl(
        organizationId: OrganizationId,
        providerId: String
    ): StoreResult<FederationProviderControl?> = read { current ->
        current.federationProviderControls.values.firstOrNull {
            it.organizationId == organizationId && it.providerId == providerId
        }
    }

    override suspend fun findFederationProviderControlByStorageKey(
        storageKey: String
    ): StoreResult<FederationProviderControl?> = read { current ->
        current.federationProviderControls[storageKey]
    }

    override suspend fun findScimGroup(
        provider: String,
        organizationId: OrganizationId,
        id: String
    ): StoreResult<ScimGroup?> = read { current ->
        current.scimGroups[id]?.takeIf { it.provider == provider && it.organizationId == organizationId }
    }

    override suspend fun findChallenge(id: ChallengeId): StoreResult<Challenge?> = read { it.challenges[id] }

    override suspend fun findRecoveryCodeBySelector(publicSelector: String): StoreResult<RecoveryCode?> =
        read { current -> current.recoveryCodes.values.firstOrNull { it.publicSelector == publicSelector } }

    override suspend fun listRecoveryCodesForUser(userId: UserId): StoreResult<List<RecoveryCode>> = read { current ->
        current.recoveryCodes.values.filter { it.userId == userId }.sortedBy { it.id.value }
    }

    override suspend fun findDeviceGrant(id: DeviceGrantId): StoreResult<DeviceGrant?> = read { it.deviceGrants[id] }

    override suspend fun findDeviceGrantByDeviceCodeDigest(digest: SecretDigest): StoreResult<DeviceGrant?> =
        read { current -> current.deviceGrants.values.firstOrNull { it.deviceCodeDigest == digest } }

    override suspend fun findDeviceGrantByUserCodeDigest(digest: SecretDigest): StoreResult<DeviceGrant?> =
        read { current -> current.deviceGrants.values.firstOrNull { it.userCodeDigest == digest } }

    override suspend fun findDeviceTokenFamily(id: DeviceTokenFamilyId): StoreResult<DeviceTokenFamily?> =
        read { it.deviceTokenFamilies[id] }

    override suspend fun findDeviceAccessTokenBySelector(publicSelector: String): StoreResult<DeviceAccessToken?> =
        read { current -> current.deviceAccessTokens.values.firstOrNull { it.publicSelector == publicSelector } }

    override suspend fun findDeviceRefreshTokenBySelector(publicSelector: String): StoreResult<DeviceRefreshToken?> =
        read { current -> current.deviceRefreshTokens.values.firstOrNull { it.publicSelector == publicSelector } }

    override suspend fun listAuditEventsForOrganization(
        request: OrganizationAuditEventPageRequest
    ): StoreResult<OrganizationAuditEventPage> = read { current ->
        val ordered = current.auditEvents.values.asSequence()
            .filter { it.organizationId == request.organizationId }
            .filter { event ->
                request.cursor?.let { cursor ->
                    event.occurredAt < cursor.occurredAt ||
                        event.occurredAt == cursor.occurredAt && event.id.value < cursor.id.value
                } ?: true
            }
            .sortedWith(
                compareByDescending<AuditEvent> { it.occurredAt }
                    .thenByDescending { it.id.value }
            )
            .take(request.limit + 1)
            .toList()
        val events = ordered.take(request.limit)
        OrganizationAuditEventPage(
            organizationId = request.organizationId,
            events = events,
            nextCursor = events.lastOrNull()?.toOrganizationAuditCursor().takeIf {
                ordered.size > request.limit
            }
        )
    }

    override suspend fun purgeAuditEvents(
        command: PurgeAuditEventsCommand
    ): StoreResult<PurgeAuditEventsCommit> = atomic {
        val expired = auditEvents.values.asSequence()
            .filter { it.occurredAt < command.occurredBefore }
            .sortedWith(compareBy<AuditEvent> { it.occurredAt }.thenBy { it.id.value })
            .take(command.maximumEvents + 1)
            .toList()
        expired.take(command.maximumEvents).forEach { auditEvents.remove(it.id) }
        PurgeAuditEventsCommit(
            deletedCount = minOf(expired.size, command.maximumEvents),
            hasMore = expired.size > command.maximumEvents
        )
    }

    override suspend fun createChallenge(command: CreateChallengeCommand): StoreResult<Challenge> = atomic {
        val challenge = command.challenge
        requireAbsent(challenges, challenge.id)
        requireUnique(challenges.values.none { it.challengeDigest == challenge.challengeDigest })
        requireChallengeFederationLease(challenge, command.federationProviderLease)
        command.auditEvent?.let { requireAuditAvailable(it) }
        challenges[challenge.id] = challenge
        command.auditEvent?.let { appendAudit(it) }
        challenge
    }

    override suspend fun consumeChallenge(command: ConsumeChallengeCommand): StoreResult<Challenge> = atomic {
        val existing = if (command.terminalState == ChallengeState.EXPIRED) {
            val candidate = requirePresent(challenges, command.challengeId)
            if (candidate.state != ChallengeState.PENDING || command.consumedAt < candidate.expiresAt) {
                abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            requireVersion(candidate.version, command.expectedVersion)
            candidate
        } else {
            requireChallenge(command.challengeId, command.expectedVersion, command.consumedAt)
        }
        requireChallengeFederationLease(existing, command.federationProviderLease)
        command.auditEvent?.let { requireAuditAvailable(it) }
        val replacement = existing.copy(
            state = command.terminalState,
            attemptCount = existing.attemptCount + if (command.terminalState == ChallengeState.FAILED) 1 else 0,
            version = existing.version + 1,
            consumedAt = command.consumedAt
        )
        challenges[existing.id] = replacement
        command.auditEvent?.let { appendAudit(it) }
        replacement
    }

    override suspend fun appendAuditEvent(event: AuditEvent): StoreResult<AuditEvent> = atomic {
        requireAuditAvailable(event)
        appendAudit(event)
        event
    }

    override suspend fun bootstrapIdentity(
        command: BootstrapIdentityCommand
    ): StoreResult<BootstrapIdentityCommit> = atomic {
        if (bootstrapReceipt != null || users.isNotEmpty() || credentials.isNotEmpty() || sessions.isNotEmpty() ||
            organizations.isNotEmpty() || memberships.isNotEmpty() || invitations.isNotEmpty() ||
            serviceIdentities.isNotEmpty() || serviceCredentials.isNotEmpty() || externalIdentities.isNotEmpty() ||
            federationProviderControls.isNotEmpty() ||
            challenges.isNotEmpty() || recoveryCodes.isNotEmpty() || deviceGrants.isNotEmpty() ||
            deviceTokenFamilies.isNotEmpty() || deviceAccessTokens.isNotEmpty() || deviceRefreshTokens.isNotEmpty()
        ) abort(IdentityStoreErrorCode.ALREADY_EXISTS)
        requireUniqueEmail(command.user)
        requireNewSession(command.enrollmentSession, command.user)
        requireAuditAvailable(command.auditEvent)
        bootstrapReceipt = command.bootstrapSecretDigest
        users[command.user.id] = command.user
        organizations[command.organization.id] = command.organization
        memberships[command.ownerMembership.id] = command.ownerMembership
        sessions[command.enrollmentSession.id] = command.enrollmentSession
        appendAudit(command.auditEvent)
        BootstrapIdentityCommit(
            command.user,
            command.organization,
            command.ownerMembership,
            command.enrollmentSession,
            command.auditEvent
        )
    }

    override suspend fun completeCredentialRegistration(
        command: CompleteCredentialRegistrationCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<CredentialRegistrationCommit>> = atomicWebAuthn(
        command.challengeId,
        command.expectedChallengeVersion,
        command.auditEvent.occurredAt,
        command.rejectionAuditEvent
    ) {
        val completedAt = command.auditEvent.occurredAt
        val challenge = requireChallenge(command.challengeId, command.expectedChallengeVersion, completedAt)
        if (challenge.purpose != ChallengePurpose.WEBAUTHN_REGISTRATION) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        challenge.userId?.let { if (it != command.credential.userId) abort(IdentityStoreErrorCode.INVALID_TRANSITION) }
        requireAbsent(credentials, command.credential.id)
        requireUnique(credentials.values.none { it.webAuthnId == command.credential.webAuthnId })
        requireAuditAvailable(command.auditEvent)

        val replacementUser = command.user
        if (replacementUser == null) {
            requirePresent(users, command.credential.userId)
        } else {
            val expectedVersion = command.expectedUserVersion!!
            if (expectedVersion == -1L) {
                requireAbsent(users, replacementUser.id)
                if (replacementUser.version != 0L) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            } else {
                val existing = requirePresent(users, replacementUser.id)
                requireVersion(existing.version, expectedVersion)
                if (replacementUser.version != expectedVersion + 1) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            requireUniqueEmail(replacementUser)
        }

        val consumedChallenge = consumeChallengeValue(challenge, completedAt)
        challenges[challenge.id] = consumedChallenge
        credentials[command.credential.id] = command.credential
        replacementUser?.let { users[it.id] = it }
        appendAudit(command.auditEvent)
        CredentialRegistrationCommit(consumedChallenge, command.credential, replacementUser, command.auditEvent)
    }

    override suspend fun completeCredentialAuthentication(
        command: CompleteCredentialAuthenticationCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<CredentialAuthenticationCommit>> = atomicWebAuthn(
        command.challengeId,
        command.expectedChallengeVersion,
        command.authenticatedAt,
        command.rejectionAuditEvent
    ) {
        val challenge = requireChallenge(
            command.challengeId,
            command.expectedChallengeVersion,
            command.authenticatedAt
        )
        if (challenge.purpose != ChallengePurpose.WEBAUTHN_AUTHENTICATION &&
            challenge.purpose != ChallengePurpose.STEP_UP
        ) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        val credential = requirePresent(credentials, command.credentialId)
        requireVersion(credential.version, command.expectedCredentialVersion)
        if (credential.state != CredentialState.ACTIVE) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        if (credential.signCount != 0L && command.newSignCount != 0L && command.newSignCount <= credential.signCount) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        if (credential.backupEligible != command.backupEligible) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        challenge.userId?.let { if (it != credential.userId) abort(IdentityStoreErrorCode.INVALID_TRANSITION) }

        val user = requirePresent(users, credential.userId)
        requireNewSession(command.session, user)
        requireAuditAvailable(command.auditEvent)

        val replacedSession = command.replacedSessionId?.let { replacedId ->
            val existing = requireActiveSession(
                replacedId,
                command.expectedReplacedSessionVersion!!,
                command.authenticatedAt
            )
            requireFederatedSessionProvider(existing)
            if (existing.userId != credential.userId ||
                command.session.rotatedFromId != existing.id ||
                command.session.familyId != existing.familyId ||
                command.session.rotationCounter != existing.rotationCounter + 1
            ) {
                abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            existing
        }
        if (replacedSession == null) requireStandaloneSession(command.session)

        val updatedCredential = credential.copy(
            signCount = command.newSignCount,
            backupEligible = command.backupEligible,
            backedUp = command.backedUp,
            version = credential.version + 1,
            updatedAt = command.authenticatedAt,
            lastUsedAt = command.authenticatedAt
        )
        val consumedChallenge = consumeChallengeValue(challenge, command.authenticatedAt)
        val rotatedSession = replacedSession?.let { rotateSessionValue(it, command.session.id) }

        challenges[challenge.id] = consumedChallenge
        credentials[credential.id] = updatedCredential
        sessions[command.session.id] = command.session
        rotatedSession?.let { sessions[it.id] = it }
        appendAudit(command.auditEvent)
        CredentialAuthenticationCommit(
            challenge = consumedChallenge,
            credential = updatedCredential,
            session = command.session,
            replacedSession = rotatedSession,
            auditEvent = command.auditEvent
        )
    }

    override suspend fun quarantineCredentialAuthentication(
        command: QuarantineCredentialAuthenticationCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<CredentialQuarantineCommit>> = atomicWebAuthn(
        command.challengeId,
        command.expectedChallengeVersion,
        command.detectedAt,
        command.rejectionAuditEvent
    ) {
        val challenge = requireChallenge(
            command.challengeId,
            command.expectedChallengeVersion,
            command.detectedAt
        )
        if (challenge.purpose != ChallengePurpose.WEBAUTHN_AUTHENTICATION &&
            challenge.purpose != ChallengePurpose.STEP_UP
        ) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        val credential = requirePresent(credentials, command.credentialId)
        requireVersion(credential.version, command.expectedCredentialVersion)
        if (credential.state != CredentialState.ACTIVE || credential.signCount == 0L ||
            command.observedSignCount > credential.signCount
        ) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        if (credential.backupEligible != command.backupEligible) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        challenge.userId?.let { if (it != credential.userId) abort(IdentityStoreErrorCode.INVALID_TRANSITION) }
        requireAuditAvailable(command.auditEvent)

        val consumedChallenge = consumeChallengeValue(challenge, command.detectedAt)
        val quarantinedCredential = credential.copy(
            signCount = command.observedSignCount,
            backupEligible = command.backupEligible,
            backedUp = command.backedUp,
            state = CredentialState.SUSPECTED_CLONE,
            version = credential.version + 1,
            updatedAt = command.detectedAt,
            lastUsedAt = command.detectedAt,
            revocationReasonCode = "signature_counter_anomaly"
        )
        challenges[challenge.id] = consumedChallenge
        credentials[credential.id] = quarantinedCredential
        appendAudit(command.auditEvent)
        CredentialQuarantineCommit(consumedChallenge, quarantinedCredential, command.auditEvent)
    }

    override suspend fun mutateCredential(command: MutateCredentialCommand): StoreResult<Credential> = atomic {
        val existing = requirePresent(credentials, command.credentialId)
        requireVersion(existing.version, command.expectedVersion)
        val replacement = command.replacement
        if (replacement.webAuthnId != existing.webAuthnId || replacement.userId != existing.userId ||
            replacement.publicKey != existing.publicKey || replacement.signCount != existing.signCount ||
            replacement.backupEligible != existing.backupEligible || replacement.backedUp != existing.backedUp ||
            replacement.discoverable != existing.discoverable || replacement.createdAt != existing.createdAt ||
            replacement.lastUsedAt != existing.lastUsedAt || replacement.updatedAt < existing.updatedAt
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        when (command.auditEvent.action) {
            AuditAction.CREDENTIAL_RENAMED -> if (replacement.state != existing.state ||
                replacement.name == existing.name || replacement.revokedAt != existing.revokedAt ||
                replacement.revocationReasonCode != existing.revocationReasonCode
            ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            AuditAction.CREDENTIAL_REVOKED -> if (existing.state == CredentialState.REVOKED ||
                replacement.state != CredentialState.REVOKED || replacement.revokedAt == null ||
                replacement.revocationReasonCode.isNullOrBlank()
            ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            else -> abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireAuditAvailable(command.auditEvent)
        credentials[existing.id] = replacement
        appendAudit(command.auditEvent)
        replacement
    }

    override suspend fun createSession(command: CreateSessionCommand): StoreResult<IdentitySession> = atomic {
        val user = requirePresent(users, command.session.userId)
        requireNewSession(command.session, user)
        requireStandaloneSession(command.session)
        requireAuditAvailable(command.auditEvent)
        sessions[command.session.id] = command.session
        appendAudit(command.auditEvent)
        command.session
    }

    override suspend fun touchIdentitySession(
        command: TouchIdentitySessionCommand
    ): StoreResult<IdentitySession> = atomic {
        val session = requirePresent(sessions, command.sessionId)
        if (session.state != SessionState.ACTIVE) abort(IdentityStoreErrorCode.SESSION_NOT_ACTIVE)
        requireFederatedSessionProvider(session)
        if (command.lastUsedAt >= session.idleExpiresAt || command.lastUsedAt >= session.absoluteExpiresAt) {
            abort(IdentityStoreErrorCode.SESSION_EXPIRED)
        }
        requireVersion(session.version, command.expectedVersion)
        if (command.lastUsedAt < session.lastUsedAt ||
            command.idleExpiresAt < command.lastUsedAt ||
            command.idleExpiresAt > session.absoluteExpiresAt
        ) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        session.copy(
            version = session.version + 1,
            lastUsedAt = command.lastUsedAt,
            idleExpiresAt = command.idleExpiresAt
        ).also { sessions[session.id] = it }
    }

    override suspend fun rotateSession(command: RotateSessionCommand): StoreResult<SessionRotationCommit> = atomic {
        val previous = requireActiveSession(command.sessionId, command.expectedVersion, command.rotatedAt)
        requireFederatedSessionProvider(previous)
        val user = requirePresent(users, previous.userId)
        requireNewSession(command.replacement, user)
        if (command.replacement.userId != previous.userId ||
            command.replacement.familyId != previous.familyId ||
            command.replacement.rotationCounter != previous.rotationCounter + 1 ||
            command.replacement.createdAt != command.rotatedAt
        ) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireAuditAvailable(command.auditEvent)
        val rotated = rotateSessionValue(previous, command.replacement.id)
        sessions[rotated.id] = rotated
        sessions[command.replacement.id] = command.replacement
        appendAudit(command.auditEvent)
        SessionRotationCommit(rotated, command.replacement, command.auditEvent)
    }

    override suspend fun revokeSession(command: RevokeSessionCommand): StoreResult<IdentitySession> = atomic {
        val session = requireActiveSession(command.sessionId, command.expectedVersion, command.revokedAt)
        requireAuditAvailable(command.auditEvent)
        val revoked = revokeSessionValue(session, command.revokedAt, command.reasonCode)
        sessions[session.id] = revoked
        appendAudit(command.auditEvent)
        revoked
    }

    override suspend fun revokeUserSessions(
        command: RevokeUserSessionsCommand
    ): StoreResult<RevokeUserSessionsCommit> = atomic {
        val user = requirePresent(users, command.userId)
        requireVersion(user.version, command.expectedUserVersion)
        if (user.sessionEpoch != command.expectedSessionEpoch) abortVersion()
        command.exceptSessionId?.let { exceptId ->
            val except = requirePresent(sessions, exceptId)
            if (except.userId != user.id || except.state != SessionState.ACTIVE) {
                abort(IdentityStoreErrorCode.SESSION_NOT_ACTIVE)
            }
        }
        requireAuditAvailable(command.auditEvent)

        val revokedIds = mutableListOf<SessionId>()
        sessions.values.filter { it.userId == user.id && it.state == SessionState.ACTIVE }.forEach { session ->
            if (session.id == command.exceptSessionId) {
                sessions[session.id] = session.copy(
                    userSessionEpoch = command.newSessionEpoch,
                    version = session.version + 1
                )
            } else {
                sessions[session.id] = revokeSessionValue(session, command.revokedAt, command.reasonCode)
                revokedIds += session.id
            }
        }
        val updatedUser = user.copy(
            sessionEpoch = command.newSessionEpoch,
            version = user.version + 1,
            updatedAt = command.revokedAt
        )
        users[user.id] = updatedUser
        appendAudit(command.auditEvent)
        RevokeUserSessionsCommit(updatedUser, revokedIds.sortedBy { it.value }, command.auditEvent)
    }

    override suspend fun acquireFederationProviderLease(
        command: AcquireFederationProviderLeaseCommand
    ): StoreResult<FederationProviderLease> = atomic {
        val organization = requirePresent(organizations, command.organizationId)
        if (organization.state != OrganizationState.ACTIVE) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        val routeMatch = federationProviderControls.values.firstOrNull {
            it.organizationId == command.organizationId && it.providerId == command.providerId
        }
        val storageMatch = federationProviderControls[command.storageKey]
        val existing = routeMatch ?: storageMatch
        if (existing != null) {
            if (routeMatch != storageMatch || !existing.matches(command)) {
                abort(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
            }
            if (existing.state != FederationProviderState.ENABLED) {
                abort(IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED)
            }
            return@atomic existing.lease()
        }
        val created = FederationProviderControl(
            organizationId = command.organizationId,
            kind = command.kind,
            providerId = command.providerId,
            storageKey = command.storageKey,
            createdAt = command.acquiredAt,
            updatedAt = command.acquiredAt
        )
        federationProviderControls[created.storageKey] = created
        created.lease()
    }

    override suspend fun validateFederationProviderLease(
        lease: FederationProviderLease
    ): StoreResult<FederationProviderLease> = atomic {
        val current = federationProviderControls[lease.storageKey]
            ?: abort(IdentityStoreErrorCode.NOT_FOUND)
        if (current.state != FederationProviderState.ENABLED || current.lease() != lease) {
            abort(IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED)
        }
        lease
    }

    override suspend fun compareAndSetFederationProviderState(
        command: CompareAndSetFederationProviderStateCommand
    ): StoreResult<FederationProviderStateCommit> = atomic {
        val replacement = command.replacement
        val organization = requirePresent(organizations, replacement.organizationId)
        if (organization.state != OrganizationState.ACTIVE) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        val routeMatch = federationProviderControls.values.firstOrNull {
            it.organizationId == replacement.organizationId && it.providerId == replacement.providerId
        }
        val storageMatch = federationProviderControls[replacement.storageKey]
        if (command.expectedVersion == null) {
            if (routeMatch != null) {
                if (!routeMatch.hasSameIdentity(replacement)) abort(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
                abortVersion()
            }
            requireUnique(storageMatch == null)
            if (replacement.state != FederationProviderState.DISABLED ||
                replacement.version != 0L || replacement.sessionEpoch != 1L ||
                replacement.createdAt != replacement.updatedAt
            ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        } else {
            val expectedVersion = command.expectedVersion
                ?: abort(IdentityStoreErrorCode.VERSION_CONFLICT)
            if (routeMatch == null && storageMatch != null) {
                abort(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
            }
            val existing = routeMatch ?: abortVersion()
            requireVersion(existing.version, expectedVersion)
            if (!existing.hasSameIdentity(replacement) || storageMatch != existing) {
                abort(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
            }
            if (existing.createdAt != replacement.createdAt) {
                abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            if (replacement.updatedAt < existing.updatedAt) {
                abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            if (existing.state == replacement.state) {
                abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            when (replacement.state) {
                FederationProviderState.DISABLED -> if (
                    existing.state != FederationProviderState.ENABLED ||
                    replacement.sessionEpoch != existing.sessionEpoch + 1
                ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
                FederationProviderState.ENABLED -> if (
                    existing.state != FederationProviderState.DISABLED ||
                    replacement.sessionEpoch != existing.sessionEpoch
                ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
        }
        requireAuditAvailable(command.auditEvent)
        federationProviderControls[replacement.storageKey] = replacement
        appendAudit(command.auditEvent)
        FederationProviderStateCommit(replacement, command.auditEvent)
    }

    override suspend fun replaceRecoveryCodes(
        command: ReplaceRecoveryCodesCommand
    ): StoreResult<RecoveryCodeReplacementCommit> = atomic {
        requirePresent(users, command.userId)
        val currentGeneration = recoveryCodes.values
            .filter { it.userId == command.userId }
            .maxOfOrNull { it.generation }
        if (currentGeneration != command.expectedGeneration) abortVersion()
        if (command.codes.any { it.version != 0L }) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        command.codes.forEach { code ->
            requireAbsent(recoveryCodes, code.id)
            requireUnique(recoveryCodes.values.none { it.publicSelector == code.publicSelector })
            requireUnique(recoveryCodes.values.none { it.secretDigest == code.secretDigest })
        }
        requireUnique(command.codes.map { it.secretDigest }.toSet().size == command.codes.size)
        requireAuditAvailable(command.auditEvent)

        recoveryCodes.values.filter {
            it.userId == command.userId && it.state == RecoveryCodeState.ACTIVE
        }.forEach { existing ->
            recoveryCodes[existing.id] = existing.copy(
                state = RecoveryCodeState.REVOKED,
                version = existing.version + 1
            )
        }
        command.codes.forEach { recoveryCodes[it.id] = it }
        appendAudit(command.auditEvent)
        RecoveryCodeReplacementCommit(command.newGeneration, command.codes, command.auditEvent)
    }

    override suspend fun consumeRecoveryCode(
        command: ConsumeRecoveryCodeCommand
    ): StoreResult<RecoveryCodeConsumptionCommit> = atomic {
        val code = requirePresent(recoveryCodes, command.recoveryCodeId)
        val expiresAt = code.expiresAt
        if (code.state != RecoveryCodeState.ACTIVE ||
            (expiresAt != null && command.consumedAt >= expiresAt)
        ) {
            abort(IdentityStoreErrorCode.RECOVERY_CODE_NOT_ACTIVE)
        }
        requireVersion(code.version, command.expectedVersion)
        val user = requirePresent(users, code.userId)
        requireNewSession(command.recoverySession, user)
        requireStandaloneSession(command.recoverySession)
        if (command.recoverySession.userId != code.userId || command.recoverySession.createdAt != command.consumedAt) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireAuditAvailable(command.auditEvent)

        val consumed = code.copy(
            state = RecoveryCodeState.CONSUMED,
            version = code.version + 1,
            consumedAt = command.consumedAt
        )
        recoveryCodes[code.id] = consumed
        sessions[command.recoverySession.id] = command.recoverySession
        appendAudit(command.auditEvent)
        RecoveryCodeConsumptionCommit(consumed, command.recoverySession, command.auditEvent)
    }

    override suspend fun activateAdministrativeRecoveryTicket(
        command: ActivateAdministrativeRecoveryTicketCommand
    ): StoreResult<AdministrativeRecoveryTicketActivationCommit> = atomic {
        val challenge = requireChallenge(
            command.challengeId,
            command.expectedChallengeVersion,
            command.activatedAt
        )
        val userId = challenge.userId
        val auditTarget = command.auditEvent.target
        if (challenge.purpose != ChallengePurpose.ACCOUNT_RECOVERY || userId == null ||
            challenge.activatedAt != null || auditTarget?.type != AuditTargetType.USER ||
            auditTarget.id != userId.value
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAuditAvailable(command.auditEvent)
        val activated = challenge.copy(
            activatedAt = command.activatedAt,
            version = challenge.version + 1
        )
        challenges[challenge.id] = activated
        appendAudit(command.auditEvent)
        AdministrativeRecoveryTicketActivationCommit(activated, command.auditEvent)
    }

    override suspend fun redeemAdministrativeRecoveryTicket(
        command: RedeemAdministrativeRecoveryTicketCommand
    ): StoreResult<AdministrativeRecoveryTicketRedemptionCommit> = atomic {
        val challenge = requireChallenge(command.challengeId, command.expectedChallengeVersion, command.redeemedAt)
        val activatedAt = challenge.activatedAt
        if (challenge.purpose != ChallengePurpose.ACCOUNT_RECOVERY || challenge.userId == null ||
            activatedAt == null || activatedAt > command.redeemedAt ||
            command.recoverySession.userId != challenge.userId
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        val ticketUserId = requireNotNull(challenge.userId)
        val user = requirePresent(users, ticketUserId)
        requireNewSession(command.recoverySession, user)
        requireStandaloneSession(command.recoverySession)
        requireAuditAvailable(command.auditEvent)
        val consumed = consumeChallengeValue(challenge, command.redeemedAt)
        challenges[challenge.id] = consumed
        sessions[command.recoverySession.id] = command.recoverySession
        appendAudit(command.auditEvent)
        AdministrativeRecoveryTicketRedemptionCommit(consumed, command.recoverySession, command.auditEvent)
    }

    override suspend fun completeRecoveryEnrollment(
        command: CompleteRecoveryEnrollmentCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<RecoveryEnrollmentCommit>> = atomicWebAuthn(
        command.challengeId,
        command.expectedChallengeVersion,
        command.completedAt,
        command.rejectionAuditEvent
    ) {
        val challenge = requireChallenge(command.challengeId, command.expectedChallengeVersion, command.completedAt)
        if (challenge.purpose != ChallengePurpose.WEBAUTHN_REGISTRATION ||
            challenge.userId != command.credential.userId
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        val recoverySession = requireActiveSession(
            command.recoverySessionId,
            command.expectedRecoverySessionVersion,
            command.completedAt
        )
        if (recoverySession.userId != command.credential.userId ||
            recoverySession.assurance != AuthenticationAssurance.RECOVERY
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        val user = requirePresent(users, command.credential.userId)
        requireVersion(user.version, command.expectedUserVersion)
        if (user.sessionEpoch != command.expectedSessionEpoch ||
            recoverySession.userSessionEpoch != user.sessionEpoch
        ) abortVersion()
        val currentGeneration = recoveryCodes.values.asSequence()
            .filter { it.userId == user.id }
            .maxOfOrNull { it.generation }
        if (currentGeneration != command.expectedRecoveryGeneration) abortVersion()
        requireAbsent(credentials, command.credential.id)
        requireUnique(credentials.values.none { it.webAuthnId == command.credential.webAuthnId })
        command.replacementRecoveryCodes.forEach { code ->
            requireAbsent(recoveryCodes, code.id)
            requireUnique(recoveryCodes.values.none {
                it.publicSelector == code.publicSelector || it.secretDigest == code.secretDigest
            })
        }
        requireAuditAvailable(command.auditEvent)

        val consumedChallenge = consumeChallengeValue(challenge, command.completedAt)
        challenges[challenge.id] = consumedChallenge
        credentials[command.credential.id] = command.credential
        val updatedUser = user.copy(
            sessionEpoch = command.newSessionEpoch,
            version = user.version + 1,
            updatedAt = command.completedAt
        )
        users[user.id] = updatedUser
        val revokedSessionIds = sessions.values.filter {
            it.userId == user.id && it.state == SessionState.ACTIVE
        }.map { session ->
            sessions[session.id] = revokeSessionValue(session, command.completedAt, "recovery_enrollment_completed")
            session.id
        }.sortedBy { it.value }
        recoveryCodes.values.filter {
            it.userId == user.id && it.state == RecoveryCodeState.ACTIVE
        }.forEach { code ->
            recoveryCodes[code.id] = code.copy(
                state = RecoveryCodeState.REVOKED,
                version = code.version + 1
            )
        }
        command.replacementRecoveryCodes.forEach { recoveryCodes[it.id] = it }
        appendAudit(command.auditEvent)
        RecoveryEnrollmentCommit(
            consumedChallenge,
            command.credential,
            updatedUser,
            revokedSessionIds,
            command.newRecoveryGeneration,
            command.replacementRecoveryCodes,
            command.auditEvent
        )
    }

    override suspend fun createOrganization(
        command: CreateOrganizationCommand
    ): StoreResult<OrganizationCreationCommit> = atomic {
        requireAbsent(organizations, command.organization.id)
        requireAbsent(memberships, command.ownerMembership.id)
        requirePresent(users, command.ownerMembership.userId)
        requireUnique(organizations.values.none { it.slug == command.organization.slug })
        requireUnique(memberships.values.none {
            it.organizationId == command.organization.id && it.userId == command.ownerMembership.userId
        })
        requireAuditAvailable(command.auditEvent)
        organizations[command.organization.id] = command.organization
        memberships[command.ownerMembership.id] = command.ownerMembership
        appendAudit(command.auditEvent)
        OrganizationCreationCommit(command.organization, command.ownerMembership, command.auditEvent)
    }

    override suspend fun mutateOrganization(command: MutateOrganizationCommand): StoreResult<Organization> = atomic {
        val existing = requirePresent(organizations, command.organizationId)
        requireVersion(existing.version, command.expectedVersion)
        if (existing.state == OrganizationState.DELETED || existing.slug != command.replacement.slug) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireAuditAvailable(command.auditEvent)
        organizations[existing.id] = command.replacement
        appendAudit(command.auditEvent)
        command.replacement
    }

    override suspend fun createInvitation(command: CreateInvitationCommand): StoreResult<Invitation> = atomic {
        requireAbsent(invitations, command.invitation.id)
        val organization = requirePresent(organizations, command.invitation.organizationId)
        if (organization.state != OrganizationState.ACTIVE) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        command.invitation.invitedByUserId?.let { requirePresent(users, it) }
        requireUnique(invitations.values.none {
            it.tokenDigest == command.invitation.tokenDigest ||
                (it.organizationId == command.invitation.organizationId &&
                    normalizeEmail(it.email) == normalizeEmail(command.invitation.email) &&
                    it.state == InvitationState.PENDING)
        })
        requireAuditAvailable(command.auditEvent)
        invitations[command.invitation.id] = command.invitation
        appendAudit(command.auditEvent)
        command.invitation
    }

    override suspend fun mutateInvitation(command: MutateInvitationCommand): StoreResult<Invitation> = atomic {
        val existing = requirePresent(invitations, command.invitationId)
        requireVersion(existing.version, command.expectedVersion)
        if (existing.state != InvitationState.PENDING || existing.organizationId != command.replacement.organizationId ||
            existing.email != command.replacement.email || existing.role != command.replacement.role ||
            existing.tokenDigest != command.replacement.tokenDigest || existing.createdAt != command.replacement.createdAt ||
            existing.expiresAt != command.replacement.expiresAt
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAuditAvailable(command.auditEvent)
        invitations[existing.id] = command.replacement
        appendAudit(command.auditEvent)
        command.replacement
    }

    override suspend fun enrollInvitation(
        command: EnrollInvitationCommand
    ): StoreResult<InvitationEnrollmentCommit> = atomic {
        val invitation = requirePresent(invitations, command.invitationId)
        requireVersion(invitation.version, command.expectedInvitationVersion)
        if (invitation.tokenDigest != command.expectedTokenDigest ||
            invitation.state != InvitationState.PENDING || invitation.expiresAt <= command.enrolledAt ||
            normalizeEmail(invitation.email) != normalizeEmail(requireNotNull(command.user.primaryEmail)) ||
            command.membership.organizationId != invitation.organizationId ||
            command.membership.role != invitation.role ||
            command.auditEvent.organizationId != invitation.organizationId
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        val organization = requirePresent(organizations, invitation.organizationId)
        if (organization.state != OrganizationState.ACTIVE) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAbsent(users, command.user.id)
        requireUniqueEmail(command.user)
        requireAbsent(memberships, command.membership.id)
        requireUnique(memberships.values.none {
            it.userId == command.user.id && it.organizationId == invitation.organizationId
        })
        requireNewSession(command.enrollmentSession, command.user)
        requireStandaloneSession(command.enrollmentSession)
        requireAuditAvailable(command.auditEvent)

        val accepted = invitation.copy(
            state = InvitationState.ACCEPTED,
            version = invitation.version + 1,
            acceptedAt = command.enrolledAt,
            acceptedByUserId = command.user.id
        )
        users[command.user.id] = command.user
        memberships[command.membership.id] = command.membership
        sessions[command.enrollmentSession.id] = command.enrollmentSession
        invitations[invitation.id] = accepted
        appendAudit(command.auditEvent)
        InvitationEnrollmentCommit(
            invitation = accepted,
            user = command.user,
            membership = command.membership,
            enrollmentSession = command.enrollmentSession,
            auditEvent = command.auditEvent
        )
    }

    override suspend fun createMembership(command: CreateMembershipCommand): StoreResult<Membership> = atomic {
        val membership = command.membership
        requireAbsent(memberships, membership.id)
        requirePresent(users, membership.userId)
        requirePresent(organizations, membership.organizationId)
        requireUnique(memberships.values.none {
            it.userId == membership.userId && it.organizationId == membership.organizationId
        })
        requireAuditAvailable(command.auditEvent)

        val invitation = command.invitationId?.let { invitationId ->
            val existing = requirePresent(invitations, invitationId)
            requireVersion(existing.version, command.expectedInvitationVersion!!)
            if (existing.state != InvitationState.PENDING || existing.expiresAt <= command.auditEvent.occurredAt ||
                existing.organizationId != membership.organizationId
            ) {
                abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            val userEmail = users[membership.userId]?.primaryEmail
            if (userEmail != null && normalizeEmail(userEmail) != normalizeEmail(existing.email)) {
                abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            existing.copy(
                state = InvitationState.ACCEPTED,
                version = existing.version + 1,
                acceptedAt = command.auditEvent.occurredAt,
                acceptedByUserId = membership.userId
            )
        }

        memberships[membership.id] = membership
        invitation?.let { invitations[it.id] = it }
        appendAudit(command.auditEvent)
        membership
    }

    override suspend fun mutateMembership(command: MutateMembershipCommand): StoreResult<Membership> = atomic {
        val existing = requirePresent(memberships, command.membershipId)
        requireVersion(existing.version, command.expectedVersion)
        if (existing.organizationId != command.replacement.organizationId ||
            existing.userId != command.replacement.userId
        ) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireLastOwnerPreserved(existing, command.replacement)
        requireAuditAvailable(command.auditEvent)
        command.expectedUserVersion?.let { expectedUserVersion ->
            val user = requirePresent(users, existing.userId)
            requireVersion(user.version, expectedUserVersion)
            if (user.sessionEpoch != command.expectedSessionEpoch) abortVersion()
            users[user.id] = user.copy(
                sessionEpoch = requireNotNull(command.newSessionEpoch),
                version = user.version + 1,
                updatedAt = requireNotNull(command.sessionsRevokedAt)
            )
            sessions.values.filter {
                it.userId == user.id && it.state == SessionState.ACTIVE
            }.forEach { session ->
                sessions[session.id] = revokeSessionValue(
                    session,
                    requireNotNull(command.sessionsRevokedAt),
                    requireNotNull(command.sessionRevocationReasonCode)
                )
            }
        }
        memberships[existing.id] = command.replacement
        appendAudit(command.auditEvent)
        command.replacement
    }

    override suspend fun createServiceIdentity(
        command: CreateServiceIdentityCommand
    ): StoreResult<ServiceIdentityCreationCommit> = atomic {
        requireAbsent(serviceIdentities, command.identity.id)
        requireAbsent(serviceCredentials, command.initialCredential.id)
        val organization = requirePresent(organizations, command.identity.organizationId)
        if (organization.state != OrganizationState.ACTIVE ||
            command.initialCredential.expiresAt?.let { it > command.identity.createdAt } != true
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireUnique(serviceCredentials.values.none {
            it.publicPrefix == command.initialCredential.publicPrefix ||
                it.secretDigest == command.initialCredential.secretDigest
        })
        requireAuditAvailable(command.auditEvent)
        serviceIdentities[command.identity.id] = command.identity
        serviceCredentials[command.initialCredential.id] = command.initialCredential
        appendAudit(command.auditEvent)
        ServiceIdentityCreationCommit(command.identity, command.initialCredential, command.auditEvent)
    }

    override suspend fun mutateServiceIdentity(
        command: MutateServiceIdentityCommand
    ): StoreResult<ServiceIdentity> = atomic {
        val existing = requirePresent(serviceIdentities, command.serviceIdentityId)
        requireVersion(existing.version, command.expectedVersion)
        if (existing.state == ServiceIdentityState.REVOKED ||
            existing.organizationId != command.replacement.organizationId ||
            existing.createdAt != command.replacement.createdAt
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAuditAvailable(command.auditEvent)
        serviceIdentities[existing.id] = command.replacement
        if (command.replacement.state == ServiceIdentityState.REVOKED) {
            serviceCredentials.values.filter {
                it.serviceIdentityId == existing.id && it.state == ServiceCredentialState.ACTIVE
            }.forEach { credential ->
                serviceCredentials[credential.id] = credential.copy(
                    state = ServiceCredentialState.REVOKED,
                    version = credential.version + 1,
                    revokedAt = command.changedAt
                )
            }
        }
        appendAudit(command.auditEvent)
        command.replacement
    }

    override suspend fun createServiceCredential(
        command: CreateServiceCredentialCommand
    ): StoreResult<ServiceCredential> = atomic {
        val identity = requirePresent(serviceIdentities, command.credential.serviceIdentityId)
        if (identity.state != ServiceIdentityState.ACTIVE ||
            !identity.capabilities.containsAll(command.credential.capabilities)
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAbsent(serviceCredentials, command.credential.id)
        requireUnique(serviceCredentials.values.none {
            it.publicPrefix == command.credential.publicPrefix || it.secretDigest == command.credential.secretDigest
        })
        requireAuditAvailable(command.auditEvent)
        serviceCredentials[command.credential.id] = command.credential
        appendAudit(command.auditEvent)
        command.credential
    }

    override suspend fun revokeServiceCredential(
        command: RevokeServiceCredentialCommand
    ): StoreResult<ServiceCredential> = atomic {
        val existing = requirePresent(serviceCredentials, command.credentialId)
        requireVersion(existing.version, command.expectedVersion)
        if (existing.state != ServiceCredentialState.ACTIVE && existing.state != ServiceCredentialState.ROTATED) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireAuditAvailable(command.auditEvent)
        val revoked = existing.copy(
            state = ServiceCredentialState.REVOKED,
            version = existing.version + 1,
            revokedAt = command.revokedAt
        )
        serviceCredentials[existing.id] = revoked
        appendAudit(command.auditEvent)
        revoked
    }

    override suspend fun compareAndSetDeviceGrant(
        command: CompareAndSetDeviceGrantCommand
    ): StoreResult<DeviceGrant> = atomic {
        val replacement = command.replacement
        val existing = deviceGrants[replacement.id]
        val expectedVersion = command.expectedVersion
        if (expectedVersion == null) {
            if (existing != null) abortVersion()
            requireUnique(replacement.deviceCodeDigest != replacement.userCodeDigest)
            requireUnique(deviceGrants.values.none {
                it.deviceCodeDigest == replacement.deviceCodeDigest || it.userCodeDigest == replacement.userCodeDigest
            })
        } else {
            if (existing == null) abort(IdentityStoreErrorCode.NOT_FOUND)
            requireVersion(existing.version, expectedVersion)
            requireDeviceGrantTransition(existing, replacement)
        }
        requireAuditAvailable(command.auditEvent)
        deviceGrants[replacement.id] = replacement
        appendAudit(command.auditEvent)
        replacement
    }

    override suspend fun exchangeDeviceGrant(
        command: ExchangeDeviceGrantCommand
    ): StoreResult<DeviceTokenIssuanceCommit> = atomic {
        val grant = requirePresent(deviceGrants, command.deviceGrantId)
        requireVersion(grant.version, command.expectedDeviceGrantVersion)
        val membership = requirePresent(memberships, command.family.membershipId)
        val organization = requirePresent(organizations, command.family.organizationId)
        val user = requirePresent(users, command.family.userId)
        if (grant.state != DeviceGrantState.AUTHORIZED || command.exchangedAt >= grant.expiresAt ||
            command.family.clientId != grant.clientId ||
            command.family.userId != grant.userId || command.family.organizationId != grant.organizationId ||
            command.family.membershipId != grant.membershipId ||
            command.family.membershipVersion != grant.membershipVersion ||
            membership.userId != command.family.userId ||
            membership.organizationId != command.family.organizationId ||
            membership.state != MembershipState.ACTIVE ||
            membership.version != command.family.membershipVersion ||
            organization.state != OrganizationState.ACTIVE ||
            user.state != UserState.ACTIVE ||
            command.auditEvent.organizationId != command.family.organizationId ||
            command.auditEvent.target != AuditTarget(AuditTargetType.DEVICE_GRANT, grant.id.value) ||
            command.family.capabilities != grant.approvedCapabilities || command.family.createdAt != command.exchangedAt ||
            command.accessToken.createdAt != command.exchangedAt || command.refreshToken.createdAt != command.exchangedAt ||
            command.accessToken.expiresAt > command.family.expiresAt ||
            command.refreshToken.expiresAt > command.family.expiresAt
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAbsent(deviceTokenFamilies, command.family.id)
        requireAbsent(deviceAccessTokens, command.accessToken.id)
        requireAbsent(deviceRefreshTokens, command.refreshToken.id)
        requireUniqueDeviceToken(command.accessToken.publicSelector, command.accessToken.secretDigest)
        requireUniqueDeviceToken(command.refreshToken.publicSelector, command.refreshToken.secretDigest)
        requireAuditAvailable(command.auditEvent)
        val consumed = grant.copy(
            state = DeviceGrantState.CONSUMED,
            version = grant.version + 1,
            consumedAt = command.exchangedAt
        )
        deviceGrants[grant.id] = consumed
        deviceTokenFamilies[command.family.id] = command.family
        deviceAccessTokens[command.accessToken.id] = command.accessToken
        deviceRefreshTokens[command.refreshToken.id] = command.refreshToken
        appendAudit(command.auditEvent)
        DeviceTokenIssuanceCommit(
            consumed,
            command.family,
            command.accessToken,
            command.refreshToken,
            command.auditEvent
        )
    }

    override suspend fun rotateDeviceRefreshToken(
        command: RotateDeviceRefreshTokenCommand
    ): StoreResult<DeviceTokenRotationCommit> = atomic {
        val previous = requirePresent(deviceRefreshTokens, command.refreshTokenId)
        requireVersion(previous.version, command.expectedRefreshTokenVersion)
        val family = requirePresent(deviceTokenFamilies, previous.familyId)
        requireVersion(family.version, command.expectedFamilyVersion)
        val membership = requirePresent(memberships, family.membershipId)
        val organization = requirePresent(organizations, family.organizationId)
        val user = requirePresent(users, family.userId)
        if (previous.state != DeviceRefreshTokenState.ACTIVE || command.rotatedAt >= previous.expiresAt ||
            family.state != DeviceTokenFamilyState.ACTIVE || command.rotatedAt >= family.expiresAt ||
            membership.userId != family.userId || membership.organizationId != family.organizationId ||
            membership.state != MembershipState.ACTIVE || membership.version != family.membershipVersion ||
            organization.state != OrganizationState.ACTIVE ||
            user.state != UserState.ACTIVE ||
            command.auditEvent.organizationId != family.organizationId ||
            command.auditEvent.target != AuditTarget(AuditTargetType.DEVICE_GRANT, family.deviceGrantId.value) ||
            command.replacementAccessToken.familyId != family.id ||
            command.replacementRefreshToken.familyId != family.id ||
            command.replacementRefreshToken.rotationCounter != previous.rotationCounter + 1 ||
            command.replacementAccessToken.createdAt != command.rotatedAt ||
            command.replacementRefreshToken.createdAt != command.rotatedAt ||
            command.replacementAccessToken.expiresAt > family.expiresAt ||
            command.replacementRefreshToken.expiresAt > family.expiresAt
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAbsent(deviceAccessTokens, command.replacementAccessToken.id)
        requireAbsent(deviceRefreshTokens, command.replacementRefreshToken.id)
        requireUniqueDeviceToken(
            command.replacementAccessToken.publicSelector,
            command.replacementAccessToken.secretDigest
        )
        requireUniqueDeviceToken(
            command.replacementRefreshToken.publicSelector,
            command.replacementRefreshToken.secretDigest
        )
        requireAuditAvailable(command.auditEvent)
        val rotated = previous.copy(
            state = DeviceRefreshTokenState.ROTATED,
            version = previous.version + 1,
            rotatedToId = command.replacementRefreshToken.id,
            consumedAt = command.rotatedAt
        )
        deviceRefreshTokens[previous.id] = rotated
        deviceAccessTokens[command.replacementAccessToken.id] = command.replacementAccessToken
        deviceRefreshTokens[command.replacementRefreshToken.id] = command.replacementRefreshToken
        appendAudit(command.auditEvent)
        DeviceTokenRotationCommit(
            family,
            rotated,
            command.replacementAccessToken,
            command.replacementRefreshToken,
            command.auditEvent
        )
    }

    override suspend fun revokeDeviceTokenFamily(
        command: RevokeDeviceTokenFamilyCommand
    ): StoreResult<DeviceTokenFamilyRevocationCommit> = atomic {
        val family = requirePresent(deviceTokenFamilies, command.familyId)
        requireVersion(family.version, command.expectedFamilyVersion)
        if (family.state != DeviceTokenFamilyState.ACTIVE) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAuditAvailable(command.auditEvent)
        val revokedFamily = family.copy(
            state = DeviceTokenFamilyState.REVOKED,
            version = family.version + 1,
            revokedAt = command.revokedAt,
            revocationReasonCode = command.reasonCode
        )
        val accessIds = mutableListOf<DeviceAccessTokenId>()
        val refreshIds = mutableListOf<DeviceRefreshTokenId>()
        deviceAccessTokens.values.filter {
            it.familyId == family.id && it.state == DeviceAccessTokenState.ACTIVE
        }.forEach { token ->
            deviceAccessTokens[token.id] = token.copy(
                state = DeviceAccessTokenState.REVOKED,
                version = token.version + 1,
                revokedAt = command.revokedAt
            )
            accessIds += token.id
        }
        deviceRefreshTokens.values.filter {
            it.familyId == family.id && it.state == DeviceRefreshTokenState.ACTIVE
        }.forEach { token ->
            deviceRefreshTokens[token.id] = token.copy(
                state = DeviceRefreshTokenState.REVOKED,
                version = token.version + 1,
                revokedAt = command.revokedAt
            )
            refreshIds += token.id
        }
        deviceTokenFamilies[family.id] = revokedFamily
        appendAudit(command.auditEvent)
        DeviceTokenFamilyRevocationCommit(
            revokedFamily,
            accessIds.sortedBy { it.value },
            refreshIds.sortedBy { it.value },
            command.auditEvent
        )
    }

    override suspend fun rotateServiceCredential(
        command: RotateServiceCredentialCommand
    ): StoreResult<ServiceCredentialRotationCommit> = atomic {
        val existing = requirePresent(serviceCredentials, command.credentialId)
        requireVersion(existing.version, command.expectedVersion)
        val expiresAt = existing.expiresAt
        if (existing.state != ServiceCredentialState.ACTIVE ||
            (expiresAt != null && command.rotatedAt >= expiresAt)
        ) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        val identity = requirePresent(serviceIdentities, existing.serviceIdentityId)
        if (command.replacement.serviceIdentityId != existing.serviceIdentityId ||
            !identity.capabilities.containsAll(command.replacement.capabilities)
        ) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireAbsent(serviceCredentials, command.replacement.id)
        requireUnique(serviceCredentials.values.none {
            it.publicPrefix == command.replacement.publicPrefix ||
                it.secretDigest == command.replacement.secretDigest
        })
        requireAuditAvailable(command.auditEvent)

        val rotated = existing.copy(
            state = ServiceCredentialState.ROTATED,
            version = existing.version + 1,
            rotatedToId = command.replacement.id,
            rotatedAt = command.rotatedAt
        )
        serviceCredentials[rotated.id] = rotated
        serviceCredentials[command.replacement.id] = command.replacement
        appendAudit(command.auditEvent)
        ServiceCredentialRotationCommit(rotated, command.replacement, command.auditEvent)
    }

    override suspend fun linkExternalIdentity(
        command: LinkExternalIdentityCommand
    ): StoreResult<ExternalIdentityLinkCommit> = atomic {
        requireFederationProviderLease(command.federationProviderLease)
        val provisioning = command.jitProvisioning
        if (provisioning == null) {
            requirePresent(users, command.identity.userId)
        } else {
            val organization = requirePresent(organizations, provisioning.membership.organizationId)
            if (organization.state != OrganizationState.ACTIVE) {
                abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            requireAbsent(users, provisioning.user.id)
            requireUniqueEmail(provisioning.user)
            requireAbsent(memberships, provisioning.membership.id)
            requireUnique(memberships.values.none {
                it.userId == provisioning.user.id &&
                    it.organizationId == provisioning.membership.organizationId
            })
        }
        requireAbsent(externalIdentities, command.identity.id)
        requireUnique(externalIdentities.values.none {
            it.provider == command.identity.provider && it.subject == command.identity.subject
        })
        requireReplayAvailable(command.replayReceipt)
        if (command.replayReceipt.expiresAt <= command.auditEvent.occurredAt) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireAuditAvailable(command.auditEvent)
        provisioning?.let {
            users[it.user.id] = it.user
            memberships[it.membership.id] = it.membership
        }
        externalIdentities[command.identity.id] = command.identity
        replayReceipts[command.replayReceipt.id] = command.replayReceipt
        appendAudit(command.auditEvent)
        ExternalIdentityLinkCommit(
            identity = command.identity,
            replayReceipt = command.replayReceipt,
            auditEvent = command.auditEvent,
            provisionedUser = provisioning?.user,
            provisionedMembership = provisioning?.membership
        )
    }

    override suspend fun recordExternalIdentityReplay(
        command: RecordExternalIdentityReplayCommand
    ): StoreResult<ExternalIdentityReplayReceipt> = atomic {
        requireFederationProviderLease(command.federationProviderLease)
        requireReplayAvailable(command.replayReceipt)
        replayReceipts[command.replayReceipt.id] = command.replayReceipt
        command.replayReceipt
    }

    override suspend fun applyScimMutation(
        command: ApplyScimMutationCommand
    ): StoreResult<ScimMutationCommit> = atomic { applyScimMutationValue(command) }

    override suspend fun applyScimBatch(command: ApplyScimBatchCommand): StoreResult<ScimBatchCommit> = atomic {
        appliedScimBatches[command.operationId]?.let { existing ->
            if (existing.command != command) abort(IdentityStoreErrorCode.IDEMPOTENCY_CONFLICT)
            return@atomic existing.commit.copy(
                mutationCommits = existing.commit.mutationCommits.map {
                    it.copy(alreadyApplied = true, auditEvent = null)
                },
                alreadyApplied = true,
                auditEvent = null
            )
        }
        val organization = requirePresent(organizations, command.organizationId)
        if (organization.state != OrganizationState.ACTIVE) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAuditAvailable(command.auditEvent)
        validateScimBatchLastOwner(command)

        val mutationCommits = command.mutations.map { applyScimMutationValue(it, enforceLastOwner = false) }
        val group = command.group?.also { aggregate ->
            val existing = scimGroups[aggregate.id]
            val expectedVersion = requireNotNull(command.expectedGroupVersion)
            if (expectedVersion == 0L) {
                if (existing != null) abort(IdentityStoreErrorCode.ALREADY_EXISTS)
            } else {
                if (existing == null) abort(IdentityStoreErrorCode.NOT_FOUND)
                if (existing.provider != command.provider || existing.organizationId != command.organizationId) {
                    abort(IdentityStoreErrorCode.NOT_FOUND)
                }
                requireVersion(existing.version, expectedVersion)
                if (existing.createdAt != aggregate.createdAt) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            aggregate.memberUserIds.forEach { requirePresent(users, it) }
            scimGroups[aggregate.id] = aggregate
        }

        val revokedSessionIds = mutableListOf<SessionId>()
        val revokedFamilyIds = mutableListOf<DeviceTokenFamilyId>()
        val revokedAccessIds = mutableListOf<DeviceAccessTokenId>()
        val revokedRefreshIds = mutableListOf<DeviceRefreshTokenId>()
        command.revocations.forEach { revocation ->
            requirePresent(users, revocation.userId)
            if (revocation.revokeSessions) {
                sessions.values.filter { session ->
                    session.userId == revocation.userId && session.state == SessionState.ACTIVE &&
                        session.federationOrganizationId == command.organizationId
                }.forEach { session ->
                    sessions[session.id] = revokeSessionValue(session, command.auditEvent.occurredAt, revocation.reasonCode)
                    revokedSessionIds += session.id
                }
            }
            if (revocation.revokeDeviceTokenFamilies) {
                deviceTokenFamilies.values.filter { family ->
                    family.userId == revocation.userId && family.organizationId == command.organizationId &&
                        family.state == DeviceTokenFamilyState.ACTIVE
                }.forEach { family ->
                    deviceTokenFamilies[family.id] = family.copy(
                        state = DeviceTokenFamilyState.REVOKED,
                        version = family.version + 1,
                        revokedAt = command.auditEvent.occurredAt,
                        revocationReasonCode = revocation.reasonCode
                    )
                    revokedFamilyIds += family.id
                    deviceAccessTokens.values.filter {
                        it.familyId == family.id && it.state == DeviceAccessTokenState.ACTIVE
                    }.forEach { token ->
                        deviceAccessTokens[token.id] = token.copy(
                            state = DeviceAccessTokenState.REVOKED,
                            version = token.version + 1,
                            revokedAt = command.auditEvent.occurredAt
                        )
                        revokedAccessIds += token.id
                    }
                    deviceRefreshTokens.values.filter {
                        it.familyId == family.id && it.state == DeviceRefreshTokenState.ACTIVE
                    }.forEach { token ->
                        deviceRefreshTokens[token.id] = token.copy(
                            state = DeviceRefreshTokenState.REVOKED,
                            version = token.version + 1,
                            revokedAt = command.auditEvent.occurredAt
                        )
                        revokedRefreshIds += token.id
                    }
                }
            }
        }
        appendAudit(command.auditEvent)
        val commit = ScimBatchCommit(
            mutationCommits = mutationCommits,
            group = group,
            revokedSessionIds = revokedSessionIds.distinct().sortedBy { it.value },
            revokedDeviceTokenFamilyIds = revokedFamilyIds.distinct().sortedBy { it.value },
            revokedDeviceAccessTokenIds = revokedAccessIds.distinct().sortedBy { it.value },
            revokedDeviceRefreshTokenIds = revokedRefreshIds.distinct().sortedBy { it.value },
            alreadyApplied = false,
            auditEvent = command.auditEvent
        )
        appliedScimBatches[command.operationId] = AppliedScimBatch(command, commit)
        commit
    }

    suspend fun snapshot(): InMemoryIdentityStoreSnapshot = lock.withLock { state.snapshot() }

    suspend fun reset(seed: InMemoryIdentityStoreSeed = InMemoryIdentityStoreSeed()) {
        lock.withLock { state = State.from(seed) }
    }

    private suspend fun <T> read(block: (State) -> T): StoreResult<T> = lock.withLock {
        StoreResult.Success(block(state))
    }

    private suspend fun <T> atomic(block: State.() -> T): StoreResult<T> = lock.withLock {
        val working = state.copyState()
        try {
            val result = working.block()
            state = working
            StoreResult.Success(result)
        } catch (abort: StoreAbort) {
            StoreResult.Failure(abort.error)
        } catch (_: IllegalArgumentException) {
            StoreResult.Failure(IdentityStoreError(IdentityStoreErrorCode.INVALID_TRANSITION))
        }
    }

    private suspend fun <T : Any> atomicWebAuthn(
        challengeId: ChallengeId,
        expectedChallengeVersion: Long,
        attemptedAt: kotlin.time.Instant,
        rejectionAuditEvent: AuditEvent,
        block: State.() -> T
    ): StoreResult<WebAuthnCeremonyAttemptCommit<T>> = lock.withLock {
        fun reject(error: IdentityStoreError): StoreResult<WebAuthnCeremonyAttemptCommit<T>> {
            if (error.code == IdentityStoreErrorCode.UNAVAILABLE || error.code == IdentityStoreErrorCode.INTERNAL) {
                return StoreResult.Failure(error)
            }
            val rejectionState = state.copyState()
            return try {
                val challenge = rejectionState.requireChallenge(
                    challengeId,
                    expectedChallengeVersion,
                    attemptedAt
                )
                rejectionState.requireAuditAvailable(rejectionAuditEvent)
                val failed = challenge.copy(
                    state = ChallengeState.FAILED,
                    attemptCount = challenge.attemptCount + 1,
                    version = challenge.version + 1,
                    consumedAt = attemptedAt
                )
                rejectionState.challenges[challenge.id] = failed
                rejectionState.appendAudit(rejectionAuditEvent)
                state = rejectionState
                StoreResult.Success(
                    WebAuthnCeremonyAttemptCommit.rejected(
                        WebAuthnCeremonyRejectionCommit(
                            challenge = failed,
                            error = IdentityStoreError(error.code),
                            auditEvent = rejectionAuditEvent
                        )
                    )
                )
            } catch (abort: StoreAbort) {
                StoreResult.Failure(abort.error)
            } catch (_: IllegalArgumentException) {
                StoreResult.Failure(IdentityStoreError(IdentityStoreErrorCode.INVALID_TRANSITION))
            }
        }

        val working = state.copyState()
        try {
            val completion = working.block()
            state = working
            StoreResult.Success(WebAuthnCeremonyAttemptCommit.completed(completion))
        } catch (abort: StoreAbort) {
            reject(abort.error)
        } catch (_: IllegalArgumentException) {
            reject(IdentityStoreError(IdentityStoreErrorCode.INVALID_TRANSITION))
        }
    }

    private class StoreAbort(val error: IdentityStoreError) : Throwable()

    private fun abort(code: IdentityStoreErrorCode, retryable: Boolean = false): Nothing =
        throw StoreAbort(IdentityStoreError(code, retryable))

    private fun abortVersion(): Nothing = abort(IdentityStoreErrorCode.VERSION_CONFLICT, retryable = true)

    private fun requireVersion(actual: Long, expected: Long) {
        if (actual != expected) abortVersion()
    }

    private fun <K, V> requirePresent(values: Map<K, V>, key: K): V =
        values[key] ?: abort(IdentityStoreErrorCode.NOT_FOUND)

    private fun <K, V> requireAbsent(values: Map<K, V>, key: K) {
        if (values.containsKey(key)) abort(IdentityStoreErrorCode.ALREADY_EXISTS)
    }

    private fun requireUnique(isUnique: Boolean) {
        if (!isUnique) abort(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
    }

    private fun State.requireChallenge(id: ChallengeId, expectedVersion: Long, at: kotlin.time.Instant): Challenge {
        val challenge = requirePresent(challenges, id)
        if (challenge.state != ChallengeState.PENDING) abort(IdentityStoreErrorCode.CHALLENGE_NOT_PENDING)
        if (at >= challenge.expiresAt) abort(IdentityStoreErrorCode.CHALLENGE_EXPIRED)
        requireVersion(challenge.version, expectedVersion)
        return challenge
    }

    private fun consumeChallengeValue(challenge: Challenge, consumedAt: kotlin.time.Instant): Challenge = challenge.copy(
        state = ChallengeState.CONSUMED,
        version = challenge.version + 1,
        consumedAt = consumedAt
    )

    private fun State.requireChallengeFederationLease(
        challenge: Challenge,
        commandLease: FederationProviderLease?
    ) {
        if (challenge.federationProviderLease != commandLease) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        if (commandLease != null) requireFederationProviderLease(commandLease)
    }

    private fun State.requireFederationProviderLease(lease: FederationProviderLease) {
        val current = federationProviderControls[lease.storageKey]
            ?: abort(IdentityStoreErrorCode.NOT_FOUND)
        if (current.state != FederationProviderState.ENABLED || current.lease() != lease) {
            abort(IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED)
        }
    }

    private fun State.requireFederatedSessionProvider(session: IdentitySession) {
        val storageKey = session.federationProviderKey ?: return
        val current = federationProviderControls[storageKey]
            ?: abort(IdentityStoreErrorCode.NOT_FOUND)
        val expectedKind = when (session.authenticationMethod) {
            SessionAuthenticationMethod.OIDC -> FederationProviderKind.OIDC
            SessionAuthenticationMethod.SAML -> FederationProviderKind.SAML
            else -> abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        if (current.state != FederationProviderState.ENABLED ||
            current.organizationId != session.federationOrganizationId ||
            current.kind != expectedKind ||
            current.sessionEpoch != session.federationProviderSessionEpoch
        ) abort(IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED)
    }

    private fun State.requireNewSession(session: IdentitySession, user: User) {
        requireAbsent(sessions, session.id)
        requireUnique(sessions.values.none { it.tokenDigest == session.tokenDigest })
        if (session.state != SessionState.ACTIVE || session.version != 0L ||
            session.userId != user.id || session.userSessionEpoch != user.sessionEpoch || user.state != UserState.ACTIVE
        ) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireFederatedSessionProvider(session)
    }

    private fun requireStandaloneSession(session: IdentitySession) {
        if (session.familyId != session.id || session.rotatedFromId != null || session.rotationCounter != 0L) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
    }

    private fun State.requireActiveSession(
        id: SessionId,
        expectedVersion: Long,
        at: kotlin.time.Instant
    ): IdentitySession {
        val session = requirePresent(sessions, id)
        if (session.state != SessionState.ACTIVE) abort(IdentityStoreErrorCode.SESSION_NOT_ACTIVE)
        if (at >= session.idleExpiresAt || at >= session.absoluteExpiresAt) {
            abort(IdentityStoreErrorCode.SESSION_EXPIRED)
        }
        requireVersion(session.version, expectedVersion)
        return session
    }

    private fun rotateSessionValue(session: IdentitySession, replacementId: SessionId): IdentitySession = session.copy(
        state = SessionState.ROTATED,
        version = session.version + 1,
        rotatedToId = replacementId
    )

    private fun revokeSessionValue(
        session: IdentitySession,
        revokedAt: kotlin.time.Instant,
        reasonCode: String
    ): IdentitySession = session.copy(
        state = SessionState.REVOKED,
        version = session.version + 1,
        revokedAt = revokedAt,
        revocationReasonCode = reasonCode
    )

    private fun State.requireAuditAvailable(event: AuditEvent) {
        requireAbsent(auditEvents, event.id)
    }

    private fun State.appendAudit(event: AuditEvent) {
        auditEvents[event.id] = event
    }

    private fun State.requireUniqueEmail(user: User) {
        val email = user.primaryEmail ?: return
        val normalized = normalizeEmail(email)
        requireUnique(users.values.none {
            it.id != user.id && it.primaryEmail?.let(::normalizeEmail) == normalized
        })
    }

    private fun normalizeEmail(email: EmailAddress): String = email.value.lowercase()

    private fun State.requireLastOwnerPreserved(existing: Membership, replacement: Membership) {
        val removesOwner = existing.state == MembershipState.ACTIVE &&
            existing.role == OrganizationRole.OWNER &&
            (replacement.state != MembershipState.ACTIVE || replacement.role != OrganizationRole.OWNER)
        if (!removesOwner) return
        val hasAnotherOwner = memberships.values.any {
            it.id != existing.id &&
                it.organizationId == existing.organizationId &&
                it.state == MembershipState.ACTIVE &&
                it.role == OrganizationRole.OWNER
        }
        if (!hasAnotherOwner) abort(IdentityStoreErrorCode.LAST_OWNER)
    }

    private fun requireDeviceGrantTransition(existing: DeviceGrant, replacement: DeviceGrant) {
        if (existing.id != replacement.id ||
            existing.deviceCodeDigest != replacement.deviceCodeDigest ||
            existing.userCodeDigest != replacement.userCodeDigest ||
            existing.clientId != replacement.clientId ||
            existing.clientName != replacement.clientName ||
            existing.requestedCapabilities != replacement.requestedCapabilities ||
            existing.createdAt != replacement.createdAt ||
            existing.expiresAt != replacement.expiresAt
        ) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        val allowed = when (existing.state) {
            DeviceGrantState.PENDING -> replacement.state in setOf(
                DeviceGrantState.PENDING,
                DeviceGrantState.AUTHORIZED,
                DeviceGrantState.DENIED,
                DeviceGrantState.EXPIRED,
                DeviceGrantState.CANCELLED
            )
            DeviceGrantState.AUTHORIZED -> replacement.state in setOf(
                DeviceGrantState.AUTHORIZED,
                DeviceGrantState.CONSUMED,
                DeviceGrantState.EXPIRED,
                DeviceGrantState.CANCELLED
            )
            DeviceGrantState.DENIED,
            DeviceGrantState.CONSUMED,
            DeviceGrantState.EXPIRED,
            DeviceGrantState.CANCELLED -> false
        }
        if (!allowed) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
    }

    private fun State.requireReplayAvailable(receipt: ExternalIdentityReplayReceipt) {
        if (replayReceipts.containsKey(receipt.id) || replayReceipts.values.any {
            it.provider == receipt.provider && it.assertionDigest == receipt.assertionDigest
        }) {
            abort(IdentityStoreErrorCode.REPLAY_DETECTED)
        }
    }

    private fun FederationProviderControl.lease(): FederationProviderLease = FederationProviderLease(
        organizationId = organizationId,
        kind = kind,
        providerId = providerId,
        storageKey = storageKey,
        sessionEpoch = sessionEpoch,
        version = version
    )

    private fun FederationProviderControl.matches(
        command: AcquireFederationProviderLeaseCommand
    ): Boolean = organizationId == command.organizationId && kind == command.kind &&
        providerId == command.providerId && storageKey == command.storageKey

    private fun FederationProviderControl.hasSameIdentity(
        other: FederationProviderControl
    ): Boolean = organizationId == other.organizationId && kind == other.kind &&
        providerId == other.providerId && storageKey == other.storageKey

    private fun State.requireUniqueDeviceToken(selector: String, digest: SecretDigest) {
        requireUnique(deviceAccessTokens.values.none { it.publicSelector == selector || it.secretDigest == digest })
        requireUnique(deviceRefreshTokens.values.none { it.publicSelector == selector || it.secretDigest == digest })
    }

    private fun State.upsertScimUser(user: User) {
        val existing = users[user.id]
        if (existing == null) {
            if (user.version != 0L) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        } else if (user.version != existing.version + 1) {
            abortVersion()
        }
        requireUniqueEmail(user)
        users[user.id] = user
    }

    private fun State.upsertScimMembership(membership: Membership, enforceLastOwner: Boolean = true) {
        requirePresent(users, membership.userId)
        requirePresent(organizations, membership.organizationId)
        val existing = memberships[membership.id]
        if (existing == null) {
            if (membership.version != 0L) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            requireUnique(memberships.values.none {
                it.userId == membership.userId && it.organizationId == membership.organizationId
            })
        } else {
            if (membership.version != existing.version + 1 ||
                membership.userId != existing.userId || membership.organizationId != existing.organizationId
            ) {
                abortVersion()
            }
            if (enforceLastOwner) requireLastOwnerPreserved(existing, membership)
        }
        memberships[membership.id] = membership
    }

    private fun State.applyScimMutationValue(
        command: ApplyScimMutationCommand,
        enforceLastOwner: Boolean = true
    ): ScimMutationCommit {
        val operationId = command.mutation.operationId
        appliedScim[operationId]?.let { existing ->
            if (existing.command != command) abort(IdentityStoreErrorCode.IDEMPOTENCY_CONFLICT)
            return existing.commit.copy(alreadyApplied = true, auditEvent = null)
        }
        requireAuditAvailable(command.auditEvent)
        val commit = when (command.mutation.type) {
            ScimMutationType.UPSERT_USER,
            ScimMutationType.DEACTIVATE_USER -> {
                val user = command.mutation.user!!
                if (command.mutation.type == ScimMutationType.DEACTIVATE_USER && user.state != UserState.DEACTIVATED) {
                    abort(IdentityStoreErrorCode.INVALID_TRANSITION)
                }
                upsertScimUser(user)
                ScimMutationCommit(user = user, alreadyApplied = false, auditEvent = command.auditEvent)
            }
            ScimMutationType.UPSERT_MEMBERSHIP,
            ScimMutationType.REMOVE_MEMBERSHIP -> {
                val membership = command.mutation.membership!!
                if (command.mutation.type == ScimMutationType.REMOVE_MEMBERSHIP &&
                    membership.state != MembershipState.REMOVED
                ) {
                    abort(IdentityStoreErrorCode.INVALID_TRANSITION)
                }
                upsertScimMembership(membership, enforceLastOwner)
                ScimMutationCommit(membership = membership, alreadyApplied = false, auditEvent = command.auditEvent)
            }
        }
        appendAudit(command.auditEvent)
        appliedScim[operationId] = AppliedScim(command, commit)
        return commit
    }

    private fun State.validateScimBatchLastOwner(command: ApplyScimBatchCommand) {
        val replacements = command.mutations.mapNotNull { it.mutation.membership }
        if (replacements.isEmpty()) return
        val prospective = memberships.toMutableMap()
        replacements.forEach { prospective[it.id] = it }
        replacements.map { it.organizationId }.toSet().forEach { organizationId ->
            val currentOwners = memberships.values.count {
                it.organizationId == organizationId && it.state == MembershipState.ACTIVE &&
                    it.role == OrganizationRole.OWNER
            }
            val prospectiveOwners = prospective.values.count {
                it.organizationId == organizationId && it.state == MembershipState.ACTIVE &&
                    it.role == OrganizationRole.OWNER
            }
            if (currentOwners > 0 && prospectiveOwners == 0) abort(IdentityStoreErrorCode.LAST_OWNER)
        }
    }

    private data class AppliedScim(val command: ApplyScimMutationCommand, val commit: ScimMutationCommit)
    private data class AppliedScimBatch(val command: ApplyScimBatchCommand, val commit: ScimBatchCommit)

    private data class State(
        var bootstrapReceipt: SecretDigest?,
        val users: MutableMap<UserId, User>,
        val credentials: MutableMap<CredentialId, Credential>,
        val sessions: MutableMap<SessionId, IdentitySession>,
        val organizations: MutableMap<OrganizationId, Organization>,
        val memberships: MutableMap<MembershipId, Membership>,
        val invitations: MutableMap<InvitationId, Invitation>,
        val serviceIdentities: MutableMap<ServiceIdentityId, ServiceIdentity>,
        val serviceCredentials: MutableMap<ServiceCredentialId, ServiceCredential>,
        val externalIdentities: MutableMap<ExternalIdentityId, ExternalIdentity>,
        val federationProviderControls: MutableMap<String, FederationProviderControl>,
        val challenges: MutableMap<ChallengeId, Challenge>,
        val recoveryCodes: MutableMap<RecoveryCodeId, RecoveryCode>,
        val deviceGrants: MutableMap<DeviceGrantId, DeviceGrant>,
        val deviceTokenFamilies: MutableMap<DeviceTokenFamilyId, DeviceTokenFamily>,
        val deviceAccessTokens: MutableMap<DeviceAccessTokenId, DeviceAccessToken>,
        val deviceRefreshTokens: MutableMap<DeviceRefreshTokenId, DeviceRefreshToken>,
        val replayReceipts: MutableMap<ExternalReplayReceiptId, ExternalIdentityReplayReceipt>,
        val scimGroups: MutableMap<String, ScimGroup>,
        val auditEvents: MutableMap<AuditEventId, AuditEvent>,
        val appliedScim: MutableMap<ScimOperationId, AppliedScim>,
        val appliedScimBatches: MutableMap<ScimOperationId, AppliedScimBatch>
    ) {
        fun copyState(): State = State(
            bootstrapReceipt,
            users.toMutableMap(),
            credentials.toMutableMap(),
            sessions.toMutableMap(),
            organizations.toMutableMap(),
            memberships.toMutableMap(),
            invitations.toMutableMap(),
            serviceIdentities.toMutableMap(),
            serviceCredentials.toMutableMap(),
            externalIdentities.toMutableMap(),
            federationProviderControls.toMutableMap(),
            challenges.toMutableMap(),
            recoveryCodes.toMutableMap(),
            deviceGrants.toMutableMap(),
            deviceTokenFamilies.toMutableMap(),
            deviceAccessTokens.toMutableMap(),
            deviceRefreshTokens.toMutableMap(),
            replayReceipts.toMutableMap(),
            scimGroups.toMutableMap(),
            auditEvents.toMutableMap(),
            appliedScim.toMutableMap(),
            appliedScimBatches.toMutableMap()
        )

        fun snapshot(): InMemoryIdentityStoreSnapshot = InMemoryIdentityStoreSnapshot(
            bootstrapReceipt != null,
            users.values.sortedBy { it.id.value },
            credentials.values.sortedBy { it.id.value },
            sessions.values.sortedBy { it.id.value },
            organizations.values.sortedBy { it.id.value },
            memberships.values.sortedBy { it.id.value },
            invitations.values.sortedBy { it.id.value },
            serviceIdentities.values.sortedBy { it.id.value },
            serviceCredentials.values.sortedBy { it.id.value },
            externalIdentities.values.sortedBy { it.id.value },
            federationProviderControls.values.sortedWith(
                compareBy<FederationProviderControl> { it.organizationId.value }.thenBy { it.providerId }
            ),
            challenges.values.sortedBy { it.id.value },
            recoveryCodes.values.sortedBy { it.id.value },
            deviceGrants.values.sortedBy { it.id.value },
            deviceTokenFamilies.values.sortedBy { it.id.value },
            deviceAccessTokens.values.sortedBy { it.id.value },
            deviceRefreshTokens.values.sortedBy { it.id.value },
            replayReceipts.values.sortedBy { it.id.value },
            scimGroups.values.sortedBy { it.id },
            auditEvents.values.sortedBy { it.id.value },
            appliedScim.keys.toSet(),
            appliedScimBatches.keys.toSet()
        )

        companion object {
            fun from(seed: InMemoryIdentityStoreSeed): State {
                val state = State(
                    bootstrapReceipt = if (seed.bootstrapCompleted) {
                        SecretDigest(DigestAlgorithm.SHA256, "bootstrap-completed")
                    } else null,
                    users = uniqueMap(seed.users, User::id, "user"),
                    credentials = uniqueMap(seed.credentials, Credential::id, "credential"),
                    sessions = uniqueMap(seed.sessions, IdentitySession::id, "session"),
                    organizations = uniqueMap(seed.organizations, Organization::id, "organization"),
                    memberships = uniqueMap(seed.memberships, Membership::id, "membership"),
                    invitations = uniqueMap(seed.invitations, Invitation::id, "invitation"),
                    serviceIdentities = uniqueMap(seed.serviceIdentities, ServiceIdentity::id, "service identity"),
                    serviceCredentials = uniqueMap(seed.serviceCredentials, ServiceCredential::id, "service credential"),
                    externalIdentities = uniqueMap(seed.externalIdentities, ExternalIdentity::id, "external identity"),
                    federationProviderControls = uniqueMap(
                        seed.federationProviderControls,
                        FederationProviderControl::storageKey,
                        "federation provider control"
                    ),
                    challenges = uniqueMap(seed.challenges, Challenge::id, "challenge"),
                    recoveryCodes = uniqueMap(seed.recoveryCodes, RecoveryCode::id, "recovery code"),
                    deviceGrants = uniqueMap(seed.deviceGrants, DeviceGrant::id, "device grant"),
                    deviceTokenFamilies = uniqueMap(seed.deviceTokenFamilies, DeviceTokenFamily::id, "device token family"),
                    deviceAccessTokens = uniqueMap(seed.deviceAccessTokens, DeviceAccessToken::id, "device access token"),
                    deviceRefreshTokens = uniqueMap(seed.deviceRefreshTokens, DeviceRefreshToken::id, "device refresh token"),
                    replayReceipts = uniqueMap(seed.replayReceipts, ExternalIdentityReplayReceipt::id, "replay receipt"),
                    scimGroups = uniqueMap(seed.scimGroups, ScimGroup::id, "SCIM Group"),
                    auditEvents = uniqueMap(seed.auditEvents, AuditEvent::id, "audit event"),
                    appliedScim = mutableMapOf(),
                    appliedScimBatches = mutableMapOf()
                )
                state.validateSeed()
                return state
            }

            private fun State.validateSeed() {
                requireUniqueSeed(users.values.mapNotNull { it.primaryEmail?.value?.lowercase() }, "user email")
                requireUniqueSeed(credentials.values.map { it.webAuthnId }, "WebAuthn credential ID")
                requireUniqueSeed(sessions.values.map { it.tokenDigest }, "session token digest")
                requireUniqueSeed(organizations.values.map { it.slug }, "organization slug")
                requireUniqueSeed(memberships.values.map { it.organizationId to it.userId }, "organization membership")
                requireUniqueSeed(serviceCredentials.values.map { it.publicPrefix }, "service credential prefix")
                requireUniqueSeed(
                    externalIdentities.values.map { it.provider to it.subject },
                    "external provider subject"
                )
                requireUniqueSeed(
                    federationProviderControls.values.map { it.organizationId to it.providerId },
                    "federation provider route selector"
                )
                federationProviderControls.values.forEach { control ->
                    require(organizations.containsKey(control.organizationId)) {
                        "Federation provider control references a missing organization"
                    }
                }
                requireUniqueSeed(challenges.values.map { it.challengeDigest }, "challenge digest")
                requireUniqueSeed(recoveryCodes.values.map { it.publicSelector }, "recovery selector")
                requireUniqueSeed(
                    deviceAccessTokens.values.map { it.publicSelector } + deviceRefreshTokens.values.map { it.publicSelector },
                    "device token selector"
                )
                requireUniqueSeed(
                    deviceAccessTokens.values.map { it.secretDigest } + deviceRefreshTokens.values.map { it.secretDigest },
                    "device token digest"
                )
                requireUniqueSeed(
                    replayReceipts.values.map { it.provider to it.assertionDigest },
                    "external replay digest"
                )
                scimGroups.values.forEach { group ->
                    require(organizations.containsKey(group.organizationId)) {
                        "SCIM Group references a missing organization"
                    }
                    require(group.memberUserIds.all(users::containsKey)) {
                        "SCIM Group references a missing user"
                    }
                }
            }

            private fun <T> requireUniqueSeed(values: List<T>, description: String) {
                require(values.toSet().size == values.size) { "Duplicate $description in identity-store seed" }
            }

            private fun <K, V> uniqueMap(values: List<V>, key: (V) -> K, description: String): MutableMap<K, V> {
                val result = values.associateBy(key)
                require(result.size == values.size) { "Duplicate $description ID in identity-store seed" }
                return result.toMutableMap()
            }
        }
    }
}
