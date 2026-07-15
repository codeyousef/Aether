package codes.yousef.aether.auth.firestore

import codes.yousef.aether.auth.*
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Firestore implementation of [IdentityStore].
 *
 * Command methods execute as Firestore read/write transactions. Every entity mutation, uniqueness
 * claim, replay/idempotency receipt, and audit record is committed together with update-time or
 * exists preconditions. Call [initialize] at startup; the store fails closed until the environment
 * marker matches [FirestoreIdentityConfig].
 */
class FirestoreIdentityStore internal constructor(
    private val config: FirestoreIdentityConfig,
    private val runtime: IdentityRuntime,
    private val transport: FirestoreDocumentTransport,
    private val json: Json = defaultFirestoreJson()
) : IdentityStore {
    constructor(
        config: FirestoreIdentityConfig,
        runtime: IdentityRuntime,
        accessTokens: FirestoreAccessTokenProvider,
        json: Json = defaultFirestoreJson()
    ) : this(config, runtime, FirestoreRestTransport(config, runtime, accessTokens, json), json)

    private val initializationMutex = Mutex()
    private var initialized = false

    /** Verifies the pre-provisioned environment marker. Missing and mismatched markers fail closed. */
    suspend fun initialize(): StoreResult<Unit> = initializationMutex.withLock {
        if (initialized) return@withLock StoreResult.Success(Unit)
        when (val verification = verifyEnvironmentMarker()) {
            is StoreResult.Failure -> verification
            is StoreResult.Success -> {
                initialized = true
                verification
            }
        }
    }

    /** Explicit deployment action. Runtime startup never creates or overwrites this marker. */
    suspend fun provisionEnvironmentMarker(): StoreResult<Unit> = try {
        val existing = transport.get(environmentMarkerName())
        if (existing != null) {
            if (environmentMarkerMatches(existing)) StoreResult.Success(Unit)
            else failure(IdentityStoreErrorCode.INTERNAL)
        } else {
            transport.commit(
                transaction = null,
                writes = listOf(
                    FirestoreWrite(
                        update = FirestoreDocument(
                            name = environmentMarkerName(),
                            fields = mapOf(
                                FIELD_ENVIRONMENT to stringValue(config.environment.wireName),
                                FIELD_NAMESPACE to stringValue(config.namespace),
                                FIELD_SCHEMA_VERSION to
                                    integerValue(FIRESTORE_ENVIRONMENT_MARKER_SCHEMA_VERSION.toLong())
                            )
                        ),
                        currentDocument = FirestorePrecondition(exists = false)
                    )
                )
            )
            StoreResult.Success(Unit)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: FirestoreStoreException) {
        if (failure.safeError.code == IdentityStoreErrorCode.VERSION_CONFLICT) {
            verifyEnvironmentMarker()
        } else {
            StoreResult.Failure(failure.safeError)
        }
    } catch (_: Throwable) {
        StoreResult.Failure(FirestoreFailureMapper.internal())
    }

    private suspend fun verifyEnvironmentMarker(): StoreResult<Unit> = try {
        val marker = transport.get(environmentMarkerName())
            ?: return failure(IdentityStoreErrorCode.NOT_FOUND)
        if (environmentMarkerMatches(marker)) StoreResult.Success(Unit)
        else failure(IdentityStoreErrorCode.INTERNAL)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: FirestoreStoreException) {
        StoreResult.Failure(failure.safeError)
    } catch (_: Throwable) {
        StoreResult.Failure(FirestoreFailureMapper.internal())
    }

    private fun environmentMarkerMatches(marker: FirestoreDocument): Boolean =
        marker.fields[FIELD_ENVIRONMENT]?.stringValue == config.environment.wireName &&
            marker.fields[FIELD_NAMESPACE]?.stringValue == config.namespace &&
            marker.fields[FIELD_SCHEMA_VERSION]?.integerValue?.toIntOrNull() ==
            FIRESTORE_ENVIRONMENT_MARKER_SCHEMA_VERSION

    private fun environmentMarkerName(): String =
        "projects/${config.projectId}/databases/${config.databaseId}/documents/${config.environmentMarkerDocument}"

    override suspend fun findUser(id: UserId): StoreResult<User?> = readEntity(COLLECTION_USERS, id.value, User.serializer())

    override suspend fun findUserByEmail(email: EmailAddress): StoreResult<User?> = readUniqueEntity(
        uniqueKind = UNIQUE_EMAIL,
        uniqueValue = normalizeEmail(email),
        serializer = User.serializer()
    )

    override suspend fun findCredential(id: CredentialId): StoreResult<Credential?> =
        readEntity(COLLECTION_CREDENTIALS, id.value, Credential.serializer())

    override suspend fun findCredentialByWebAuthnId(id: WebAuthnCredentialId): StoreResult<Credential?> =
        readUniqueEntity(UNIQUE_WEBAUTHN_ID, id.encoded, Credential.serializer())

    override suspend fun listCredentialsForUser(userId: UserId): StoreResult<List<Credential>> =
        queryEntities(COLLECTION_CREDENTIALS, Credential.serializer(), FIELD_USER_ID, userId.value) { it.id.value }

    override suspend fun findSession(id: SessionId): StoreResult<IdentitySession?> =
        readEntity(COLLECTION_SESSIONS, id.value, IdentitySession.serializer())

    override suspend fun listSessionsForUser(userId: UserId): StoreResult<List<IdentitySession>> =
        queryEntities(COLLECTION_SESSIONS, IdentitySession.serializer(), FIELD_USER_ID, userId.value) { it.id.value }

    override suspend fun findOrganization(id: OrganizationId): StoreResult<Organization?> =
        readEntity(COLLECTION_ORGANIZATIONS, id.value, Organization.serializer())

    override suspend fun findOrganizationBySlug(slug: String): StoreResult<Organization?> =
        readUniqueEntity(UNIQUE_ORGANIZATION_SLUG, slug, Organization.serializer())

    override suspend fun listOrganizationsForUser(userId: UserId): StoreResult<List<Organization>> {
        requireInitialized() ?: return StoreResult.Failure(FirestoreFailureMapper.unavailable())
        return safeRead {
            transport.runQuery(parentName(), equalityQuery(COLLECTION_MEMBERSHIPS, FIELD_USER_ID, userId.value))
                .map { it.decode(Membership.serializer()) }
                .filter { it.state == MembershipState.ACTIVE }
                .mapNotNull { membership ->
                    transport.get(name(COLLECTION_ORGANIZATIONS, membership.organizationId.value))
                        ?.decode(Organization.serializer())
                }
                .filter { it.state == OrganizationState.ACTIVE }
                .distinctBy { it.id }
                .sortedBy { it.id.value }
        }
    }

    override suspend fun findMembership(id: MembershipId): StoreResult<Membership?> =
        readEntity(COLLECTION_MEMBERSHIPS, id.value, Membership.serializer())

    override suspend fun findMembershipForUser(
        userId: UserId,
        organizationId: OrganizationId
    ): StoreResult<Membership?> = readUniqueEntity(
        uniqueKind = UNIQUE_MEMBERSHIP,
        uniqueValue = membershipUniqueValue(userId, organizationId),
        serializer = Membership.serializer()
    )

    override suspend fun listMembershipsForOrganization(
        organizationId: OrganizationId
    ): StoreResult<List<Membership>> = queryEntities(
        COLLECTION_MEMBERSHIPS,
        Membership.serializer(),
        FIELD_ORGANIZATION_ID,
        organizationId.value
    ) { it.id.value }

    override suspend fun findInvitation(id: InvitationId): StoreResult<Invitation?> =
        readEntity(COLLECTION_INVITATIONS, id.value, Invitation.serializer())

    override suspend fun findInvitationByTokenDigest(digest: SecretDigest): StoreResult<Invitation?> =
        readUniqueEntity(UNIQUE_INVITATION_DIGEST, digestUniqueValue(digest), Invitation.serializer())

    override suspend fun listInvitationsForOrganization(
        organizationId: OrganizationId
    ): StoreResult<List<Invitation>> = queryEntities(
        COLLECTION_INVITATIONS,
        Invitation.serializer(),
        FIELD_ORGANIZATION_ID,
        organizationId.value
    ) { it.id.value }

    override suspend fun findServiceIdentity(id: ServiceIdentityId): StoreResult<ServiceIdentity?> =
        readEntity(COLLECTION_SERVICE_IDENTITIES, id.value, ServiceIdentity.serializer())

    override suspend fun listServiceIdentitiesForOrganization(
        organizationId: OrganizationId
    ): StoreResult<List<ServiceIdentity>> = queryEntities(
        COLLECTION_SERVICE_IDENTITIES,
        ServiceIdentity.serializer(),
        FIELD_ORGANIZATION_ID,
        organizationId.value
    ) { it.id.value }

    override suspend fun findServiceCredentialByPrefix(publicPrefix: String): StoreResult<ServiceCredential?> =
        readUniqueEntity(UNIQUE_SERVICE_PREFIX, publicPrefix, ServiceCredential.serializer())

    override suspend fun listServiceCredentialsForIdentity(
        serviceIdentityId: ServiceIdentityId
    ): StoreResult<List<ServiceCredential>> = queryEntities(
        COLLECTION_SERVICE_CREDENTIALS,
        ServiceCredential.serializer(),
        FIELD_SERVICE_IDENTITY_ID,
        serviceIdentityId.value
    ) { it.id.value }

    override suspend fun findExternalIdentity(
        provider: String,
        subject: ExternalSubject
    ): StoreResult<ExternalIdentity?> = readUniqueEntity(
        UNIQUE_EXTERNAL_IDENTITY,
        externalUniqueValue(provider, subject),
        ExternalIdentity.serializer()
    )

    override suspend fun findFederationProviderControl(
        organizationId: OrganizationId,
        providerId: String
    ): StoreResult<FederationProviderControl?> = readUniqueEntity(
        UNIQUE_FEDERATION_PROVIDER_ROUTE,
        federationProviderRouteUniqueValue(organizationId, providerId),
        FederationProviderControl.serializer()
    )

    override suspend fun findFederationProviderControlByStorageKey(
        storageKey: String
    ): StoreResult<FederationProviderControl?> = readUniqueEntity(
        UNIQUE_FEDERATION_PROVIDER_STORAGE_KEY,
        storageKey,
        FederationProviderControl.serializer()
    )

    override suspend fun findScimGroup(
        provider: String,
        organizationId: OrganizationId,
        id: String
    ): StoreResult<ScimGroup?> = when (val result = readEntity(COLLECTION_SCIM_GROUPS, id, ScimGroup.serializer())) {
        is StoreResult.Failure -> result
        is StoreResult.Success -> StoreResult.Success(
            result.value?.takeIf { it.provider == provider && it.organizationId == organizationId }
        )
    }

    override suspend fun findChallenge(id: ChallengeId): StoreResult<Challenge?> =
        readEntity(COLLECTION_CHALLENGES, id.value, Challenge.serializer())

    override suspend fun findRecoveryCodeBySelector(publicSelector: String): StoreResult<RecoveryCode?> =
        readUniqueEntity(UNIQUE_RECOVERY_SELECTOR, publicSelector, RecoveryCode.serializer())

    override suspend fun listRecoveryCodesForUser(userId: UserId): StoreResult<List<RecoveryCode>> = queryEntities(
        COLLECTION_RECOVERY_CODES,
        RecoveryCode.serializer(),
        FIELD_USER_ID,
        userId.value
    ) { it.id.value }

    override suspend fun findDeviceGrant(id: DeviceGrantId): StoreResult<DeviceGrant?> =
        readEntity(COLLECTION_DEVICE_GRANTS, id.value, DeviceGrant.serializer())

    override suspend fun findDeviceGrantByDeviceCodeDigest(digest: SecretDigest): StoreResult<DeviceGrant?> =
        readUniqueEntity(UNIQUE_DEVICE_CODE, digestUniqueValue(digest), DeviceGrant.serializer())

    override suspend fun findDeviceGrantByUserCodeDigest(digest: SecretDigest): StoreResult<DeviceGrant?> =
        readUniqueEntity(UNIQUE_USER_CODE, digestUniqueValue(digest), DeviceGrant.serializer())

    override suspend fun findDeviceTokenFamily(id: DeviceTokenFamilyId): StoreResult<DeviceTokenFamily?> =
        readEntity(COLLECTION_DEVICE_TOKEN_FAMILIES, id.value, DeviceTokenFamily.serializer())

    override suspend fun findDeviceAccessTokenBySelector(publicSelector: String): StoreResult<DeviceAccessToken?> =
        readUniqueEntity(UNIQUE_DEVICE_ACCESS_SELECTOR, publicSelector, DeviceAccessToken.serializer())

    override suspend fun findDeviceRefreshTokenBySelector(publicSelector: String): StoreResult<DeviceRefreshToken?> =
        readUniqueEntity(UNIQUE_DEVICE_REFRESH_SELECTOR, publicSelector, DeviceRefreshToken.serializer())

    override suspend fun listAuditEventsForOrganization(
        request: OrganizationAuditEventPageRequest
    ): StoreResult<OrganizationAuditEventPage> {
        requireInitialized() ?: return StoreResult.Failure(FirestoreFailureMapper.unavailable())
        return safeRead {
            val selected = transport.runQuery(parentName(), auditPageQuery(request))
                .map { it.decode(AuditEvent.serializer()) }
            val events = selected.take(request.limit)
            OrganizationAuditEventPage(
                organizationId = request.organizationId,
                events = events,
                nextCursor = events.lastOrNull()?.toOrganizationAuditCursor().takeIf {
                    selected.size > request.limit
                }
            )
        }
    }

    override suspend fun purgeAuditEvents(
        command: PurgeAuditEventsCommand
    ): StoreResult<PurgeAuditEventsCommit> = atomic {
        val selected = auditEventsBefore(command)
        selected.take(command.maximumEvents).forEach(::delete)
        PurgeAuditEventsCommit(
            deletedCount = minOf(selected.size, command.maximumEvents),
            hasMore = selected.size > command.maximumEvents
        )
    }

    override suspend fun createChallenge(command: CreateChallengeCommand): StoreResult<Challenge> = atomic {
        requireAbsent(COLLECTION_CHALLENGES, command.challenge.id.value)
        requireChallengeFederationLease(command.challenge, command.federationProviderLease)
        claimUnique(UNIQUE_CHALLENGE_DIGEST, digestUniqueValue(command.challenge.challengeDigest), COLLECTION_CHALLENGES, command.challenge.id.value)
        command.auditEvent?.let { requireAuditAvailable(it) }
        create(COLLECTION_CHALLENGES, command.challenge.id.value, command.challenge, Challenge.serializer(), challengeFields(command.challenge))
        command.auditEvent?.let { appendAudit(it) }
        command.challenge
    }

    override suspend fun consumeChallenge(command: ConsumeChallengeCommand): StoreResult<Challenge> = atomic {
        val existing = if (command.terminalState == ChallengeState.EXPIRED) {
            val candidate = requireEntity(COLLECTION_CHALLENGES, command.challengeId.value, Challenge.serializer())
            if (candidate.value.state != ChallengeState.PENDING || command.consumedAt < candidate.value.expiresAt) {
                abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            requireVersion(candidate.value.version, command.expectedVersion)
            candidate
        } else {
            requireChallenge(command.challengeId, command.expectedVersion, command.consumedAt)
        }
        requireChallengeFederationLease(existing.value, command.federationProviderLease)
        command.auditEvent?.let { requireAuditAvailable(it) }
        val replacement = existing.value.copy(
            state = command.terminalState,
            attemptCount = existing.value.attemptCount + if (command.terminalState == ChallengeState.FAILED) 1 else 0,
            version = existing.value.version + 1,
            consumedAt = command.consumedAt
        )
        update(existing, replacement, Challenge.serializer(), challengeFields(replacement))
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
        requireAbsent(COLLECTION_BOOTSTRAP, DOCUMENT_CURRENT)
        val identityCollections = listOf(
            COLLECTION_USERS,
            COLLECTION_CREDENTIALS,
            COLLECTION_SESSIONS,
            COLLECTION_ORGANIZATIONS,
            COLLECTION_MEMBERSHIPS,
            COLLECTION_INVITATIONS,
            COLLECTION_SERVICE_IDENTITIES,
            COLLECTION_SERVICE_CREDENTIALS,
            COLLECTION_EXTERNAL_IDENTITIES,
            COLLECTION_FEDERATION_PROVIDER_CONTROLS,
            COLLECTION_CHALLENGES,
            COLLECTION_RECOVERY_CODES,
            COLLECTION_DEVICE_GRANTS,
            COLLECTION_DEVICE_TOKEN_FAMILIES,
            COLLECTION_DEVICE_ACCESS_TOKENS,
            COLLECTION_DEVICE_REFRESH_TOKENS
        )
        if (identityCollections.any { hasAny(it) }) abort(IdentityStoreErrorCode.ALREADY_EXISTS)
        command.user.primaryEmail?.let {
            claimUnique(UNIQUE_EMAIL, normalizeEmail(it), COLLECTION_USERS, command.user.id.value)
        }
        claimUnique(
            UNIQUE_ORGANIZATION_SLUG,
            command.organization.slug,
            COLLECTION_ORGANIZATIONS,
            command.organization.id.value
        )
        claimUnique(
            UNIQUE_MEMBERSHIP,
            membershipUniqueValue(command.user.id, command.organization.id),
            COLLECTION_MEMBERSHIPS,
            command.ownerMembership.id.value
        )
        requireNewSession(command.enrollmentSession, command.user)
        requireAuditAvailable(command.auditEvent)
        create(
            COLLECTION_BOOTSTRAP,
            DOCUMENT_CURRENT,
            BootstrapReceipt(command.bootstrapSecretDigest, command.auditEvent.occurredAt),
            BootstrapReceipt.serializer()
        )
        create(
            COLLECTION_USERS,
            command.user.id.value,
            command.user,
            User.serializer(),
            userFields(command.user)
        )
        create(
            COLLECTION_ORGANIZATIONS,
            command.organization.id.value,
            command.organization,
            Organization.serializer(),
            organizationFields(command.organization)
        )
        create(
            COLLECTION_MEMBERSHIPS,
            command.ownerMembership.id.value,
            command.ownerMembership,
            Membership.serializer(),
            membershipFields(command.ownerMembership)
        )
        createSessionValue(command.enrollmentSession)
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
        challenge.value.userId?.let { if (it != command.credential.userId) abort(IdentityStoreErrorCode.INVALID_TRANSITION) }
        requireAbsent(COLLECTION_CREDENTIALS, command.credential.id.value)
        claimUnique(
            UNIQUE_WEBAUTHN_ID,
            command.credential.webAuthnId.encoded,
            COLLECTION_CREDENTIALS,
            command.credential.id.value
        )
        requireAuditAvailable(command.auditEvent)

        val replacementUser = command.user
        if (replacementUser == null) {
            requireEntity(COLLECTION_USERS, command.credential.userId.value, User.serializer())
        } else {
            val expectedVersion = command.expectedUserVersion!!
            if (expectedVersion == -1L) {
                requireAbsent(COLLECTION_USERS, replacementUser.id.value)
                if (replacementUser.version != 0L) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
                replacementUser.primaryEmail?.let {
                    claimUnique(UNIQUE_EMAIL, normalizeEmail(it), COLLECTION_USERS, replacementUser.id.value)
                }
                create(COLLECTION_USERS, replacementUser.id.value, replacementUser, User.serializer(), userFields(replacementUser))
            } else {
                val current = requireEntity(COLLECTION_USERS, replacementUser.id.value, User.serializer())
                requireVersion(current.value.version, expectedVersion)
                if (replacementUser.version != expectedVersion + 1) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
                replaceUserEmailClaim(current.value, replacementUser)
                update(current, replacementUser, User.serializer(), userFields(replacementUser))
            }
        }

        val consumed = consumeChallengeValue(challenge.value, completedAt)
        update(challenge, consumed, Challenge.serializer(), challengeFields(consumed))
        create(
            COLLECTION_CREDENTIALS,
            command.credential.id.value,
            command.credential,
            Credential.serializer(),
            credentialFields(command.credential)
        )
        appendAudit(command.auditEvent)
        CredentialRegistrationCommit(consumed, command.credential, replacementUser, command.auditEvent)
    }

    override suspend fun completeCredentialAuthentication(
        command: CompleteCredentialAuthenticationCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<CredentialAuthenticationCommit>> = atomicWebAuthn(
        command.challengeId,
        command.expectedChallengeVersion,
        command.authenticatedAt,
        command.rejectionAuditEvent
    ) {
        val challenge = requireChallenge(command.challengeId, command.expectedChallengeVersion, command.authenticatedAt)
        val credential = requireEntity(COLLECTION_CREDENTIALS, command.credentialId.value, Credential.serializer())
        requireVersion(credential.value.version, command.expectedCredentialVersion)
        if (credential.value.state != CredentialState.ACTIVE) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        if (credential.value.signCount != 0L && command.newSignCount != 0L && command.newSignCount <= credential.value.signCount) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        if (credential.value.backupEligible != command.backupEligible) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        challenge.value.userId?.let { if (it != credential.value.userId) abort(IdentityStoreErrorCode.INVALID_TRANSITION) }

        val user = requireEntity(COLLECTION_USERS, credential.value.userId.value, User.serializer())
        requireNewSession(command.session, user.value)
        requireAuditAvailable(command.auditEvent)
        val replaced = command.replacedSessionId?.let { id ->
            val current = requireActiveSession(
                id,
                command.expectedReplacedSessionVersion!!,
                command.authenticatedAt,
                validateFederationProvider = true
            )
            if (current.value.userId != credential.value.userId || command.session.rotatedFromId != current.value.id) {
                abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            current
        }
        val updatedCredential = credential.value.copy(
            signCount = command.newSignCount,
            backupEligible = command.backupEligible,
            backedUp = command.backedUp,
            version = credential.value.version + 1,
            updatedAt = command.authenticatedAt,
            lastUsedAt = command.authenticatedAt
        )
        val consumed = consumeChallengeValue(challenge.value, command.authenticatedAt)
        val rotated = replaced?.value?.let { rotateSessionValue(it, command.session.id) }
        update(challenge, consumed, Challenge.serializer(), challengeFields(consumed))
        update(credential, updatedCredential, Credential.serializer(), credentialFields(updatedCredential))
        replaced?.let { update(it, requireNotNull(rotated), IdentitySession.serializer(), sessionFields(rotated)) }
        createSessionValue(command.session)
        appendAudit(command.auditEvent)
        CredentialAuthenticationCommit(consumed, updatedCredential, command.session, rotated, command.auditEvent)
    }

    override suspend fun quarantineCredentialAuthentication(
        command: QuarantineCredentialAuthenticationCommand
    ): StoreResult<WebAuthnCeremonyAttemptCommit<CredentialQuarantineCommit>> = atomicWebAuthn(
        command.challengeId,
        command.expectedChallengeVersion,
        command.detectedAt,
        command.rejectionAuditEvent
    ) {
        val challenge = requireChallenge(command.challengeId, command.expectedChallengeVersion, command.detectedAt)
        if (challenge.value.purpose != ChallengePurpose.WEBAUTHN_AUTHENTICATION &&
            challenge.value.purpose != ChallengePurpose.STEP_UP
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        val credential = requireEntity(COLLECTION_CREDENTIALS, command.credentialId.value, Credential.serializer())
        requireVersion(credential.value.version, command.expectedCredentialVersion)
        if (credential.value.state != CredentialState.ACTIVE || credential.value.signCount == 0L ||
            command.observedSignCount > credential.value.signCount
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        if (credential.value.backupEligible != command.backupEligible) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        challenge.value.userId?.let { if (it != credential.value.userId) abort(IdentityStoreErrorCode.INVALID_TRANSITION) }
        requireAuditAvailable(command.auditEvent)

        val consumed = consumeChallengeValue(challenge.value, command.detectedAt)
        val quarantined = credential.value.copy(
            signCount = command.observedSignCount,
            backupEligible = command.backupEligible,
            backedUp = command.backedUp,
            state = CredentialState.SUSPECTED_CLONE,
            version = credential.value.version + 1,
            updatedAt = command.detectedAt,
            lastUsedAt = command.detectedAt,
            revocationReasonCode = "signature_counter_anomaly"
        )
        update(challenge, consumed, Challenge.serializer(), challengeFields(consumed))
        update(credential, quarantined, Credential.serializer(), credentialFields(quarantined))
        appendAudit(command.auditEvent)
        CredentialQuarantineCommit(consumed, quarantined, command.auditEvent)
    }

    override suspend fun mutateCredential(command: MutateCredentialCommand): StoreResult<Credential> = atomic {
        val existing = requireEntity(COLLECTION_CREDENTIALS, command.credentialId.value, Credential.serializer())
        requireVersion(existing.value.version, command.expectedVersion)
        val replacement = command.replacement
        if (replacement.webAuthnId != existing.value.webAuthnId || replacement.userId != existing.value.userId ||
            replacement.publicKey != existing.value.publicKey || replacement.signCount != existing.value.signCount ||
            replacement.backupEligible != existing.value.backupEligible || replacement.backedUp != existing.value.backedUp ||
            replacement.discoverable != existing.value.discoverable || replacement.createdAt != existing.value.createdAt ||
            replacement.lastUsedAt != existing.value.lastUsedAt || replacement.updatedAt < existing.value.updatedAt
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        when (command.auditEvent.action) {
            AuditAction.CREDENTIAL_RENAMED -> if (replacement.state != existing.value.state ||
                replacement.name == existing.value.name || replacement.revokedAt != existing.value.revokedAt ||
                replacement.revocationReasonCode != existing.value.revocationReasonCode
            ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            AuditAction.CREDENTIAL_REVOKED -> if (existing.value.state == CredentialState.REVOKED ||
                replacement.state != CredentialState.REVOKED || replacement.revokedAt == null ||
                replacement.revocationReasonCode.isNullOrBlank()
            ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            else -> abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireAuditAvailable(command.auditEvent)
        update(existing, replacement, Credential.serializer(), credentialFields(replacement))
        appendAudit(command.auditEvent)
        replacement
    }

    override suspend fun createSession(command: CreateSessionCommand): StoreResult<IdentitySession> = atomic {
        val user = requireEntity(COLLECTION_USERS, command.session.userId.value, User.serializer())
        requireNewSession(command.session, user.value)
        requireAuditAvailable(command.auditEvent)
        createSessionValue(command.session)
        appendAudit(command.auditEvent)
        command.session
    }

    override suspend fun touchIdentitySession(command: TouchIdentitySessionCommand): StoreResult<IdentitySession> = atomic {
        val current = requireActiveSession(
            command.sessionId,
            command.expectedVersion,
            command.lastUsedAt,
            validateFederationProvider = true
        )
        if (command.lastUsedAt < current.value.lastUsedAt ||
            command.idleExpiresAt < command.lastUsedAt ||
            command.idleExpiresAt > current.value.absoluteExpiresAt
        ) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        val renewed = current.value.copy(
            version = current.value.version + 1,
            lastUsedAt = command.lastUsedAt,
            idleExpiresAt = command.idleExpiresAt
        )
        update(current, renewed, IdentitySession.serializer(), sessionFields(renewed))
        renewed
    }

    override suspend fun rotateSession(command: RotateSessionCommand): StoreResult<SessionRotationCommit> = atomic {
        val previous = requireActiveSession(
            command.sessionId,
            command.expectedVersion,
            command.rotatedAt,
            validateFederationProvider = true
        )
        val user = requireEntity(COLLECTION_USERS, previous.value.userId.value, User.serializer())
        requireNewSession(command.replacement, user.value)
        if (command.replacement.userId != previous.value.userId ||
            command.replacement.familyId != previous.value.familyId ||
            command.replacement.rotationCounter != previous.value.rotationCounter + 1 ||
            command.replacement.createdAt != command.rotatedAt
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAuditAvailable(command.auditEvent)
        val rotated = rotateSessionValue(previous.value, command.replacement.id)
        update(previous, rotated, IdentitySession.serializer(), sessionFields(rotated))
        createSessionValue(command.replacement)
        appendAudit(command.auditEvent)
        SessionRotationCommit(rotated, command.replacement, command.auditEvent)
    }

    override suspend fun revokeSession(command: RevokeSessionCommand): StoreResult<IdentitySession> = atomic {
        val session = requireActiveSession(command.sessionId, command.expectedVersion, command.revokedAt)
        requireAuditAvailable(command.auditEvent)
        val revoked = revokeSessionValue(session.value, command.revokedAt, command.reasonCode)
        update(session, revoked, IdentitySession.serializer(), sessionFields(revoked))
        appendAudit(command.auditEvent)
        revoked
    }

    override suspend fun revokeUserSessions(command: RevokeUserSessionsCommand): StoreResult<RevokeUserSessionsCommit> = atomic {
        val user = requireEntity(COLLECTION_USERS, command.userId.value, User.serializer())
        requireVersion(user.value.version, command.expectedUserVersion)
        if (user.value.sessionEpoch != command.expectedSessionEpoch) abortVersion()
        val sessions = query(COLLECTION_SESSIONS, IdentitySession.serializer(), FIELD_USER_ID, command.userId.value)
        command.exceptSessionId?.let { exceptId ->
            val except = sessions.firstOrNull { it.value.id == exceptId }
                ?: abort(IdentityStoreErrorCode.SESSION_NOT_ACTIVE)
            if (except.value.state != SessionState.ACTIVE) abort(IdentityStoreErrorCode.SESSION_NOT_ACTIVE)
        }
        requireAuditAvailable(command.auditEvent)
        val revokedIds = mutableListOf<SessionId>()
        sessions.filter { it.value.state == SessionState.ACTIVE }.forEach { current ->
            val replacement = if (current.value.id == command.exceptSessionId) {
                current.value.copy(userSessionEpoch = command.newSessionEpoch, version = current.value.version + 1)
            } else {
                revokedIds += current.value.id
                revokeSessionValue(current.value, command.revokedAt, command.reasonCode)
            }
            update(current, replacement, IdentitySession.serializer(), sessionFields(replacement))
        }
        val updatedUser = user.value.copy(
            sessionEpoch = command.newSessionEpoch,
            version = user.value.version + 1,
            updatedAt = command.revokedAt
        )
        update(user, updatedUser, User.serializer(), userFields(updatedUser))
        appendAudit(command.auditEvent)
        RevokeUserSessionsCommit(updatedUser, revokedIds.sortedBy { it.value }, command.auditEvent)
    }

    override suspend fun acquireFederationProviderLease(
        command: AcquireFederationProviderLeaseCommand
    ): StoreResult<FederationProviderLease> = atomic {
        val organization = requireEntity(
            COLLECTION_ORGANIZATIONS,
            command.organizationId.value,
            Organization.serializer()
        )
        if (organization.value.state != OrganizationState.ACTIVE) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        val routeMatch = federationProviderByRoute(command.organizationId, command.providerId)
        val storageMatch = federationProviderByStorageKey(command.storageKey)
        val existing = routeMatch ?: storageMatch
        if (existing != null) {
            if (routeMatch == null || storageMatch == null) {
                if (existing.value.matches(command)) abort(IdentityStoreErrorCode.INTERNAL)
                abort(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
            }
            if (routeMatch.value != storageMatch.value || !existing.value.matches(command)) {
                abort(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
            }
            if (existing.value.state != FederationProviderState.ENABLED) {
                abort(IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED)
            }
            return@atomic existing.value.lease()
        }
        val created = FederationProviderControl(
            organizationId = command.organizationId,
            kind = command.kind,
            providerId = command.providerId,
            storageKey = command.storageKey,
            createdAt = command.acquiredAt,
            updatedAt = command.acquiredAt
        )
        claimFederationProviderUniqueness(created)
        createFederationProviderControl(created)
        created.lease()
    }

    override suspend fun validateFederationProviderLease(
        lease: FederationProviderLease
    ): StoreResult<FederationProviderLease> = atomic {
        requireFederationProviderLease(lease)
        lease
    }

    override suspend fun compareAndSetFederationProviderState(
        command: CompareAndSetFederationProviderStateCommand
    ): StoreResult<FederationProviderStateCommit> = atomic {
        val replacement = command.replacement
        val organization = requireEntity(
            COLLECTION_ORGANIZATIONS,
            replacement.organizationId.value,
            Organization.serializer()
        )
        if (organization.value.state != OrganizationState.ACTIVE) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        val routeMatch = federationProviderByRoute(replacement.organizationId, replacement.providerId)
        val storageMatch = federationProviderByStorageKey(replacement.storageKey)
        if (command.expectedVersion == null) {
            if (routeMatch != null) {
                if (!routeMatch.value.hasSameIdentity(replacement)) {
                    abort(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
                }
                abortVersion()
            }
            if (storageMatch != null) {
                abort(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
            }
            if (replacement.state != FederationProviderState.DISABLED ||
                replacement.version != 0L || replacement.sessionEpoch != 1L ||
                replacement.createdAt != replacement.updatedAt
            ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            requireAuditAvailable(command.auditEvent)
            claimFederationProviderUniqueness(replacement)
            createFederationProviderControl(replacement)
        } else {
            val expectedVersion = command.expectedVersion
                ?: abort(IdentityStoreErrorCode.VERSION_CONFLICT)
            val existing = routeMatch ?: abortVersion()
            requireVersion(existing.value.version, expectedVersion)
            if (!existing.value.hasSameIdentity(replacement)) {
                abort(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
            }
            if (existing.value.createdAt != replacement.createdAt) {
                abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            if (storageMatch == null || storageMatch.value != existing.value) {
                abort(IdentityStoreErrorCode.INTERNAL)
            }
            if (replacement.updatedAt < existing.value.updatedAt ||
                existing.value.state == replacement.state
            ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            when (replacement.state) {
                FederationProviderState.DISABLED -> if (
                    existing.value.state != FederationProviderState.ENABLED ||
                    replacement.sessionEpoch != existing.value.sessionEpoch + 1
                ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
                FederationProviderState.ENABLED -> if (
                    existing.value.state != FederationProviderState.DISABLED ||
                    replacement.sessionEpoch != existing.value.sessionEpoch
                ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            requireAuditAvailable(command.auditEvent)
            update(
                existing,
                replacement,
                FederationProviderControl.serializer(),
                federationProviderControlFields(replacement)
            )
        }
        appendAudit(command.auditEvent)
        FederationProviderStateCommit(replacement, command.auditEvent)
    }

    override suspend fun replaceRecoveryCodes(
        command: ReplaceRecoveryCodesCommand
    ): StoreResult<RecoveryCodeReplacementCommit> = atomic {
        requireEntity(COLLECTION_USERS, command.userId.value, User.serializer())
        val existing = query(COLLECTION_RECOVERY_CODES, RecoveryCode.serializer(), FIELD_USER_ID, command.userId.value)
        val currentGeneration = existing.maxOfOrNull { it.value.generation }
        if (currentGeneration != command.expectedGeneration) abortVersion()
        if (command.codes.any { it.version != 0L }) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        command.codes.forEach { code ->
            requireAbsent(COLLECTION_RECOVERY_CODES, code.id.value)
            claimUnique(UNIQUE_RECOVERY_SELECTOR, code.publicSelector, COLLECTION_RECOVERY_CODES, code.id.value)
            claimUnique(
                UNIQUE_RECOVERY_DIGEST,
                digestUniqueValue(code.secretDigest),
                COLLECTION_RECOVERY_CODES,
                code.id.value
            )
        }
        requireAuditAvailable(command.auditEvent)
        existing.filter { it.value.state == RecoveryCodeState.ACTIVE }.forEach { current ->
            val revoked = current.value.copy(state = RecoveryCodeState.REVOKED, version = current.value.version + 1)
            update(current, revoked, RecoveryCode.serializer(), recoveryFields(revoked))
        }
        command.codes.forEach { code ->
            create(COLLECTION_RECOVERY_CODES, code.id.value, code, RecoveryCode.serializer(), recoveryFields(code))
        }
        appendAudit(command.auditEvent)
        RecoveryCodeReplacementCommit(command.newGeneration, command.codes, command.auditEvent)
    }

    override suspend fun consumeRecoveryCode(
        command: ConsumeRecoveryCodeCommand
    ): StoreResult<RecoveryCodeConsumptionCommit> = atomic {
        val code = requireEntity(COLLECTION_RECOVERY_CODES, command.recoveryCodeId.value, RecoveryCode.serializer())
        val codeExpiresAt = code.value.expiresAt
        if (code.value.state != RecoveryCodeState.ACTIVE ||
            (codeExpiresAt != null && command.consumedAt >= codeExpiresAt)
        ) abort(IdentityStoreErrorCode.RECOVERY_CODE_NOT_ACTIVE)
        requireVersion(code.value.version, command.expectedVersion)
        val user = requireEntity(COLLECTION_USERS, code.value.userId.value, User.serializer())
        requireNewSession(command.recoverySession, user.value)
        if (command.recoverySession.userId != code.value.userId || command.recoverySession.createdAt != command.consumedAt) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireAuditAvailable(command.auditEvent)
        val consumed = code.value.copy(
            state = RecoveryCodeState.CONSUMED,
            version = code.value.version + 1,
            consumedAt = command.consumedAt
        )
        update(code, consumed, RecoveryCode.serializer(), recoveryFields(consumed))
        createSessionValue(command.recoverySession)
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
        val userId = challenge.value.userId
        val auditTarget = command.auditEvent.target
        if (challenge.value.purpose != ChallengePurpose.ACCOUNT_RECOVERY || userId == null ||
            challenge.value.activatedAt != null || auditTarget?.type != AuditTargetType.USER ||
            auditTarget.id != userId.value
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAuditAvailable(command.auditEvent)
        val activated = challenge.value.copy(
            activatedAt = command.activatedAt,
            version = challenge.value.version + 1
        )
        update(challenge, activated, Challenge.serializer(), challengeFields(activated))
        appendAudit(command.auditEvent)
        AdministrativeRecoveryTicketActivationCommit(activated, command.auditEvent)
    }

    override suspend fun redeemAdministrativeRecoveryTicket(
        command: RedeemAdministrativeRecoveryTicketCommand
    ): StoreResult<AdministrativeRecoveryTicketRedemptionCommit> = atomic {
        val challenge = requireChallenge(command.challengeId, command.expectedChallengeVersion, command.redeemedAt)
        val activatedAt = challenge.value.activatedAt
        if (challenge.value.purpose != ChallengePurpose.ACCOUNT_RECOVERY || challenge.value.userId == null ||
            activatedAt == null || activatedAt > command.redeemedAt ||
            command.recoverySession.userId != challenge.value.userId
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        val ticketUserId = requireNotNull(challenge.value.userId)
        val user = requireEntity(COLLECTION_USERS, ticketUserId.value, User.serializer())
        requireNewSession(command.recoverySession, user.value)
        if (command.recoverySession.familyId != command.recoverySession.id ||
            command.recoverySession.rotatedFromId != null || command.recoverySession.rotationCounter != 0L
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAuditAvailable(command.auditEvent)
        val consumed = consumeChallengeValue(challenge.value, command.redeemedAt)
        update(challenge, consumed, Challenge.serializer(), challengeFields(consumed))
        createSessionValue(command.recoverySession)
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
        if (challenge.value.purpose != ChallengePurpose.WEBAUTHN_REGISTRATION ||
            challenge.value.userId != command.credential.userId
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        val recoverySession = requireActiveSession(
            command.recoverySessionId,
            command.expectedRecoverySessionVersion,
            command.completedAt
        )
        if (recoverySession.value.userId != command.credential.userId ||
            recoverySession.value.assurance != AuthenticationAssurance.RECOVERY
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        val user = requireEntity(COLLECTION_USERS, command.credential.userId.value, User.serializer())
        requireVersion(user.value.version, command.expectedUserVersion)
        if (user.value.sessionEpoch != command.expectedSessionEpoch ||
            recoverySession.value.userSessionEpoch != user.value.sessionEpoch
        ) abortVersion()
        val existingCodes = query(
            COLLECTION_RECOVERY_CODES,
            RecoveryCode.serializer(),
            FIELD_USER_ID,
            user.value.id.value
        )
        val currentGeneration = existingCodes.maxOfOrNull { it.value.generation }
        if (currentGeneration != command.expectedRecoveryGeneration) abortVersion()
        requireAbsent(COLLECTION_CREDENTIALS, command.credential.id.value)
        claimUnique(
            UNIQUE_WEBAUTHN_ID,
            command.credential.webAuthnId.encoded,
            COLLECTION_CREDENTIALS,
            command.credential.id.value
        )
        command.replacementRecoveryCodes.forEach { code ->
            requireAbsent(COLLECTION_RECOVERY_CODES, code.id.value)
            claimUnique(UNIQUE_RECOVERY_SELECTOR, code.publicSelector, COLLECTION_RECOVERY_CODES, code.id.value)
            claimUnique(
                UNIQUE_RECOVERY_DIGEST,
                digestUniqueValue(code.secretDigest),
                COLLECTION_RECOVERY_CODES,
                code.id.value
            )
        }
        val userSessions = query(COLLECTION_SESSIONS, IdentitySession.serializer(), FIELD_USER_ID, user.value.id.value)
        requireAuditAvailable(command.auditEvent)

        val consumedChallenge = consumeChallengeValue(challenge.value, command.completedAt)
        update(challenge, consumedChallenge, Challenge.serializer(), challengeFields(consumedChallenge))
        create(
            COLLECTION_CREDENTIALS,
            command.credential.id.value,
            command.credential,
            Credential.serializer(),
            credentialFields(command.credential)
        )
        val updatedUser = user.value.copy(
            sessionEpoch = command.newSessionEpoch,
            version = user.value.version + 1,
            updatedAt = command.completedAt
        )
        update(user, updatedUser, User.serializer(), userFields(updatedUser))
        val revokedSessionIds = userSessions.filter { it.value.state == SessionState.ACTIVE }.map { session ->
            val revoked = revokeSessionValue(session.value, command.completedAt, "recovery_enrollment_completed")
            update(session, revoked, IdentitySession.serializer(), sessionFields(revoked))
            session.value.id
        }.sortedBy { it.value }
        existingCodes.filter { it.value.state == RecoveryCodeState.ACTIVE }.forEach { code ->
            val revoked = code.value.copy(state = RecoveryCodeState.REVOKED, version = code.value.version + 1)
            update(code, revoked, RecoveryCode.serializer(), recoveryFields(revoked))
        }
        command.replacementRecoveryCodes.forEach { code ->
            create(COLLECTION_RECOVERY_CODES, code.id.value, code, RecoveryCode.serializer(), recoveryFields(code))
        }
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
        requireAbsent(COLLECTION_ORGANIZATIONS, command.organization.id.value)
        requireAbsent(COLLECTION_MEMBERSHIPS, command.ownerMembership.id.value)
        requireEntity(COLLECTION_USERS, command.ownerMembership.userId.value, User.serializer())
        claimUnique(
            UNIQUE_ORGANIZATION_SLUG,
            command.organization.slug,
            COLLECTION_ORGANIZATIONS,
            command.organization.id.value
        )
        claimUnique(
            UNIQUE_MEMBERSHIP,
            membershipUniqueValue(command.ownerMembership.userId, command.organization.id),
            COLLECTION_MEMBERSHIPS,
            command.ownerMembership.id.value
        )
        requireAuditAvailable(command.auditEvent)
        create(
            COLLECTION_ORGANIZATIONS,
            command.organization.id.value,
            command.organization,
            Organization.serializer(),
            organizationFields(command.organization)
        )
        create(
            COLLECTION_MEMBERSHIPS,
            command.ownerMembership.id.value,
            command.ownerMembership,
            Membership.serializer(),
            membershipFields(command.ownerMembership)
        )
        appendAudit(command.auditEvent)
        OrganizationCreationCommit(command.organization, command.ownerMembership, command.auditEvent)
    }

    override suspend fun mutateOrganization(command: MutateOrganizationCommand): StoreResult<Organization> = atomic {
        val existing = requireEntity(
            COLLECTION_ORGANIZATIONS,
            command.organizationId.value,
            Organization.serializer()
        )
        requireVersion(existing.value.version, command.expectedVersion)
        if (existing.value.state == OrganizationState.DELETED || existing.value.slug != command.replacement.slug) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireAuditAvailable(command.auditEvent)
        update(
            existing,
            command.replacement,
            Organization.serializer(),
            organizationFields(command.replacement)
        )
        appendAudit(command.auditEvent)
        command.replacement
    }

    override suspend fun createInvitation(command: CreateInvitationCommand): StoreResult<Invitation> = atomic {
        requireAbsent(COLLECTION_INVITATIONS, command.invitation.id.value)
        val organization = requireEntity(
            COLLECTION_ORGANIZATIONS,
            command.invitation.organizationId.value,
            Organization.serializer()
        )
        if (organization.value.state != OrganizationState.ACTIVE) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        command.invitation.invitedByUserId?.let { requireEntity(COLLECTION_USERS, it.value, User.serializer()) }
        claimUnique(
            UNIQUE_INVITATION_DIGEST,
            digestUniqueValue(command.invitation.tokenDigest),
            COLLECTION_INVITATIONS,
            command.invitation.id.value
        )
        claimUnique(
            UNIQUE_PENDING_INVITATION,
            pendingInvitationUniqueValue(command.invitation.organizationId, command.invitation.email),
            COLLECTION_INVITATIONS,
            command.invitation.id.value
        )
        requireAuditAvailable(command.auditEvent)
        create(
            COLLECTION_INVITATIONS,
            command.invitation.id.value,
            command.invitation,
            Invitation.serializer(),
            invitationFields(command.invitation)
        )
        appendAudit(command.auditEvent)
        command.invitation
    }

    override suspend fun mutateInvitation(command: MutateInvitationCommand): StoreResult<Invitation> = atomic {
        val existing = requireEntity(COLLECTION_INVITATIONS, command.invitationId.value, Invitation.serializer())
        requireVersion(existing.value.version, command.expectedVersion)
        if (existing.value.state != InvitationState.PENDING ||
            existing.value.organizationId != command.replacement.organizationId ||
            existing.value.email != command.replacement.email || existing.value.role != command.replacement.role ||
            existing.value.tokenDigest != command.replacement.tokenDigest ||
            existing.value.createdAt != command.replacement.createdAt ||
            existing.value.expiresAt != command.replacement.expiresAt
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAuditAvailable(command.auditEvent)
        releaseUnique(
            UNIQUE_PENDING_INVITATION,
            pendingInvitationUniqueValue(existing.value.organizationId, existing.value.email),
            COLLECTION_INVITATIONS,
            existing.value.id.value
        )
        update(existing, command.replacement, Invitation.serializer(), invitationFields(command.replacement))
        appendAudit(command.auditEvent)
        command.replacement
    }

    override suspend fun enrollInvitation(
        command: EnrollInvitationCommand
    ): StoreResult<InvitationEnrollmentCommit> = atomic {
        val invitation = requireEntity(
            COLLECTION_INVITATIONS,
            command.invitationId.value,
            Invitation.serializer()
        )
        requireVersion(invitation.value.version, command.expectedInvitationVersion)
        val organization = requireEntity(
            COLLECTION_ORGANIZATIONS,
            invitation.value.organizationId.value,
            Organization.serializer()
        )
        if (invitation.value.tokenDigest != command.expectedTokenDigest ||
            invitation.value.state != InvitationState.PENDING ||
            invitation.value.expiresAt <= command.enrolledAt ||
            organization.value.state != OrganizationState.ACTIVE ||
            normalizeEmail(invitation.value.email) != normalizeEmail(requireNotNull(command.user.primaryEmail)) ||
            command.membership.organizationId != invitation.value.organizationId ||
            command.membership.role != invitation.value.role ||
            command.auditEvent.organizationId != invitation.value.organizationId
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)

        requireAbsent(COLLECTION_USERS, command.user.id.value)
        claimUnique(
            UNIQUE_EMAIL,
            normalizeEmail(requireNotNull(command.user.primaryEmail)),
            COLLECTION_USERS,
            command.user.id.value
        )
        requireAbsent(COLLECTION_MEMBERSHIPS, command.membership.id.value)
        claimUnique(
            UNIQUE_MEMBERSHIP,
            membershipUniqueValue(command.user.id, invitation.value.organizationId),
            COLLECTION_MEMBERSHIPS,
            command.membership.id.value
        )
        requireNewSession(command.enrollmentSession, command.user)
        requireAuditAvailable(command.auditEvent)

        val accepted = invitation.value.copy(
            state = InvitationState.ACCEPTED,
            version = invitation.value.version + 1,
            acceptedAt = command.enrolledAt,
            acceptedByUserId = command.user.id
        )
        releaseUnique(
            UNIQUE_PENDING_INVITATION,
            pendingInvitationUniqueValue(invitation.value.organizationId, invitation.value.email),
            COLLECTION_INVITATIONS,
            invitation.value.id.value
        )
        create(
            COLLECTION_USERS,
            command.user.id.value,
            command.user,
            User.serializer(),
            userFields(command.user)
        )
        create(
            COLLECTION_MEMBERSHIPS,
            command.membership.id.value,
            command.membership,
            Membership.serializer(),
            membershipFields(command.membership)
        )
        createSessionValue(command.enrollmentSession)
        update(invitation, accepted, Invitation.serializer(), invitationFields(accepted))
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
        requireAbsent(COLLECTION_MEMBERSHIPS, membership.id.value)
        val user = requireEntity(COLLECTION_USERS, membership.userId.value, User.serializer())
        requireEntity(COLLECTION_ORGANIZATIONS, membership.organizationId.value, Organization.serializer())
        claimUnique(
            UNIQUE_MEMBERSHIP,
            membershipUniqueValue(membership.userId, membership.organizationId),
            COLLECTION_MEMBERSHIPS,
            membership.id.value
        )
        requireAuditAvailable(command.auditEvent)
        val invitation = command.invitationId?.let { id ->
            val current = requireEntity(COLLECTION_INVITATIONS, id.value, Invitation.serializer())
            requireVersion(current.value.version, command.expectedInvitationVersion!!)
            if (current.value.state != InvitationState.PENDING ||
                current.value.expiresAt <= command.auditEvent.occurredAt ||
                current.value.organizationId != membership.organizationId
            ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            user.value.primaryEmail?.let {
                if (normalizeEmail(it) != normalizeEmail(current.value.email)) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            current to current.value.copy(
                state = InvitationState.ACCEPTED,
                version = current.value.version + 1,
                acceptedAt = command.auditEvent.occurredAt,
                acceptedByUserId = membership.userId
            )
        }
        create(COLLECTION_MEMBERSHIPS, membership.id.value, membership, Membership.serializer(), membershipFields(membership))
        invitation?.let { (current, replacement) ->
            releaseUnique(
                UNIQUE_PENDING_INVITATION,
                pendingInvitationUniqueValue(current.value.organizationId, current.value.email),
                COLLECTION_INVITATIONS,
                current.value.id.value
            )
            update(current, replacement, Invitation.serializer(), invitationFields(replacement))
        }
        appendAudit(command.auditEvent)
        membership
    }

    override suspend fun mutateMembership(command: MutateMembershipCommand): StoreResult<Membership> = atomic {
        val existing = requireEntity(COLLECTION_MEMBERSHIPS, command.membershipId.value, Membership.serializer())
        requireVersion(existing.value.version, command.expectedVersion)
        if (existing.value.organizationId != command.replacement.organizationId ||
            existing.value.userId != command.replacement.userId
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        val removesOwner = existing.value.state == MembershipState.ACTIVE &&
            existing.value.role == OrganizationRole.OWNER &&
            (command.replacement.state != MembershipState.ACTIVE || command.replacement.role != OrganizationRole.OWNER)
        if (removesOwner) {
            val memberships = query(
                COLLECTION_MEMBERSHIPS,
                Membership.serializer(),
                FIELD_ORGANIZATION_ID,
                existing.value.organizationId.value
            )
            val anotherOwner = memberships.any {
                it.value.id != existing.value.id && it.value.state == MembershipState.ACTIVE &&
                    it.value.role == OrganizationRole.OWNER
            }
            if (!anotherOwner) abort(IdentityStoreErrorCode.LAST_OWNER)
        }
        requireAuditAvailable(command.auditEvent)
        command.expectedUserVersion?.let { expectedUserVersion ->
            val user = requireEntity(COLLECTION_USERS, existing.value.userId.value, User.serializer())
            requireVersion(user.value.version, expectedUserVersion)
            if (user.value.sessionEpoch != command.expectedSessionEpoch) abortVersion()
            val replacementUser = user.value.copy(
                sessionEpoch = requireNotNull(command.newSessionEpoch),
                version = user.value.version + 1,
                updatedAt = requireNotNull(command.sessionsRevokedAt)
            )
            update(user, replacementUser, User.serializer(), userFields(replacementUser))
        }
        update(existing, command.replacement, Membership.serializer(), membershipFields(command.replacement))
        appendAudit(command.auditEvent)
        command.replacement
    }

    override suspend fun createServiceIdentity(
        command: CreateServiceIdentityCommand
    ): StoreResult<ServiceIdentityCreationCommit> = atomic {
        requireAbsent(COLLECTION_SERVICE_IDENTITIES, command.identity.id.value)
        requireAbsent(COLLECTION_SERVICE_CREDENTIALS, command.initialCredential.id.value)
        val organization = requireEntity(
            COLLECTION_ORGANIZATIONS,
            command.identity.organizationId.value,
            Organization.serializer()
        )
        if (organization.value.state != OrganizationState.ACTIVE ||
            command.initialCredential.expiresAt?.let { it > command.identity.createdAt } != true
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        claimUnique(
            UNIQUE_SERVICE_PREFIX,
            command.initialCredential.publicPrefix,
            COLLECTION_SERVICE_CREDENTIALS,
            command.initialCredential.id.value
        )
        claimUnique(
            UNIQUE_SERVICE_DIGEST,
            digestUniqueValue(command.initialCredential.secretDigest),
            COLLECTION_SERVICE_CREDENTIALS,
            command.initialCredential.id.value
        )
        requireAuditAvailable(command.auditEvent)
        create(
            COLLECTION_SERVICE_IDENTITIES,
            command.identity.id.value,
            command.identity,
            ServiceIdentity.serializer(),
            serviceIdentityFields(command.identity)
        )
        create(
            COLLECTION_SERVICE_CREDENTIALS,
            command.initialCredential.id.value,
            command.initialCredential,
            ServiceCredential.serializer(),
            serviceCredentialFields(command.initialCredential)
        )
        appendAudit(command.auditEvent)
        ServiceIdentityCreationCommit(command.identity, command.initialCredential, command.auditEvent)
    }

    override suspend fun mutateServiceIdentity(
        command: MutateServiceIdentityCommand
    ): StoreResult<ServiceIdentity> = atomic {
        val existing = requireEntity(
            COLLECTION_SERVICE_IDENTITIES,
            command.serviceIdentityId.value,
            ServiceIdentity.serializer()
        )
        requireVersion(existing.value.version, command.expectedVersion)
        if (existing.value.state == ServiceIdentityState.REVOKED ||
            existing.value.organizationId != command.replacement.organizationId ||
            existing.value.createdAt != command.replacement.createdAt
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAuditAvailable(command.auditEvent)
        update(
            existing,
            command.replacement,
            ServiceIdentity.serializer(),
            serviceIdentityFields(command.replacement)
        )
        if (command.replacement.state == ServiceIdentityState.REVOKED) {
            query(
                COLLECTION_SERVICE_CREDENTIALS,
                ServiceCredential.serializer(),
                FIELD_SERVICE_IDENTITY_ID,
                existing.value.id.value
            ).filter {
                it.value.state == ServiceCredentialState.ACTIVE || it.value.state == ServiceCredentialState.ROTATED
            }.forEach { credential ->
                val revoked = credential.value.copy(
                    state = ServiceCredentialState.REVOKED,
                    version = credential.value.version + 1,
                    revokedAt = command.changedAt
                )
                update(credential, revoked, ServiceCredential.serializer(), serviceCredentialFields(revoked))
            }
        }
        appendAudit(command.auditEvent)
        command.replacement
    }

    override suspend fun createServiceCredential(
        command: CreateServiceCredentialCommand
    ): StoreResult<ServiceCredential> = atomic {
        val identity = requireEntity(
            COLLECTION_SERVICE_IDENTITIES,
            command.credential.serviceIdentityId.value,
            ServiceIdentity.serializer()
        )
        if (identity.value.state != ServiceIdentityState.ACTIVE ||
            !identity.value.capabilities.containsAll(command.credential.capabilities)
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAbsent(COLLECTION_SERVICE_CREDENTIALS, command.credential.id.value)
        claimUnique(
            UNIQUE_SERVICE_PREFIX,
            command.credential.publicPrefix,
            COLLECTION_SERVICE_CREDENTIALS,
            command.credential.id.value
        )
        claimUnique(
            UNIQUE_SERVICE_DIGEST,
            digestUniqueValue(command.credential.secretDigest),
            COLLECTION_SERVICE_CREDENTIALS,
            command.credential.id.value
        )
        requireAuditAvailable(command.auditEvent)
        create(
            COLLECTION_SERVICE_CREDENTIALS,
            command.credential.id.value,
            command.credential,
            ServiceCredential.serializer(),
            serviceCredentialFields(command.credential)
        )
        appendAudit(command.auditEvent)
        command.credential
    }

    override suspend fun revokeServiceCredential(
        command: RevokeServiceCredentialCommand
    ): StoreResult<ServiceCredential> = atomic {
        val existing = requireEntity(
            COLLECTION_SERVICE_CREDENTIALS,
            command.credentialId.value,
            ServiceCredential.serializer()
        )
        requireVersion(existing.value.version, command.expectedVersion)
        if (existing.value.state != ServiceCredentialState.ACTIVE &&
            existing.value.state != ServiceCredentialState.ROTATED
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAuditAvailable(command.auditEvent)
        val revoked = existing.value.copy(
            state = ServiceCredentialState.REVOKED,
            version = existing.value.version + 1,
            revokedAt = command.revokedAt
        )
        update(existing, revoked, ServiceCredential.serializer(), serviceCredentialFields(revoked))
        appendAudit(command.auditEvent)
        revoked
    }

    override suspend fun compareAndSetDeviceGrant(
        command: CompareAndSetDeviceGrantCommand
    ): StoreResult<DeviceGrant> = atomic {
        val replacement = command.replacement
        val existing = get(COLLECTION_DEVICE_GRANTS, replacement.id.value, DeviceGrant.serializer())
        val expectedVersion = command.expectedVersion
        if (expectedVersion == null) {
            if (existing != null) abortVersion()
            claimUnique(UNIQUE_DEVICE_CODE, digestUniqueValue(replacement.deviceCodeDigest), COLLECTION_DEVICE_GRANTS, replacement.id.value)
            claimUnique(UNIQUE_USER_CODE, digestUniqueValue(replacement.userCodeDigest), COLLECTION_DEVICE_GRANTS, replacement.id.value)
            create(COLLECTION_DEVICE_GRANTS, replacement.id.value, replacement, DeviceGrant.serializer(), deviceGrantFields(replacement))
        } else {
            existing ?: abort(IdentityStoreErrorCode.NOT_FOUND)
            requireVersion(existing.value.version, expectedVersion)
            requireDeviceGrantTransition(existing.value, replacement)
            update(existing, replacement, DeviceGrant.serializer(), deviceGrantFields(replacement))
        }
        requireAuditAvailable(command.auditEvent)
        appendAudit(command.auditEvent)
        replacement
    }

    override suspend fun exchangeDeviceGrant(
        command: ExchangeDeviceGrantCommand
    ): StoreResult<DeviceTokenIssuanceCommit> = atomic {
        val grant = requireEntity(COLLECTION_DEVICE_GRANTS, command.deviceGrantId.value, DeviceGrant.serializer())
        requireVersion(grant.value.version, command.expectedDeviceGrantVersion)
        val membership = requireEntity(
            COLLECTION_MEMBERSHIPS,
            command.family.membershipId.value,
            Membership.serializer()
        )
        val organization = requireEntity(
            COLLECTION_ORGANIZATIONS,
            command.family.organizationId.value,
            Organization.serializer()
        )
        val user = requireEntity(COLLECTION_USERS, command.family.userId.value, User.serializer())
        if (grant.value.state != DeviceGrantState.AUTHORIZED || command.exchangedAt >= grant.value.expiresAt ||
            command.family.clientId != grant.value.clientId ||
            command.family.userId != grant.value.userId ||
            command.family.organizationId != grant.value.organizationId ||
            command.family.membershipId != grant.value.membershipId ||
            command.family.membershipVersion != grant.value.membershipVersion ||
            membership.value.userId != command.family.userId ||
            membership.value.organizationId != command.family.organizationId ||
            membership.value.state != MembershipState.ACTIVE ||
            membership.value.version != command.family.membershipVersion ||
            organization.value.state != OrganizationState.ACTIVE ||
            user.value.state != UserState.ACTIVE ||
            command.auditEvent.organizationId != command.family.organizationId ||
            command.auditEvent.target != AuditTarget(AuditTargetType.DEVICE_GRANT, grant.value.id.value) ||
            command.family.capabilities != grant.value.approvedCapabilities ||
            command.family.createdAt != command.exchangedAt ||
            command.accessToken.createdAt != command.exchangedAt ||
            command.refreshToken.createdAt != command.exchangedAt ||
            command.accessToken.expiresAt > command.family.expiresAt ||
            command.refreshToken.expiresAt > command.family.expiresAt
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAbsent(COLLECTION_DEVICE_TOKEN_FAMILIES, command.family.id.value)
        requireAbsent(COLLECTION_DEVICE_ACCESS_TOKENS, command.accessToken.id.value)
        requireAbsent(COLLECTION_DEVICE_REFRESH_TOKENS, command.refreshToken.id.value)
        claimDeviceToken(UNIQUE_DEVICE_ACCESS_SELECTOR, command.accessToken.publicSelector, command.accessToken.secretDigest,
            COLLECTION_DEVICE_ACCESS_TOKENS, command.accessToken.id.value)
        claimDeviceToken(UNIQUE_DEVICE_REFRESH_SELECTOR, command.refreshToken.publicSelector, command.refreshToken.secretDigest,
            COLLECTION_DEVICE_REFRESH_TOKENS, command.refreshToken.id.value)
        requireAuditAvailable(command.auditEvent)
        val consumed = grant.value.copy(
            state = DeviceGrantState.CONSUMED,
            version = grant.value.version + 1,
            consumedAt = command.exchangedAt
        )
        update(grant, consumed, DeviceGrant.serializer(), deviceGrantFields(consumed))
        create(COLLECTION_DEVICE_TOKEN_FAMILIES, command.family.id.value, command.family,
            DeviceTokenFamily.serializer(), deviceTokenFamilyFields(command.family))
        create(COLLECTION_DEVICE_ACCESS_TOKENS, command.accessToken.id.value, command.accessToken,
            DeviceAccessToken.serializer(), deviceAccessTokenFields(command.accessToken))
        create(COLLECTION_DEVICE_REFRESH_TOKENS, command.refreshToken.id.value, command.refreshToken,
            DeviceRefreshToken.serializer(), deviceRefreshTokenFields(command.refreshToken))
        appendAudit(command.auditEvent)
        DeviceTokenIssuanceCommit(
            consumed, command.family, command.accessToken, command.refreshToken, command.auditEvent
        )
    }

    override suspend fun rotateDeviceRefreshToken(
        command: RotateDeviceRefreshTokenCommand
    ): StoreResult<DeviceTokenRotationCommit> = atomic {
        val previous = requireEntity(
            COLLECTION_DEVICE_REFRESH_TOKENS,
            command.refreshTokenId.value,
            DeviceRefreshToken.serializer()
        )
        requireVersion(previous.value.version, command.expectedRefreshTokenVersion)
        val family = requireEntity(
            COLLECTION_DEVICE_TOKEN_FAMILIES,
            previous.value.familyId.value,
            DeviceTokenFamily.serializer()
        )
        requireVersion(family.value.version, command.expectedFamilyVersion)
        val membership = requireEntity(
            COLLECTION_MEMBERSHIPS,
            family.value.membershipId.value,
            Membership.serializer()
        )
        val organization = requireEntity(
            COLLECTION_ORGANIZATIONS,
            family.value.organizationId.value,
            Organization.serializer()
        )
        val user = requireEntity(COLLECTION_USERS, family.value.userId.value, User.serializer())
        if (previous.value.state != DeviceRefreshTokenState.ACTIVE || command.rotatedAt >= previous.value.expiresAt ||
            family.value.state != DeviceTokenFamilyState.ACTIVE || command.rotatedAt >= family.value.expiresAt ||
            membership.value.userId != family.value.userId ||
            membership.value.organizationId != family.value.organizationId ||
            membership.value.state != MembershipState.ACTIVE ||
            membership.value.version != family.value.membershipVersion ||
            organization.value.state != OrganizationState.ACTIVE ||
            user.value.state != UserState.ACTIVE ||
            command.auditEvent.organizationId != family.value.organizationId ||
            command.auditEvent.target != AuditTarget(
                AuditTargetType.DEVICE_GRANT,
                family.value.deviceGrantId.value
            ) ||
            command.replacementAccessToken.familyId != family.value.id ||
            command.replacementRefreshToken.familyId != family.value.id ||
            command.replacementRefreshToken.rotationCounter != previous.value.rotationCounter + 1 ||
            command.replacementAccessToken.createdAt != command.rotatedAt ||
            command.replacementRefreshToken.createdAt != command.rotatedAt ||
            command.replacementAccessToken.expiresAt > family.value.expiresAt ||
            command.replacementRefreshToken.expiresAt > family.value.expiresAt
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAbsent(COLLECTION_DEVICE_ACCESS_TOKENS, command.replacementAccessToken.id.value)
        requireAbsent(COLLECTION_DEVICE_REFRESH_TOKENS, command.replacementRefreshToken.id.value)
        claimDeviceToken(UNIQUE_DEVICE_ACCESS_SELECTOR, command.replacementAccessToken.publicSelector,
            command.replacementAccessToken.secretDigest,
            COLLECTION_DEVICE_ACCESS_TOKENS, command.replacementAccessToken.id.value)
        claimDeviceToken(UNIQUE_DEVICE_REFRESH_SELECTOR, command.replacementRefreshToken.publicSelector,
            command.replacementRefreshToken.secretDigest,
            COLLECTION_DEVICE_REFRESH_TOKENS, command.replacementRefreshToken.id.value)
        requireAuditAvailable(command.auditEvent)
        val rotated = previous.value.copy(
            state = DeviceRefreshTokenState.ROTATED,
            version = previous.value.version + 1,
            rotatedToId = command.replacementRefreshToken.id,
            consumedAt = command.rotatedAt
        )
        update(previous, rotated, DeviceRefreshToken.serializer(), deviceRefreshTokenFields(rotated))
        create(COLLECTION_DEVICE_ACCESS_TOKENS, command.replacementAccessToken.id.value,
            command.replacementAccessToken, DeviceAccessToken.serializer(),
            deviceAccessTokenFields(command.replacementAccessToken))
        create(COLLECTION_DEVICE_REFRESH_TOKENS, command.replacementRefreshToken.id.value,
            command.replacementRefreshToken, DeviceRefreshToken.serializer(),
            deviceRefreshTokenFields(command.replacementRefreshToken))
        appendAudit(command.auditEvent)
        DeviceTokenRotationCommit(
            family.value,
            rotated,
            command.replacementAccessToken,
            command.replacementRefreshToken,
            command.auditEvent
        )
    }

    override suspend fun revokeDeviceTokenFamily(
        command: RevokeDeviceTokenFamilyCommand
    ): StoreResult<DeviceTokenFamilyRevocationCommit> = atomic {
        val family = requireEntity(
            COLLECTION_DEVICE_TOKEN_FAMILIES,
            command.familyId.value,
            DeviceTokenFamily.serializer()
        )
        requireVersion(family.value.version, command.expectedFamilyVersion)
        if (family.value.state != DeviceTokenFamilyState.ACTIVE) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        val access = query(
            COLLECTION_DEVICE_ACCESS_TOKENS,
            DeviceAccessToken.serializer(),
            FIELD_FAMILY_ID,
            family.value.id.value
        )
        val refresh = query(
            COLLECTION_DEVICE_REFRESH_TOKENS,
            DeviceRefreshToken.serializer(),
            FIELD_FAMILY_ID,
            family.value.id.value
        )
        requireAuditAvailable(command.auditEvent)
        val revokedAccessTokenIds = mutableListOf<DeviceAccessTokenId>()
        access.filter { it.value.state == DeviceAccessTokenState.ACTIVE }.forEach { token ->
            val replacement = token.value.copy(
                state = DeviceAccessTokenState.REVOKED,
                version = token.value.version + 1,
                revokedAt = command.revokedAt
            )
            update(
                token,
                replacement,
                DeviceAccessToken.serializer(),
                deviceAccessTokenFields(replacement)
            )
            revokedAccessTokenIds += token.value.id
        }
        val revokedRefreshTokenIds = mutableListOf<DeviceRefreshTokenId>()
        refresh.filter { it.value.state == DeviceRefreshTokenState.ACTIVE }.forEach { token ->
            val replacement = token.value.copy(
                state = DeviceRefreshTokenState.REVOKED,
                version = token.value.version + 1,
                revokedAt = command.revokedAt
            )
            update(
                token,
                replacement,
                DeviceRefreshToken.serializer(),
                deviceRefreshTokenFields(replacement)
            )
            revokedRefreshTokenIds += token.value.id
        }
        val revoked = family.value.copy(
            state = DeviceTokenFamilyState.REVOKED,
            version = family.value.version + 1,
            revokedAt = command.revokedAt,
            revocationReasonCode = command.reasonCode
        )
        update(family, revoked, DeviceTokenFamily.serializer(), deviceTokenFamilyFields(revoked))
        appendAudit(command.auditEvent)
        DeviceTokenFamilyRevocationCommit(
            revoked,
            revokedAccessTokenIds.sortedBy { it.value },
            revokedRefreshTokenIds.sortedBy { it.value },
            command.auditEvent
        )
    }

    override suspend fun rotateServiceCredential(
        command: RotateServiceCredentialCommand
    ): StoreResult<ServiceCredentialRotationCommit> = atomic {
        val existing = requireEntity(COLLECTION_SERVICE_CREDENTIALS, command.credentialId.value, ServiceCredential.serializer())
        requireVersion(existing.value.version, command.expectedVersion)
        val existingExpiresAt = existing.value.expiresAt
        if (existing.value.state != ServiceCredentialState.ACTIVE ||
            (existingExpiresAt != null && command.rotatedAt >= existingExpiresAt)
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        val identity = requireEntity(
            COLLECTION_SERVICE_IDENTITIES,
            existing.value.serviceIdentityId.value,
            ServiceIdentity.serializer()
        )
        if (command.replacement.serviceIdentityId != existing.value.serviceIdentityId ||
            !identity.value.capabilities.containsAll(command.replacement.capabilities)
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAbsent(COLLECTION_SERVICE_CREDENTIALS, command.replacement.id.value)
        claimUnique(
            UNIQUE_SERVICE_PREFIX,
            command.replacement.publicPrefix,
            COLLECTION_SERVICE_CREDENTIALS,
            command.replacement.id.value
        )
        claimUnique(
            UNIQUE_SERVICE_DIGEST,
            digestUniqueValue(command.replacement.secretDigest),
            COLLECTION_SERVICE_CREDENTIALS,
            command.replacement.id.value
        )
        requireAuditAvailable(command.auditEvent)
        val rotated = existing.value.copy(
            state = ServiceCredentialState.ROTATED,
            version = existing.value.version + 1,
            rotatedToId = command.replacement.id,
            rotatedAt = command.rotatedAt
        )
        update(existing, rotated, ServiceCredential.serializer(), serviceCredentialFields(rotated))
        create(
            COLLECTION_SERVICE_CREDENTIALS,
            command.replacement.id.value,
            command.replacement,
            ServiceCredential.serializer(),
            serviceCredentialFields(command.replacement)
        )
        appendAudit(command.auditEvent)
        ServiceCredentialRotationCommit(rotated, command.replacement, command.auditEvent)
    }

    override suspend fun linkExternalIdentity(
        command: LinkExternalIdentityCommand
    ): StoreResult<ExternalIdentityLinkCommit> = atomic {
        requireFederationProviderLease(command.federationProviderLease)
        val occurredAt = command.auditEvent.occurredAt
        val provisioning = command.jitProvisioning
        if (provisioning == null) {
            requireEntity(COLLECTION_USERS, command.identity.userId.value, User.serializer())
        } else {
            val organization = requireEntity(
                COLLECTION_ORGANIZATIONS,
                provisioning.membership.organizationId.value,
                Organization.serializer()
            )
            if (organization.value.state != OrganizationState.ACTIVE ||
                provisioning.user.state != UserState.ACTIVE ||
                provisioning.user.version != 0L || provisioning.user.primaryEmail != null ||
                provisioning.membership.userId != provisioning.user.id ||
                provisioning.membership.organizationId != command.federationProviderLease.organizationId ||
                provisioning.membership.role != OrganizationRole.VIEWER ||
                provisioning.membership.state != MembershipState.ACTIVE ||
                provisioning.membership.version != 0L ||
                command.identity.userId != provisioning.user.id ||
                command.identity.createdAt != occurredAt ||
                command.identity.updatedAt != occurredAt ||
                provisioning.user.createdAt != occurredAt ||
                provisioning.user.updatedAt != occurredAt ||
                provisioning.user.activatedAt != occurredAt ||
                provisioning.membership.createdAt != occurredAt ||
                provisioning.membership.updatedAt != occurredAt
            ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            requireAbsent(COLLECTION_USERS, provisioning.user.id.value)
            requireAbsent(COLLECTION_MEMBERSHIPS, provisioning.membership.id.value)
            claimUnique(
                UNIQUE_MEMBERSHIP,
                membershipUniqueValue(provisioning.user.id, provisioning.membership.organizationId),
                COLLECTION_MEMBERSHIPS,
                provisioning.membership.id.value
            )
        }
        requireAbsent(COLLECTION_EXTERNAL_IDENTITIES, command.identity.id.value)
        claimUnique(
            UNIQUE_EXTERNAL_IDENTITY,
            externalUniqueValue(command.identity.provider, command.identity.subject),
            COLLECTION_EXTERNAL_IDENTITIES,
            command.identity.id.value
        )
        requireReplayAvailable(command.replayReceipt)
        if (command.replayReceipt.expiresAt <= occurredAt) {
            abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        }
        requireAuditAvailable(command.auditEvent)
        provisioning?.let {
            create(COLLECTION_USERS, it.user.id.value, it.user, User.serializer(), userFields(it.user))
            create(
                COLLECTION_MEMBERSHIPS,
                it.membership.id.value,
                it.membership,
                Membership.serializer(),
                membershipFields(it.membership)
            )
        }
        create(
            COLLECTION_EXTERNAL_IDENTITIES,
            command.identity.id.value,
            command.identity,
            ExternalIdentity.serializer(),
            externalIdentityFields(command.identity)
        )
        createReplayReceipt(command.replayReceipt)
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
        createReplayReceipt(command.replayReceipt)
        command.replayReceipt
    }

    override suspend fun applyScimMutation(command: ApplyScimMutationCommand): StoreResult<ScimMutationCommit> =
        atomic { applyScimMutationValue(command) }

    override suspend fun applyScimBatch(command: ApplyScimBatchCommand): StoreResult<ScimBatchCommit> = atomic {
        val receipt = get(
            COLLECTION_SCIM_BATCH_RECEIPTS,
            command.operationId.value,
            AppliedScimBatch.serializer()
        )
        if (receipt != null) {
            if (receipt.value.command != command) abort(IdentityStoreErrorCode.IDEMPOTENCY_CONFLICT)
            return@atomic receipt.value.commit.copy(
                mutationCommits = receipt.value.commit.mutationCommits.map {
                    it.copy(alreadyApplied = true, auditEvent = null)
                },
                alreadyApplied = true,
                auditEvent = null
            )
        }
        val organization = requireEntity(
            COLLECTION_ORGANIZATIONS,
            command.organizationId.value,
            Organization.serializer()
        )
        if (organization.value.state != OrganizationState.ACTIVE) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        requireAuditAvailable(command.auditEvent)
        validateScimBatchLastOwner(command)
        val pendingUserIds = command.mutations.mapNotNull { it.mutation.user?.id }.toSet()
        val mutationCommits = command.mutations.map {
            applyScimMutationValue(it, enforceLastOwner = false, pendingUserIds = pendingUserIds)
        }

        val group = command.group?.also { aggregate ->
            val expectedVersion = requireNotNull(command.expectedGroupVersion)
            val existing = get(COLLECTION_SCIM_GROUPS, aggregate.id, ScimGroup.serializer())
            if (expectedVersion == 0L) {
                if (existing != null) abort(IdentityStoreErrorCode.ALREADY_EXISTS)
                create(
                    COLLECTION_SCIM_GROUPS,
                    aggregate.id,
                    aggregate,
                    ScimGroup.serializer(),
                    scimGroupFields(aggregate)
                )
            } else {
                if (existing == null || existing.value.provider != command.provider ||
                    existing.value.organizationId != command.organizationId
                ) abort(IdentityStoreErrorCode.NOT_FOUND)
                requireVersion(existing.value.version, expectedVersion)
                if (existing.value.createdAt != aggregate.createdAt) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
                update(existing, aggregate, ScimGroup.serializer(), scimGroupFields(aggregate))
            }
            aggregate.memberUserIds.forEach { userId ->
                if (userId !in pendingUserIds) requireEntity(COLLECTION_USERS, userId.value, User.serializer())
            }
        }

        val revokedSessionIds = mutableListOf<SessionId>()
        val revokedFamilyIds = mutableListOf<DeviceTokenFamilyId>()
        val revokedAccessIds = mutableListOf<DeviceAccessTokenId>()
        val revokedRefreshIds = mutableListOf<DeviceRefreshTokenId>()
        val tenantSessions = if (command.revocations.any { it.revokeSessions }) {
            query(COLLECTION_SESSIONS, IdentitySession.serializer(), FIELD_ORGANIZATION_ID, command.organizationId.value)
        } else emptyList()
        val tenantFamilies = if (command.revocations.any { it.revokeDeviceTokenFamilies }) {
            query(
                COLLECTION_DEVICE_TOKEN_FAMILIES,
                DeviceTokenFamily.serializer(),
                FIELD_ORGANIZATION_ID,
                command.organizationId.value
            )
        } else emptyList()
        command.revocations.forEach { revocation ->
            if (revocation.userId !in pendingUserIds) {
                requireEntity(COLLECTION_USERS, revocation.userId.value, User.serializer())
            }
            if (revocation.revokeSessions) {
                tenantSessions.filter { session ->
                    session.value.userId == revocation.userId && session.value.state == SessionState.ACTIVE &&
                        session.value.federationOrganizationId == command.organizationId
                }.forEach { session ->
                    val revoked = revokeSessionValue(
                        session.value,
                        command.auditEvent.occurredAt,
                        revocation.reasonCode
                    )
                    update(
                        session,
                        revoked,
                        IdentitySession.serializer(),
                        sessionFields(revoked)
                    )
                    revokedSessionIds += session.value.id
                }
            }
            if (revocation.revokeDeviceTokenFamilies) {
                tenantFamilies.filter { family ->
                    family.value.userId == revocation.userId && family.value.state == DeviceTokenFamilyState.ACTIVE
                }.forEach { family ->
                    val revokedFamily = family.value.copy(
                        state = DeviceTokenFamilyState.REVOKED,
                        version = family.value.version + 1,
                        revokedAt = command.auditEvent.occurredAt,
                        revocationReasonCode = revocation.reasonCode
                    )
                    update(
                        family,
                        revokedFamily,
                        DeviceTokenFamily.serializer(),
                        deviceTokenFamilyFields(revokedFamily)
                    )
                    revokedFamilyIds += family.value.id
                    query(
                        COLLECTION_DEVICE_ACCESS_TOKENS,
                        DeviceAccessToken.serializer(),
                        FIELD_FAMILY_ID,
                        family.value.id.value
                    ).filter { it.value.state == DeviceAccessTokenState.ACTIVE }.forEach { token ->
                        val revoked = token.value.copy(
                            state = DeviceAccessTokenState.REVOKED,
                            version = token.value.version + 1,
                            revokedAt = command.auditEvent.occurredAt
                        )
                        update(token, revoked, DeviceAccessToken.serializer(), deviceAccessTokenFields(revoked))
                        revokedAccessIds += token.value.id
                    }
                    query(
                        COLLECTION_DEVICE_REFRESH_TOKENS,
                        DeviceRefreshToken.serializer(),
                        FIELD_FAMILY_ID,
                        family.value.id.value
                    ).filter { it.value.state == DeviceRefreshTokenState.ACTIVE }.forEach { token ->
                        val revoked = token.value.copy(
                            state = DeviceRefreshTokenState.REVOKED,
                            version = token.value.version + 1,
                            revokedAt = command.auditEvent.occurredAt
                        )
                        update(token, revoked, DeviceRefreshToken.serializer(), deviceRefreshTokenFields(revoked))
                        revokedRefreshIds += token.value.id
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
        create(
            COLLECTION_SCIM_BATCH_RECEIPTS,
            command.operationId.value,
            AppliedScimBatch(command, commit),
            AppliedScimBatch.serializer(),
            mapOf(FIELD_PROVIDER to command.provider, FIELD_ORGANIZATION_ID to command.organizationId.value)
        )
        commit
    }

    private suspend fun <T> readEntity(collection: String, id: String, serializer: KSerializer<T>): StoreResult<T?> {
        val ready = requireInitialized() ?: return StoreResult.Failure(FirestoreFailureMapper.unavailable())
        return safeRead { transport.get(name(collection, id))?.decode(serializer) }
    }

    private suspend fun <T> readUniqueEntity(
        uniqueKind: String,
        uniqueValue: String,
        serializer: KSerializer<T>
    ): StoreResult<T?> {
        requireInitialized() ?: return StoreResult.Failure(FirestoreFailureMapper.unavailable())
        return safeRead {
            val key = uniqueDocumentId(uniqueKind, uniqueValue)
            val claim = transport.get(name(COLLECTION_UNIQUE, key))?.decode(UniqueClaim.serializer()) ?: return@safeRead null
            transport.get(name(claim.collection, claim.entityId))?.decode(serializer)
        }
    }

    private suspend fun <T> queryEntities(
        collection: String,
        serializer: KSerializer<T>,
        field: String,
        value: String,
        sort: (T) -> String
    ): StoreResult<List<T>> {
        requireInitialized() ?: return StoreResult.Failure(FirestoreFailureMapper.unavailable())
        return safeRead {
            transport.runQuery(parentName(), equalityQuery(collection, field, value))
                .map { it.decode(serializer) }.sortedBy(sort)
        }
    }

    private suspend fun <T> safeRead(block: suspend () -> T): StoreResult<T> = try {
        StoreResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: FirestoreStoreException) {
        StoreResult.Failure(failure.safeError)
    } catch (_: SerializationException) {
        StoreResult.Failure(FirestoreFailureMapper.internal())
    } catch (_: IllegalArgumentException) {
        StoreResult.Failure(FirestoreFailureMapper.internal())
    } catch (_: Throwable) {
        StoreResult.Failure(FirestoreFailureMapper.internal())
    }

    private suspend fun requireInitialized(): Unit? = initializationMutex.withLock {
        if (initialized) Unit else null
    }

    private suspend fun <T : Any> atomicWebAuthn(
        challengeId: ChallengeId,
        expectedChallengeVersion: Long,
        attemptedAt: Instant,
        rejectionAuditEvent: AuditEvent,
        block: suspend Transaction.() -> T
    ): StoreResult<WebAuthnCeremonyAttemptCommit<T>> = atomic {
        val challenge = requireChallenge(challengeId, expectedChallengeVersion, attemptedAt)
        requireAuditAvailable(rejectionAuditEvent)
        val writeCheckpoint = writes.size

        fun reject(error: IdentityStoreError): WebAuthnCeremonyAttemptCommit<T> {
            if (error.code == IdentityStoreErrorCode.UNAVAILABLE || error.code == IdentityStoreErrorCode.INTERNAL) {
                throw StoreAbort(error)
            }
            while (writes.size > writeCheckpoint) writes.removeAt(writes.lastIndex)
            val failed = challenge.value.copy(
                state = ChallengeState.FAILED,
                attemptCount = challenge.value.attemptCount + 1,
                version = challenge.value.version + 1,
                consumedAt = attemptedAt
            )
            update(challenge, failed, Challenge.serializer(), challengeFields(failed))
            appendAudit(rejectionAuditEvent)
            return WebAuthnCeremonyAttemptCommit.rejected(
                WebAuthnCeremonyRejectionCommit(
                    challenge = failed,
                    error = IdentityStoreError(error.code),
                    auditEvent = rejectionAuditEvent
                )
            )
        }

        try {
            WebAuthnCeremonyAttemptCommit.completed(block())
        } catch (abort: StoreAbort) {
            reject(abort.error)
        } catch (_: IllegalArgumentException) {
            reject(IdentityStoreError(IdentityStoreErrorCode.INVALID_TRANSITION))
        }
    }

    private suspend fun <T> atomic(block: suspend Transaction.() -> T): StoreResult<T> {
        requireInitialized() ?: return StoreResult.Failure(FirestoreFailureMapper.unavailable())
        var lastFailure: IdentityStoreError = FirestoreFailureMapper.versionConflict()
        repeat(config.maximumTransactionAttempts) { attempt ->
            val token = try {
                transport.beginTransaction()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: FirestoreStoreException) {
                lastFailure = failure.safeError
                if (failure.transactionRetryable && attempt + 1 < config.maximumTransactionAttempts) return@repeat
                return StoreResult.Failure(failure.safeError)
            } catch (_: Throwable) {
                return StoreResult.Failure(FirestoreFailureMapper.internal())
            }
            val transaction = Transaction(token)
            try {
                val value = transaction.block()
                if (transaction.writes.isEmpty()) {
                    transport.rollback(token)
                } else {
                    if (transaction.writes.size > MAXIMUM_TRANSACTION_WRITES) {
                        throw StoreAbort(FirestoreFailureMapper.internal())
                    }
                    transport.commit(token, transaction.writes)
                }
                return StoreResult.Success(value)
            } catch (cancelled: CancellationException) {
                transport.rollback(token)
                throw cancelled
            } catch (abort: StoreAbort) {
                transport.rollback(token)
                return StoreResult.Failure(abort.error)
            } catch (failure: FirestoreStoreException) {
                transport.rollback(token)
                lastFailure = failure.safeError
                if (failure.transactionRetryable && attempt + 1 < config.maximumTransactionAttempts) return@repeat
                return StoreResult.Failure(failure.safeError)
            } catch (_: SerializationException) {
                transport.rollback(token)
                return StoreResult.Failure(FirestoreFailureMapper.internal())
            } catch (_: IllegalArgumentException) {
                transport.rollback(token)
                return StoreResult.Failure(FirestoreFailureMapper.internal())
            } catch (_: Throwable) {
                transport.rollback(token)
                return StoreResult.Failure(FirestoreFailureMapper.internal())
            }
        }
        return StoreResult.Failure(lastFailure)
    }

    private inner class Transaction(val token: String) {
        val writes = mutableListOf<FirestoreWrite>()

        suspend fun <T> get(collection: String, id: String, serializer: KSerializer<T>): EntityDocument<T>? {
            val documentName = name(collection, id)
            val document = transport.batchGet(listOf(documentName), token)[documentName] ?: return null
            return EntityDocument(collection, id, document.decode(serializer), requireNotNull(document.updateTime))
        }

        suspend fun <T> requireEntity(collection: String, id: String, serializer: KSerializer<T>): EntityDocument<T> =
            get(collection, id, serializer) ?: abort(IdentityStoreErrorCode.NOT_FOUND)

        suspend fun requireAbsent(collection: String, id: String) {
            if (transport.batchGet(listOf(name(collection, id)), token)[name(collection, id)] != null) {
                abort(IdentityStoreErrorCode.ALREADY_EXISTS)
            }
        }

        suspend fun <T> query(
            collection: String,
            serializer: KSerializer<T>,
            field: String,
            value: String
        ): List<EntityDocument<T>> = transport.runQuery(parentName(), equalityQuery(collection, field, value), token).map {
            EntityDocument(collection, it.name.substringAfterLast('/'), it.decode(serializer), requireNotNull(it.updateTime))
        }

        suspend fun hasAny(collection: String): Boolean = transport.runQuery(
            parentName(),
            FirestoreStructuredQuery(
                from = listOf(FirestoreCollectionSelector(collection)),
                limit = 1
            ),
            token
        ).isNotEmpty()

        suspend fun auditEventsBefore(
            command: PurgeAuditEventsCommand
        ): List<EntityDocument<AuditEvent>> = transport.runQuery(
            parentName(),
            auditRetentionQuery(command),
            token
        ).map { document ->
            EntityDocument(
                COLLECTION_AUDIT_EVENTS,
                document.name.substringAfterLast('/'),
                document.decode(AuditEvent.serializer()),
                requireNotNull(document.updateTime)
            )
        }

        fun <T> create(
            collection: String,
            id: String,
            value: T,
            serializer: KSerializer<T>,
            fields: Map<String, String> = emptyMap()
        ) {
            writes += FirestoreWrite(
                update = encodeDocument(collection, id, value, serializer, fields),
                currentDocument = FirestorePrecondition(exists = false)
            )
        }

        fun <T> update(
            current: EntityDocument<T>,
            replacement: T,
            serializer: KSerializer<T>,
            fields: Map<String, String> = emptyMap()
        ) {
            writes += FirestoreWrite(
                update = encodeDocument(current.collection, current.id, replacement, serializer, fields),
                currentDocument = FirestorePrecondition(updateTime = current.updateTime)
            )
        }

        fun delete(current: EntityDocument<*>) {
            writes += FirestoreWrite(
                delete = name(current.collection, current.id),
                currentDocument = FirestorePrecondition(updateTime = current.updateTime)
            )
        }

        suspend fun claimUnique(kind: String, value: String, collection: String, id: String) {
            val key = uniqueDocumentId(kind, value)
            if (get(COLLECTION_UNIQUE, key, UniqueClaim.serializer()) != null) {
                abort(IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
            }
            create(
                COLLECTION_UNIQUE,
                key,
                UniqueClaim(kind, collection, id),
                UniqueClaim.serializer(),
                mapOf(FIELD_KIND to kind, FIELD_ENTITY_ID to id)
            )
        }

        suspend fun claimDeviceToken(
            selectorKind: String,
            selector: String,
            digest: SecretDigest,
            collection: String,
            id: String
        ) {
            claimUnique(selectorKind, selector, collection, id)
            claimUnique(UNIQUE_DEVICE_TOKEN_SELECTOR, selector, collection, id)
            claimUnique(UNIQUE_DEVICE_TOKEN_DIGEST, digestUniqueValue(digest), collection, id)
        }

        suspend fun releaseUnique(kind: String, value: String, collection: String, id: String) {
            val key = uniqueDocumentId(kind, value)
            val claim = get(COLLECTION_UNIQUE, key, UniqueClaim.serializer()) ?: return
            if (claim.value.collection != collection || claim.value.entityId != id) abort(IdentityStoreErrorCode.INTERNAL)
            delete(claim)
        }

        suspend fun requireAuditAvailable(event: AuditEvent) = requireAbsent(COLLECTION_AUDIT_EVENTS, event.id.value)

        fun appendAudit(event: AuditEvent) = create(
            COLLECTION_AUDIT_EVENTS,
            event.id.value,
            event,
            AuditEvent.serializer(),
            auditFields(event)
        )

        suspend fun requireChallenge(id: ChallengeId, version: Long, at: Instant): EntityDocument<Challenge> {
            val challenge = requireEntity(COLLECTION_CHALLENGES, id.value, Challenge.serializer())
            if (challenge.value.state != ChallengeState.PENDING) abort(IdentityStoreErrorCode.CHALLENGE_NOT_PENDING)
            if (at >= challenge.value.expiresAt) abort(IdentityStoreErrorCode.CHALLENGE_EXPIRED)
            requireVersion(challenge.value.version, version)
            return challenge
        }

        suspend fun federationProviderByRoute(
            organizationId: OrganizationId,
            providerId: String
        ): EntityDocument<FederationProviderControl>? {
            val claim = uniqueClaim(
                UNIQUE_FEDERATION_PROVIDER_ROUTE,
                federationProviderRouteUniqueValue(organizationId, providerId)
            ) ?: return null
            requireFederationProviderClaim(claim, UNIQUE_FEDERATION_PROVIDER_ROUTE)
            val control = get(
                COLLECTION_FEDERATION_PROVIDER_CONTROLS,
                claim.value.entityId,
                FederationProviderControl.serializer()
            ) ?: abort(IdentityStoreErrorCode.INTERNAL)
            if (control.value.organizationId != organizationId || control.value.providerId != providerId) {
                abort(IdentityStoreErrorCode.INTERNAL)
            }
            return control
        }

        suspend fun federationProviderByStorageKey(
            storageKey: String
        ): EntityDocument<FederationProviderControl>? {
            val direct = get(
                COLLECTION_FEDERATION_PROVIDER_CONTROLS,
                storageKey,
                FederationProviderControl.serializer()
            )
            val claim = uniqueClaim(UNIQUE_FEDERATION_PROVIDER_STORAGE_KEY, storageKey)
            if (direct == null && claim == null) return null
            if (direct == null || claim == null) abort(IdentityStoreErrorCode.INTERNAL)
            requireFederationProviderClaim(claim, UNIQUE_FEDERATION_PROVIDER_STORAGE_KEY)
            if (claim.value.entityId != storageKey || direct.value.storageKey != storageKey) {
                abort(IdentityStoreErrorCode.INTERNAL)
            }
            return direct
        }

        private suspend fun uniqueClaim(kind: String, value: String): EntityDocument<UniqueClaim>? =
            get(COLLECTION_UNIQUE, uniqueDocumentId(kind, value), UniqueClaim.serializer())

        private fun requireFederationProviderClaim(claim: EntityDocument<UniqueClaim>, expectedKind: String) {
            if (claim.value.collection != COLLECTION_FEDERATION_PROVIDER_CONTROLS ||
                claim.value.kind != expectedKind
            ) abort(IdentityStoreErrorCode.INTERNAL)
        }

        suspend fun claimFederationProviderUniqueness(control: FederationProviderControl) {
            claimUnique(
                UNIQUE_FEDERATION_PROVIDER_ROUTE,
                federationProviderRouteUniqueValue(control.organizationId, control.providerId),
                COLLECTION_FEDERATION_PROVIDER_CONTROLS,
                control.storageKey
            )
            claimUnique(
                UNIQUE_FEDERATION_PROVIDER_STORAGE_KEY,
                control.storageKey,
                COLLECTION_FEDERATION_PROVIDER_CONTROLS,
                control.storageKey
            )
        }

        fun createFederationProviderControl(control: FederationProviderControl) = create(
            COLLECTION_FEDERATION_PROVIDER_CONTROLS,
            control.storageKey,
            control,
            FederationProviderControl.serializer(),
            federationProviderControlFields(control)
        )

        suspend fun requireFederationProviderLease(lease: FederationProviderLease) {
            val current = federationProviderByStorageKey(lease.storageKey)
                ?: abort(IdentityStoreErrorCode.NOT_FOUND)
            if (current.value.state != FederationProviderState.ENABLED || current.value.lease() != lease) {
                abort(IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED)
            }
            requireFederationProviderRouteMapping(current)
        }

        private suspend fun requireFederationProviderRouteMapping(
            control: EntityDocument<FederationProviderControl>
        ) {
            val route = federationProviderByRoute(
                control.value.organizationId,
                control.value.providerId
            ) ?: abort(IdentityStoreErrorCode.INTERNAL)
            if (route.id != control.id || route.value != control.value) {
                abort(IdentityStoreErrorCode.INTERNAL)
            }
        }

        suspend fun requireChallengeFederationLease(
            challenge: Challenge,
            commandLease: FederationProviderLease?
        ) {
            if (challenge.federationProviderLease != commandLease) {
                abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            commandLease?.let { requireFederationProviderLease(it) }
        }

        suspend fun requireFederatedSessionProvider(session: IdentitySession) {
            val storageKey = session.federationProviderKey ?: return
            val current = federationProviderByStorageKey(storageKey)
                ?: abort(IdentityStoreErrorCode.NOT_FOUND)
            requireFederationProviderRouteMapping(current)
            val expectedKind = when (session.authenticationMethod) {
                SessionAuthenticationMethod.OIDC -> FederationProviderKind.OIDC
                SessionAuthenticationMethod.SAML -> FederationProviderKind.SAML
                else -> abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            }
            if (current.value.state != FederationProviderState.ENABLED ||
                current.value.organizationId != session.federationOrganizationId ||
                current.value.kind != expectedKind ||
                current.value.sessionEpoch != session.federationProviderSessionEpoch
            ) abort(IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED)
        }

        suspend fun requireNewSession(session: IdentitySession, user: User) {
            requireAbsent(COLLECTION_SESSIONS, session.id.value)
            if (session.state != SessionState.ACTIVE || session.version != 0L || session.userId != user.id ||
                session.userSessionEpoch != user.sessionEpoch || user.state != UserState.ACTIVE
            ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
            requireFederatedSessionProvider(session)
            claimUnique(UNIQUE_SESSION_DIGEST, digestUniqueValue(session.tokenDigest), COLLECTION_SESSIONS, session.id.value)
        }

        fun createSessionValue(session: IdentitySession) = create(
            COLLECTION_SESSIONS,
            session.id.value,
            session,
            IdentitySession.serializer(),
            sessionFields(session)
        )

        suspend fun requireActiveSession(
            id: SessionId,
            version: Long,
            at: Instant,
            validateFederationProvider: Boolean = false
        ): EntityDocument<IdentitySession> {
            val session = requireEntity(COLLECTION_SESSIONS, id.value, IdentitySession.serializer())
            if (session.value.state != SessionState.ACTIVE) abort(IdentityStoreErrorCode.SESSION_NOT_ACTIVE)
            if (validateFederationProvider) requireFederatedSessionProvider(session.value)
            if (at >= session.value.idleExpiresAt || at >= session.value.absoluteExpiresAt) {
                abort(IdentityStoreErrorCode.SESSION_EXPIRED)
            }
            requireVersion(session.value.version, version)
            return session
        }

        suspend fun replaceUserEmailClaim(current: User, replacement: User) {
            val previous = current.primaryEmail?.let(::normalizeEmail)
            val next = replacement.primaryEmail?.let(::normalizeEmail)
            if (previous == next) return
            previous?.let { releaseUnique(UNIQUE_EMAIL, it, COLLECTION_USERS, current.id.value) }
            next?.let { claimUnique(UNIQUE_EMAIL, it, COLLECTION_USERS, replacement.id.value) }
        }

        suspend fun requireReplayAvailable(receipt: ExternalIdentityReplayReceipt) {
            if (get(
                    COLLECTION_REPLAY_RECEIPTS,
                    receipt.id.value,
                    ExternalIdentityReplayReceipt.serializer()
                ) != null
            ) {
                abort(IdentityStoreErrorCode.REPLAY_DETECTED)
            }
            val assertion = receipt.provider + "\u0000" + digestUniqueValue(receipt.assertionDigest)
            val claimId = uniqueDocumentId(UNIQUE_REPLAY_ASSERTION, assertion)
            if (get(COLLECTION_UNIQUE, claimId, UniqueClaim.serializer()) != null) {
                abort(IdentityStoreErrorCode.REPLAY_DETECTED)
            }
            create(
                COLLECTION_UNIQUE,
                claimId,
                UniqueClaim(
                    kind = UNIQUE_REPLAY_ASSERTION,
                    collection = COLLECTION_REPLAY_RECEIPTS,
                    entityId = receipt.id.value
                ),
                UniqueClaim.serializer(),
                mapOf(FIELD_KIND to UNIQUE_REPLAY_ASSERTION, FIELD_ENTITY_ID to receipt.id.value)
            )
        }

        fun createReplayReceipt(receipt: ExternalIdentityReplayReceipt) = create(
            COLLECTION_REPLAY_RECEIPTS,
            receipt.id.value,
            receipt,
            ExternalIdentityReplayReceipt.serializer(),
            mapOf(FIELD_PROVIDER to receipt.provider)
        )

        suspend fun upsertScimUser(user: User) {
            val existing = get(COLLECTION_USERS, user.id.value, User.serializer())
            if (existing == null) {
                if (user.version != 0L) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
                user.primaryEmail?.let { claimUnique(UNIQUE_EMAIL, normalizeEmail(it), COLLECTION_USERS, user.id.value) }
                create(COLLECTION_USERS, user.id.value, user, User.serializer(), userFields(user))
            } else {
                if (user.version != existing.value.version + 1) abortVersion()
                replaceUserEmailClaim(existing.value, user)
                update(existing, user, User.serializer(), userFields(user))
            }
        }

        suspend fun upsertScimMembership(
            membership: Membership,
            enforceLastOwner: Boolean = true,
            pendingUserIds: Set<UserId> = emptySet()
        ) {
            if (membership.userId !in pendingUserIds) {
                requireEntity(COLLECTION_USERS, membership.userId.value, User.serializer())
            }
            requireEntity(COLLECTION_ORGANIZATIONS, membership.organizationId.value, Organization.serializer())
            val existing = get(COLLECTION_MEMBERSHIPS, membership.id.value, Membership.serializer())
            if (existing == null) {
                if (membership.version != 0L) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
                claimUnique(
                    UNIQUE_MEMBERSHIP,
                    membershipUniqueValue(membership.userId, membership.organizationId),
                    COLLECTION_MEMBERSHIPS,
                    membership.id.value
                )
                create(COLLECTION_MEMBERSHIPS, membership.id.value, membership, Membership.serializer(), membershipFields(membership))
            } else {
                if (membership.version != existing.value.version + 1 || existing.value.userId != membership.userId ||
                    existing.value.organizationId != membership.organizationId
                ) abortVersion()
                val removesOwner = existing.value.state == MembershipState.ACTIVE &&
                    existing.value.role == OrganizationRole.OWNER &&
                    (membership.state != MembershipState.ACTIVE || membership.role != OrganizationRole.OWNER)
                if (enforceLastOwner && removesOwner) {
                    val others = query(
                        COLLECTION_MEMBERSHIPS,
                        Membership.serializer(),
                        FIELD_ORGANIZATION_ID,
                        membership.organizationId.value
                    )
                    if (others.none { it.value.id != membership.id && it.value.state == MembershipState.ACTIVE &&
                            it.value.role == OrganizationRole.OWNER }) abort(IdentityStoreErrorCode.LAST_OWNER)
                }
                update(existing, membership, Membership.serializer(), membershipFields(membership))
            }
        }

        suspend fun applyScimMutationValue(
            command: ApplyScimMutationCommand,
            enforceLastOwner: Boolean = true,
            pendingUserIds: Set<UserId> = emptySet()
        ): ScimMutationCommit {
            val receipt = get(
                COLLECTION_SCIM_RECEIPTS,
                command.mutation.operationId.value,
                AppliedScimMutation.serializer()
            )
            if (receipt != null) {
                if (receipt.value.command != command) abort(IdentityStoreErrorCode.IDEMPOTENCY_CONFLICT)
                return receipt.value.commit.copy(alreadyApplied = true, auditEvent = null)
            }
            requireAuditAvailable(command.auditEvent)
            val commit = when (command.mutation.type) {
                ScimMutationType.UPSERT_USER,
                ScimMutationType.DEACTIVATE_USER -> {
                    val user = command.mutation.user!!
                    if (command.mutation.type == ScimMutationType.DEACTIVATE_USER &&
                        user.state != UserState.DEACTIVATED
                    ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
                    upsertScimUser(user)
                    ScimMutationCommit(user = user, alreadyApplied = false, auditEvent = command.auditEvent)
                }
                ScimMutationType.UPSERT_MEMBERSHIP,
                ScimMutationType.REMOVE_MEMBERSHIP -> {
                    val membership = command.mutation.membership!!
                    if (command.mutation.type == ScimMutationType.REMOVE_MEMBERSHIP &&
                        membership.state != MembershipState.REMOVED
                    ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
                    upsertScimMembership(membership, enforceLastOwner, pendingUserIds)
                    ScimMutationCommit(membership = membership, alreadyApplied = false, auditEvent = command.auditEvent)
                }
            }
            appendAudit(command.auditEvent)
            create(
                COLLECTION_SCIM_RECEIPTS,
                command.mutation.operationId.value,
                AppliedScimMutation(command, commit),
                AppliedScimMutation.serializer(),
                mapOf(FIELD_PROVIDER to command.mutation.provider)
            )
            return commit
        }

        suspend fun validateScimBatchLastOwner(command: ApplyScimBatchCommand) {
            val replacements = command.mutations.mapNotNull { it.mutation.membership }
            if (replacements.isEmpty()) return
            val current = query(
                COLLECTION_MEMBERSHIPS,
                Membership.serializer(),
                FIELD_ORGANIZATION_ID,
                command.organizationId.value
            ).map { it.value }
            val prospective = current.associateBy { it.id }.toMutableMap()
            replacements.forEach { prospective[it.id] = it }
            val currentOwners = current.count {
                it.state == MembershipState.ACTIVE && it.role == OrganizationRole.OWNER
            }
            val prospectiveOwners = prospective.values.count {
                it.state == MembershipState.ACTIVE && it.role == OrganizationRole.OWNER
            }
            if (currentOwners > 0 && prospectiveOwners == 0) abort(IdentityStoreErrorCode.LAST_OWNER)
        }
    }

    private suspend fun uniqueDocumentId(kind: String, value: String): String =
        "$kind-${Base64Url.encode(runtime.crypto.sha256(value.encodeToByteArray()))}"

    private fun <T> encodeDocument(
        collection: String,
        id: String,
        value: T,
        serializer: KSerializer<T>,
        indexedFields: Map<String, String>
    ): FirestoreDocument {
        val payload = json.encodeToString(serializer, value)
        require(payload.encodeToByteArray().size <= config.maximumRequestBytes) { "Firestore entity payload exceeds limit" }
        val fields = mutableMapOf(
            FIELD_PAYLOAD to stringValue(payload),
            FIELD_ENTITY_ID to stringValue(id),
            FIELD_ENVIRONMENT to stringValue(config.environment.wireName),
            FIELD_NAMESPACE to stringValue(config.namespace),
            FIELD_SCHEMA_VERSION to integerValue(FIRESTORE_SCHEMA_VERSION.toLong())
        )
        indexedFields.forEach { (key, fieldValue) ->
            fields[key] = if (collection == COLLECTION_AUDIT_EVENTS && key == FIELD_OCCURRED_AT) {
                timestampValue(fieldValue)
            } else {
                stringValue(fieldValue)
            }
        }
        return FirestoreDocument(name(collection, id), fields)
    }

    private fun <T> FirestoreDocument.decode(serializer: KSerializer<T>): T {
        if (fields[FIELD_ENVIRONMENT]?.stringValue != config.environment.wireName ||
            fields[FIELD_NAMESPACE]?.stringValue != config.namespace ||
            fields[FIELD_SCHEMA_VERSION]?.integerValue?.toIntOrNull() != FIRESTORE_SCHEMA_VERSION
        ) throw FirestoreStoreException(FirestoreFailureMapper.internal())
        val payload = fields[FIELD_PAYLOAD]?.stringValue ?: throw FirestoreStoreException(FirestoreFailureMapper.internal())
        if (payload.encodeToByteArray().size > config.maximumResponseBytes) {
            throw FirestoreStoreException(FirestoreFailureMapper.internal())
        }
        return json.decodeFromString(serializer, payload)
    }

    private fun equalityQuery(collection: String, field: String, value: String): FirestoreStructuredQuery =
        FirestoreStructuredQuery(
            from = listOf(FirestoreCollectionSelector(collection)),
            where = FirestoreFilter(
                fieldFilter = FirestoreFieldFilter(
                    FirestoreFieldReference(field),
                    op = "EQUAL",
                    value = stringValue(value)
                )
            )
        )

    private fun auditPageQuery(request: OrganizationAuditEventPageRequest): FirestoreStructuredQuery =
        FirestoreStructuredQuery(
            from = listOf(FirestoreCollectionSelector(COLLECTION_AUDIT_EVENTS)),
            where = FirestoreFilter(
                fieldFilter = FirestoreFieldFilter(
                    field = FirestoreFieldReference(FIELD_ORGANIZATION_ID),
                    op = "EQUAL",
                    value = stringValue(request.organizationId.value)
                )
            ),
            orderBy = listOf(
                FirestoreOrder(FirestoreFieldReference(FIELD_OCCURRED_AT), direction = "DESCENDING"),
                FirestoreOrder(FirestoreFieldReference(FIELD_ENTITY_ID), direction = "DESCENDING")
            ),
            startAt = request.cursor?.let { cursor ->
                FirestoreCursor(
                    values = listOf(
                        timestampValue(cursor.occurredAt.toString()),
                        stringValue(cursor.id.value)
                    ),
                    before = false
                )
            },
            limit = request.limit + 1
        )

    private fun auditRetentionQuery(command: PurgeAuditEventsCommand): FirestoreStructuredQuery =
        FirestoreStructuredQuery(
            from = listOf(FirestoreCollectionSelector(COLLECTION_AUDIT_EVENTS)),
            where = FirestoreFilter(
                fieldFilter = FirestoreFieldFilter(
                    field = FirestoreFieldReference(FIELD_OCCURRED_AT),
                    op = "LESS_THAN",
                    value = timestampValue(command.occurredBefore.toString())
                )
            ),
            orderBy = listOf(
                FirestoreOrder(FirestoreFieldReference(FIELD_OCCURRED_AT), direction = "ASCENDING"),
                FirestoreOrder(FirestoreFieldReference(FIELD_ENTITY_ID), direction = "ASCENDING")
            ),
            limit = command.maximumEvents + 1
        )

    private fun name(collection: String, id: String): String {
        require(COLLECTION_ID.matches(collection) && DOCUMENT_ID.matches(id)) { "Invalid Firestore document path" }
        return "projects/${config.projectId}/databases/${config.databaseId}/documents/${config.namespaceDocument}/$collection/$id"
    }

    private fun parentName(): String =
        "projects/${config.projectId}/databases/${config.databaseId}/documents/${config.namespaceDocument}"

    private fun userFields(value: User): Map<String, String> = buildMap {
        put(FIELD_STATE, value.state.name.lowercase())
        value.primaryEmail?.let { put(FIELD_NORMALIZED_EMAIL, normalizeEmail(it)) }
    }

    private fun credentialFields(value: Credential) = mapOf(
        FIELD_USER_ID to value.userId.value,
        FIELD_STATE to value.state.name.lowercase(),
        FIELD_CREATED_AT to value.createdAt.toString()
    )

    private fun sessionFields(value: IdentitySession) = buildMap {
        put(FIELD_USER_ID, value.userId.value)
        put(FIELD_STATE, value.state.name.lowercase())
        put(FIELD_LAST_USED_AT, value.lastUsedAt.toString())
        value.federationOrganizationId?.let { put(FIELD_ORGANIZATION_ID, it.value) }
        value.federationProviderKey?.let { put(FIELD_PROVIDER, it) }
        value.federationProviderSessionEpoch?.let { put(FIELD_PROVIDER_SESSION_EPOCH, it.toString()) }
    }

    private fun federationProviderControlFields(value: FederationProviderControl) = mapOf(
        FIELD_ORGANIZATION_ID to value.organizationId.value,
        FIELD_PROVIDER_ID to value.providerId,
        FIELD_PROVIDER_KIND to value.kind.name.lowercase(),
        FIELD_STORAGE_KEY to value.storageKey,
        FIELD_STATE to value.state.name.lowercase(),
        FIELD_PROVIDER_SESSION_EPOCH to value.sessionEpoch.toString(),
        FIELD_VERSION to value.version.toString(),
        FIELD_UPDATED_AT to value.updatedAt.toString()
    )

    private fun organizationFields(value: Organization) = mapOf(
        FIELD_SLUG to value.slug,
        FIELD_STATE to value.state.name.lowercase()
    )

    private fun membershipFields(value: Membership) = mapOf(
        FIELD_USER_ID to value.userId.value,
        FIELD_ORGANIZATION_ID to value.organizationId.value,
        FIELD_STATE to value.state.name.lowercase(),
        FIELD_ROLE to value.role.wireName
    )

    private fun invitationFields(value: Invitation) = mapOf(
        FIELD_ORGANIZATION_ID to value.organizationId.value,
        FIELD_NORMALIZED_EMAIL to normalizeEmail(value.email),
        FIELD_STATE to value.state.name.lowercase()
    )

    private fun serviceIdentityFields(value: ServiceIdentity) = mapOf(
        FIELD_ORGANIZATION_ID to value.organizationId.value,
        FIELD_STATE to value.state.name.lowercase()
    )

    private fun serviceCredentialFields(value: ServiceCredential) = mapOf(
        FIELD_SERVICE_IDENTITY_ID to value.serviceIdentityId.value,
        FIELD_PUBLIC_PREFIX to value.publicPrefix,
        FIELD_STATE to value.state.name.lowercase()
    )

    private fun scimGroupFields(value: ScimGroup) = mapOf(
        FIELD_ORGANIZATION_ID to value.organizationId.value,
        FIELD_PROVIDER to value.provider,
        FIELD_STATE to value.state.name.lowercase()
    )

    private fun externalIdentityFields(value: ExternalIdentity) = mapOf(
        FIELD_USER_ID to value.userId.value,
        FIELD_PROVIDER to value.provider,
        FIELD_STATE to value.state.name.lowercase()
    )

    private fun challengeFields(value: Challenge) = buildMap {
        put(FIELD_STATE, value.state.name.lowercase())
        value.userId?.let { put(FIELD_USER_ID, it.value) }
        value.organizationId?.let { put(FIELD_ORGANIZATION_ID, it.value) }
    }

    private fun recoveryFields(value: RecoveryCode) = mapOf(
        FIELD_USER_ID to value.userId.value,
        FIELD_GENERATION to value.generation.toString(),
        FIELD_STATE to value.state.name.lowercase()
    )

    private fun deviceGrantFields(value: DeviceGrant) = mapOf(
        FIELD_STATE to value.state.name.lowercase(),
        FIELD_CREATED_AT to value.createdAt.toString()
    )

    private fun deviceTokenFamilyFields(value: DeviceTokenFamily) = mapOf(
        FIELD_USER_ID to value.userId.value,
        FIELD_ORGANIZATION_ID to value.organizationId.value,
        FIELD_STATE to value.state.name.lowercase(),
        FIELD_CREATED_AT to value.createdAt.toString()
    )

    private fun deviceAccessTokenFields(value: DeviceAccessToken) = mapOf(
        FIELD_FAMILY_ID to value.familyId.value,
        FIELD_STATE to value.state.name.lowercase(),
        FIELD_CREATED_AT to value.createdAt.toString()
    )

    private fun deviceRefreshTokenFields(value: DeviceRefreshToken) = mapOf(
        FIELD_FAMILY_ID to value.familyId.value,
        FIELD_STATE to value.state.name.lowercase(),
        FIELD_CREATED_AT to value.createdAt.toString()
    )

    private fun auditFields(value: AuditEvent) = buildMap {
        put(FIELD_OCCURRED_AT, value.occurredAt.toString())
        put(FIELD_ACTION, value.action.name.lowercase())
        value.organizationId?.let { put(FIELD_ORGANIZATION_ID, it.value) }
    }

    private fun normalizeEmail(value: EmailAddress): String = value.value.lowercase()
    private fun membershipUniqueValue(userId: UserId, organizationId: OrganizationId) =
        "${organizationId.value}\u0000${userId.value}"
    private fun federationProviderRouteUniqueValue(organizationId: OrganizationId, providerId: String) =
        "${organizationId.value}\u0000$providerId"
    private fun pendingInvitationUniqueValue(organizationId: OrganizationId, email: EmailAddress) =
        "${organizationId.value}\u0000${normalizeEmail(email)}"
    private fun externalUniqueValue(provider: String, subject: ExternalSubject) =
        "$provider\u0000${subject.value}"
    private fun digestUniqueValue(value: SecretDigest): String =
        "${value.algorithm.name}\u0000${value.keyVersion.orEmpty()}\u0000${value.encoded}"

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

    private fun consumeChallengeValue(value: Challenge, at: Instant): Challenge = value.copy(
        state = ChallengeState.CONSUMED,
        version = value.version + 1,
        consumedAt = at
    )

    private fun rotateSessionValue(value: IdentitySession, replacement: SessionId): IdentitySession = value.copy(
        state = SessionState.ROTATED,
        version = value.version + 1,
        rotatedToId = replacement
    )

    private fun revokeSessionValue(value: IdentitySession, at: Instant, reason: String): IdentitySession = value.copy(
        state = SessionState.REVOKED,
        version = value.version + 1,
        revokedAt = at,
        revocationReasonCode = reason
    )

    private fun requireDeviceGrantTransition(existing: DeviceGrant, replacement: DeviceGrant) {
        if (existing.id != replacement.id || existing.deviceCodeDigest != replacement.deviceCodeDigest ||
            existing.userCodeDigest != replacement.userCodeDigest || existing.clientId != replacement.clientId ||
            existing.clientName != replacement.clientName ||
            existing.requestedCapabilities != replacement.requestedCapabilities || existing.createdAt != replacement.createdAt ||
            existing.expiresAt != replacement.expiresAt
        ) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
        val allowed = when (existing.state) {
            DeviceGrantState.PENDING -> replacement.state in setOf(
                DeviceGrantState.PENDING, DeviceGrantState.AUTHORIZED, DeviceGrantState.DENIED,
                DeviceGrantState.EXPIRED, DeviceGrantState.CANCELLED
            )
            DeviceGrantState.AUTHORIZED -> replacement.state in setOf(
                DeviceGrantState.AUTHORIZED, DeviceGrantState.CONSUMED, DeviceGrantState.EXPIRED,
                DeviceGrantState.CANCELLED
            )
            DeviceGrantState.DENIED, DeviceGrantState.CONSUMED, DeviceGrantState.EXPIRED,
            DeviceGrantState.CANCELLED -> false
        }
        if (!allowed) abort(IdentityStoreErrorCode.INVALID_TRANSITION)
    }

    private fun requireVersion(actual: Long, expected: Long) {
        if (actual != expected) abortVersion()
    }

    private fun abortVersion(): Nothing = throw StoreAbort(FirestoreFailureMapper.versionConflict())
    private fun abort(code: IdentityStoreErrorCode): Nothing = throw StoreAbort(IdentityStoreError(code))
    private fun failure(code: IdentityStoreErrorCode): StoreResult.Failure =
        StoreResult.Failure(IdentityStoreError(code))

    private data class EntityDocument<T>(
        val collection: String,
        val id: String,
        val value: T,
        val updateTime: String
    )

    private class StoreAbort(val error: IdentityStoreError) : Throwable()

    @Serializable
    private data class UniqueClaim(val kind: String, val collection: String, val entityId: String)

    @Serializable
    private data class AppliedScimMutation(
        val command: ApplyScimMutationCommand,
        val commit: ScimMutationCommit
    )

    @Serializable
    private data class AppliedScimBatch(
        val command: ApplyScimBatchCommand,
        val commit: ScimBatchCommit
    )

    @Serializable
    private data class BootstrapReceipt(val secretDigest: SecretDigest, val completedAt: Instant)

    companion object {
        const val FIRESTORE_SCHEMA_VERSION: Int = 1
        const val FIRESTORE_ENVIRONMENT_MARKER_SCHEMA_VERSION: Int = 2
        private const val MAXIMUM_TRANSACTION_WRITES = 500
        private const val DOCUMENT_CURRENT = "current"

        private const val COLLECTION_BOOTSTRAP = "bootstrap"
        private const val COLLECTION_USERS = "users"
        private const val COLLECTION_CREDENTIALS = "credentials"
        private const val COLLECTION_SESSIONS = "sessions"
        private const val COLLECTION_ORGANIZATIONS = "organizations"
        private const val COLLECTION_MEMBERSHIPS = "memberships"
        private const val COLLECTION_INVITATIONS = "invitations"
        private const val COLLECTION_SERVICE_IDENTITIES = "serviceIdentities"
        private const val COLLECTION_SERVICE_CREDENTIALS = "serviceCredentials"
        private const val COLLECTION_EXTERNAL_IDENTITIES = "externalIdentities"
        private const val COLLECTION_FEDERATION_PROVIDER_CONTROLS = "federationProviderControls"
        private const val COLLECTION_CHALLENGES = "challenges"
        private const val COLLECTION_RECOVERY_CODES = "recoveryCodes"
        private const val COLLECTION_DEVICE_GRANTS = "deviceGrants"
        private const val COLLECTION_DEVICE_TOKEN_FAMILIES = "deviceTokenFamilies"
        private const val COLLECTION_DEVICE_ACCESS_TOKENS = "deviceAccessTokens"
        private const val COLLECTION_DEVICE_REFRESH_TOKENS = "deviceRefreshTokens"
        private const val COLLECTION_REPLAY_RECEIPTS = "replayReceipts"
        private const val COLLECTION_AUDIT_EVENTS = "auditEvents"
        private const val COLLECTION_SCIM_GROUPS = "scimGroups"
        private const val COLLECTION_SCIM_RECEIPTS = "scimReceipts"
        private const val COLLECTION_SCIM_BATCH_RECEIPTS = "scimBatchReceipts"
        private const val COLLECTION_UNIQUE = "unique"

        private const val UNIQUE_EMAIL = "email"
        private const val UNIQUE_WEBAUTHN_ID = "webauthn-id"
        private const val UNIQUE_MEMBERSHIP = "membership"
        private const val UNIQUE_ORGANIZATION_SLUG = "organization-slug"
        private const val UNIQUE_INVITATION_DIGEST = "invitation-digest"
        private const val UNIQUE_PENDING_INVITATION = "pending-invitation"
        private const val UNIQUE_SERVICE_PREFIX = "service-prefix"
        private const val UNIQUE_SERVICE_DIGEST = "service-digest"
        private const val UNIQUE_EXTERNAL_IDENTITY = "external-identity"
        private const val UNIQUE_FEDERATION_PROVIDER_ROUTE = "federation-provider-route"
        private const val UNIQUE_FEDERATION_PROVIDER_STORAGE_KEY = "federation-provider-storage-key"
        private const val UNIQUE_RECOVERY_SELECTOR = "recovery-selector"
        private const val UNIQUE_RECOVERY_DIGEST = "recovery-digest"
        private const val UNIQUE_CHALLENGE_DIGEST = "challenge-digest"
        private const val UNIQUE_SESSION_DIGEST = "session-digest"
        private const val UNIQUE_DEVICE_CODE = "device-code"
        private const val UNIQUE_USER_CODE = "user-code"
        private const val UNIQUE_DEVICE_ACCESS_SELECTOR = "device-access-selector"
        private const val UNIQUE_DEVICE_REFRESH_SELECTOR = "device-refresh-selector"
        private const val UNIQUE_DEVICE_TOKEN_SELECTOR = "device-token-selector"
        private const val UNIQUE_DEVICE_TOKEN_DIGEST = "device-token-digest"
        private const val UNIQUE_REPLAY_ASSERTION = "replay-assertion"

        private const val FIELD_PAYLOAD = "payload"
        private const val FIELD_ENTITY_ID = "entityId"
        private const val FIELD_SLUG = "slug"
        private const val FIELD_ENVIRONMENT = "environment"
        private const val FIELD_NAMESPACE = "namespace"
        private const val FIELD_SCHEMA_VERSION = "schemaVersion"
        private const val FIELD_KIND = "kind"
        private const val FIELD_STATE = "state"
        private const val FIELD_USER_ID = "userId"
        private const val FIELD_ORGANIZATION_ID = "organizationId"
        private const val FIELD_SERVICE_IDENTITY_ID = "serviceIdentityId"
        private const val FIELD_PROVIDER = "provider"
        private const val FIELD_PROVIDER_ID = "providerId"
        private const val FIELD_PROVIDER_KIND = "providerKind"
        private const val FIELD_STORAGE_KEY = "storageKey"
        private const val FIELD_PROVIDER_SESSION_EPOCH = "providerSessionEpoch"
        private const val FIELD_VERSION = "version"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val FIELD_PUBLIC_PREFIX = "publicPrefix"
        private const val FIELD_NORMALIZED_EMAIL = "normalizedEmail"
        private const val FIELD_GENERATION = "generation"
        private const val FIELD_ROLE = "role"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_LAST_USED_AT = "lastUsedAt"
        private const val FIELD_OCCURRED_AT = "occurredAt"
        private const val FIELD_ACTION = "action"
        private const val FIELD_FAMILY_ID = "familyId"

        private val COLLECTION_ID = Regex("[A-Za-z][A-Za-z0-9]{0,127}")
        private val DOCUMENT_ID = Regex("[^/]{1,1500}")
    }
}
