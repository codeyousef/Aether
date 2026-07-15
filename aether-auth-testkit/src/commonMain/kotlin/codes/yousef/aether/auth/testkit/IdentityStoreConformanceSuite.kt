package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Storage-adapter contract scenarios shared by the in-memory, PostgreSQL, and Firestore suites.
 *
 * The suite intentionally exercises only [IdentityStore]. It does not inspect adapter internals,
 * open database transactions, or depend on provider-specific test hooks. Callers must initialize
 * an empty or isolated adapter namespace before invoking [runAll]. The suite creates a dedicated
 * user and organization, so an adapter namespace may already contain a bootstrap identity.
 */
class IdentityStoreConformanceSuite(
    private val store: IdentityStore,
    private val namespace: String
) {
    init {
        require(namespace.length in 1..40 && namespace.all { it.isLetterOrDigit() || it == '-' }) {
            "Conformance namespace must contain 1..40 letters, digits, or hyphens"
        }
    }

    suspend fun runAll(): IdentityStoreConformanceReport {
        val context = createTenant()
        challengeSingleConsumption(context)
        credentialUniqueness(context)
        val currentUser = sessionEpochAndRevocation(context)
        lastOwnerProtection(context)
        recoveryReuseAndGeneration(context, currentUser)
        deviceTokenReplayAndFamilyRevocation(context)
        serviceCredentialTransitions(context)
        federationProviderLifecycle(context)
        federationLinkConflictsAndReplayReceipts(context)
        federationJitAtomicity(context)
        administrativeRecoveryActivation(currentUser)
        return IdentityStoreConformanceReport(
            namespace = namespace,
            cases = IdentityStoreConformanceCase.entries.toSet()
        )
    }

    private suspend fun createTenant(): ConformanceContext {
        val user = IdentityFixtures.user(IdentityFixtures.userId(label("user")))
        val userMutation = ScimMutation(
            operationId = IdentityFixtures.scimOperationId(label("create-user")),
            provider = provider("setup"),
            type = ScimMutationType.UPSERT_USER,
            externalSubject = ExternalSubject("subject-${label("user")}"),
            user = user,
            occurredAt = time(0)
        )
        success(
            "create conformance user",
            store.applyScimMutation(
                ApplyScimMutationCommand(
                    mutation = userMutation,
                    auditEvent = audit(
                        key = "create-user",
                        action = AuditAction.SCIM_MUTATION_APPLIED,
                        targetType = AuditTargetType.USER,
                        targetId = user.id.value
                    )
                )
            )
        )

        val organization = IdentityFixtures.organization(
            id = IdentityFixtures.organizationId(label("organization")),
            slug = slug()
        )
        val owner = IdentityFixtures.membership(
            id = IdentityFixtures.membershipId(label("owner")),
            organizationId = organization.id,
            userId = user.id,
            role = OrganizationRole.OWNER
        )
        success(
            "create conformance organization",
            store.createOrganization(
                CreateOrganizationCommand(
                    organization = organization,
                    ownerMembership = owner,
                    auditEvent = audit(
                        key = "create-organization",
                        action = AuditAction.ORGANIZATION_CREATED,
                        targetType = AuditTargetType.ORGANIZATION,
                        targetId = organization.id.value,
                        organizationId = organization.id
                    )
                )
            )
        )
        return ConformanceContext(user, organization, owner)
    }

    private suspend fun challengeSingleConsumption(context: ConformanceContext) {
        val challenge = IdentityFixtures.challenge(
            id = IdentityFixtures.challengeId(label("challenge-race")),
            userId = context.user.id
        )
        success("create challenge", store.createChallenge(CreateChallengeCommand(challenge)))
        val command = ConsumeChallengeCommand(
            challengeId = challenge.id,
            expectedVersion = challenge.version,
            terminalState = ChallengeState.CONSUMED,
            consumedAt = time(1_000)
        )
        val results = race { store.consumeChallenge(command) }
        exactlyOneWinner(
            case = IdentityStoreConformanceCase.CHALLENGE_SINGLE_CONSUMPTION,
            results = results,
            allowedFailureCodes = setOf(
                IdentityStoreErrorCode.VERSION_CONFLICT,
                IdentityStoreErrorCode.CHALLENGE_NOT_PENDING
            )
        )
        val stored = present("read consumed challenge", store.findChallenge(challenge.id))
        equal(
            IdentityStoreConformanceCase.CHALLENGE_SINGLE_CONSUMPTION,
            ChallengeState.CONSUMED,
            stored.state,
            "challenge terminal state"
        )
    }

    private suspend fun credentialUniqueness(context: ConformanceContext) {
        val firstChallenge = IdentityFixtures.challenge(
            id = IdentityFixtures.challengeId(label("credential-challenge-one")),
            purpose = ChallengePurpose.WEBAUTHN_REGISTRATION,
            userId = context.user.id
        )
        val secondChallenge = IdentityFixtures.challenge(
            id = IdentityFixtures.challengeId(label("credential-challenge-two")),
            purpose = ChallengePurpose.WEBAUTHN_REGISTRATION,
            userId = context.user.id
        )
        success("create first registration challenge", store.createChallenge(CreateChallengeCommand(firstChallenge)))
        success("create second registration challenge", store.createChallenge(CreateChallengeCommand(secondChallenge)))

        val firstCredential = IdentityFixtures.credential(
            id = IdentityFixtures.credentialId(label("credential-one")),
            userId = context.user.id
        )
        success(
            "register first credential",
            store.completeCredentialRegistration(
                CompleteCredentialRegistrationCommand(
                    challengeId = firstChallenge.id,
                    expectedChallengeVersion = firstChallenge.version,
                    credential = firstCredential,
                    auditEvent = audit(
                        key = "credential-one",
                        action = AuditAction.CREDENTIAL_REGISTERED,
                        targetType = AuditTargetType.CREDENTIAL,
                        targetId = firstCredential.id.value
                    ),
                    rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(
                        firstChallenge.id,
                        id = IdentityFixtures.auditEventId(label("audit-credential-one-rejected"))
                    )
                )
            )
        )

        val duplicate = IdentityFixtures.credential(
            id = IdentityFixtures.credentialId(label("credential-duplicate")),
            webAuthnId = firstCredential.webAuthnId,
            userId = context.user.id
        )
        val rejected = success(
            "terminally reject duplicate credential",
            store.completeCredentialRegistration(
                CompleteCredentialRegistrationCommand(
                    challengeId = secondChallenge.id,
                    expectedChallengeVersion = secondChallenge.version,
                    credential = duplicate,
                    auditEvent = audit(
                        key = "credential-duplicate",
                        action = AuditAction.CREDENTIAL_REGISTERED,
                        targetType = AuditTargetType.CREDENTIAL,
                        targetId = duplicate.id.value
                    ),
                    rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(
                        secondChallenge.id,
                        id = IdentityFixtures.auditEventId(label("audit-credential-duplicate-rejected"))
                    )
                )
            )
        )
        equal(
            IdentityStoreConformanceCase.CREDENTIAL_UNIQUENESS,
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
            rejected.rejection?.error?.code,
            "duplicate credential rejection code"
        )
        equal(
            IdentityStoreConformanceCase.CREDENTIAL_UNIQUENESS,
            null,
            success("read rejected credential", store.findCredential(duplicate.id)),
            "duplicate credential rollback"
        )
        equal(
            IdentityStoreConformanceCase.CREDENTIAL_UNIQUENESS,
            ChallengeState.FAILED,
            present("read duplicate registration challenge", store.findChallenge(secondChallenge.id)).state,
            "failed registration must terminally consume its challenge"
        )
    }

    private suspend fun sessionEpochAndRevocation(context: ConformanceContext): User {
        val retained = IdentityFixtures.session(
            id = IdentityFixtures.sessionId(label("session-retained")),
            userId = context.user.id,
            userSessionEpoch = context.user.sessionEpoch,
            createdAt = time(2_000)
        )
        val revoked = IdentityFixtures.session(
            id = IdentityFixtures.sessionId(label("session-revoked")),
            userId = context.user.id,
            userSessionEpoch = context.user.sessionEpoch,
            createdAt = time(2_000)
        )
        listOf(retained, revoked).forEachIndexed { index, session ->
            success(
                "create session $index",
                store.createSession(
                    CreateSessionCommand(
                        session = session,
                        auditEvent = audit(
                            key = "session-create-$index",
                            action = AuditAction.SESSION_CREATED,
                            targetType = AuditTargetType.SESSION,
                            targetId = session.id.value
                        )
                    )
                )
            )
        }
        val touchedAt = time(2_500)
        val touchedIdleExpiresAt = time(3_602_500)
        val touchCommand = TouchIdentitySessionCommand(
            sessionId = retained.id,
            expectedVersion = retained.version,
            lastUsedAt = touchedAt,
            idleExpiresAt = touchedIdleExpiresAt
        )
        exactlyOneWinner(
            case = IdentityStoreConformanceCase.SESSION_IDLE_TOUCH,
            results = race { store.touchIdentitySession(touchCommand) },
            allowedFailureCodes = setOf(IdentityStoreErrorCode.VERSION_CONFLICT),
            requireRetryableFailure = true
        )
        val touched = present("read touched session", store.findSession(retained.id))
        equal(
            IdentityStoreConformanceCase.SESSION_IDLE_TOUCH,
            retained.version + 1,
            touched.version,
            "touched session version"
        )
        equal(
            IdentityStoreConformanceCase.SESSION_IDLE_TOUCH,
            touchedAt,
            touched.lastUsedAt,
            "touched session last-used time"
        )
        equal(
            IdentityStoreConformanceCase.SESSION_IDLE_TOUCH,
            touchedIdleExpiresAt,
            touched.idleExpiresAt,
            "touched session idle expiration"
        )
        val command = RevokeUserSessionsCommand(
            userId = context.user.id,
            expectedUserVersion = context.user.version,
            expectedSessionEpoch = context.user.sessionEpoch,
            newSessionEpoch = context.user.sessionEpoch + 1,
            exceptSessionId = retained.id,
            revokedAt = time(3_000),
            reasonCode = "conformance_epoch_change",
            auditEvent = audit(
                key = "session-epoch",
                action = AuditAction.SESSION_REVOKED,
                targetType = AuditTargetType.USER,
                targetId = context.user.id.value
            )
        )
        val commit = success("advance user session epoch", store.revokeUserSessions(command))
        equal(
            IdentityStoreConformanceCase.SESSION_EPOCH_AND_REVOCATION,
            1L,
            commit.user.sessionEpoch,
            "user session epoch"
        )
        containsExactly(
            IdentityStoreConformanceCase.SESSION_EPOCH_AND_REVOCATION,
            setOf(revoked.id),
            commit.revokedSessionIds.toSet(),
            "revoked session IDs"
        )
        val retainedStored = present("read retained session", store.findSession(retained.id))
        equal(
            IdentityStoreConformanceCase.SESSION_EPOCH_AND_REVOCATION,
            SessionState.ACTIVE,
            retainedStored.state,
            "excepted session state"
        )
        equal(
            IdentityStoreConformanceCase.SESSION_EPOCH_AND_REVOCATION,
            1L,
            retainedStored.userSessionEpoch,
            "excepted session epoch"
        )
        equal(
            IdentityStoreConformanceCase.SESSION_EPOCH_AND_REVOCATION,
            SessionState.REVOKED,
            present("read revoked session", store.findSession(revoked.id)).state,
            "revoked session state"
        )
        val stale = failure(
            IdentityStoreConformanceCase.CONCURRENT_RETRY_SEMANTICS,
            store.revokeUserSessions(command),
            IdentityStoreErrorCode.VERSION_CONFLICT
        )
        truth(
            IdentityStoreConformanceCase.CONCURRENT_RETRY_SEMANTICS,
            stale.error.retryable,
            "a stale epoch compare-and-set must be retryable"
        )
        return commit.user
    }

    private suspend fun lastOwnerProtection(context: ConformanceContext) {
        val replacement = context.owner.copy(
            role = OrganizationRole.ADMIN,
            version = context.owner.version + 1,
            updatedAt = time(4_000)
        )
        failure(
            IdentityStoreConformanceCase.LAST_OWNER_PROTECTION,
            store.mutateMembership(
                MutateMembershipCommand(
                    membershipId = context.owner.id,
                    expectedVersion = context.owner.version,
                    replacement = replacement,
                    auditEvent = audit(
                        key = "last-owner",
                        action = AuditAction.MEMBERSHIP_CHANGED,
                        targetType = AuditTargetType.MEMBERSHIP,
                        targetId = context.owner.id.value,
                        organizationId = context.organization.id
                    )
                )
            ),
            IdentityStoreErrorCode.LAST_OWNER
        )
        equal(
            IdentityStoreConformanceCase.LAST_OWNER_PROTECTION,
            OrganizationRole.OWNER,
            present("read protected owner", store.findMembership(context.owner.id)).role,
            "last owner role after rejected demotion"
        )
    }

    private suspend fun recoveryReuseAndGeneration(context: ConformanceContext, user: User) {
        val generationZero = List(10) { index ->
            IdentityFixtures.recoveryCode(
                id = IdentityFixtures.recoveryCodeId(label("recovery-zero-$index")),
                userId = user.id,
                generation = 0
            )
        }
        success(
            "create recovery generation zero",
            store.replaceRecoveryCodes(
                ReplaceRecoveryCodesCommand(
                    userId = user.id,
                    expectedGeneration = null,
                    newGeneration = 0,
                    codes = generationZero,
                    auditEvent = audit(
                        key = "recovery-zero",
                        action = AuditAction.RECOVERY_CODES_REPLACED,
                        targetType = AuditTargetType.USER,
                        targetId = user.id.value
                    )
                )
            )
        )
        val consumedAt = time(5_000)
        val results = listOf("one", "two").map { suffix ->
            ConsumeRecoveryCodeCommand(
                recoveryCodeId = generationZero.first().id,
                expectedVersion = generationZero.first().version,
                consumedAt = consumedAt,
                recoverySession = IdentityFixtures.session(
                    id = IdentityFixtures.sessionId(label("recovery-session-$suffix")),
                    userId = user.id,
                    userSessionEpoch = user.sessionEpoch,
                    assurance = AuthenticationAssurance.RECOVERY,
                    authenticationMethod = SessionAuthenticationMethod.RECOVERY_CODE,
                    createdAt = consumedAt
                ),
                auditEvent = audit(
                    key = "recovery-use-$suffix",
                    action = AuditAction.RECOVERY_CODE_USED,
                    targetType = AuditTargetType.USER,
                    targetId = user.id.value
                )
            )
        }.let { commands ->
            raceIndexed { index -> store.consumeRecoveryCode(commands[index]) }
                .mapIndexed { index, commandResult -> index to commandResult }
        }
        val winners = results.filter { it.second is StoreResult.Success<*> }
        val losers = results.filter { it.second is StoreResult.Failure }
        equal(IdentityStoreConformanceCase.RECOVERY_REUSE_AND_GENERATION, 1, winners.size, "recovery race winners")
        equal(IdentityStoreConformanceCase.RECOVERY_REUSE_AND_GENERATION, 1, losers.size, "recovery race losers")
        val recoveryFailure = losers.single().second as StoreResult.Failure
        truth(
            IdentityStoreConformanceCase.RECOVERY_REUSE_AND_GENERATION,
            recoveryFailure.error.code == IdentityStoreErrorCode.RECOVERY_CODE_NOT_ACTIVE ||
                recoveryFailure.error.code == IdentityStoreErrorCode.VERSION_CONFLICT,
            "second recovery-code consumption returned ${recoveryFailure.error.code}"
        )
        equal(
            IdentityStoreConformanceCase.RECOVERY_REUSE_AND_GENERATION,
            RecoveryCodeState.CONSUMED,
            present(
                "read consumed recovery code",
                store.findRecoveryCodeBySelector(generationZero.first().publicSelector)
            ).state,
            "consumed recovery-code state"
        )

        val generationOne = List(10) { index ->
            IdentityFixtures.recoveryCode(
                id = IdentityFixtures.recoveryCodeId(label("recovery-one-$index")),
                userId = user.id,
                generation = 1
            ).copy(createdAt = time(6_000))
        }
        val replacement = success(
            "advance recovery generation",
            store.replaceRecoveryCodes(
                ReplaceRecoveryCodesCommand(
                    userId = user.id,
                    expectedGeneration = 0,
                    newGeneration = 1,
                    codes = generationOne,
                    auditEvent = audit(
                        key = "recovery-one",
                        action = AuditAction.RECOVERY_CODES_REPLACED,
                        targetType = AuditTargetType.USER,
                        targetId = user.id.value,
                        occurredAt = time(6_000)
                    )
                )
            )
        )
        equal(
            IdentityStoreConformanceCase.RECOVERY_REUSE_AND_GENERATION,
            1L,
            replacement.generation,
            "replacement recovery generation"
        )
        val stored = success("list recovery generations", store.listRecoveryCodesForUser(user.id))
        equal(
            IdentityStoreConformanceCase.RECOVERY_REUSE_AND_GENERATION,
            10,
            stored.count { it.generation == 0L && it.state != RecoveryCodeState.ACTIVE },
            "inactive prior-generation recovery codes"
        )
        equal(
            IdentityStoreConformanceCase.RECOVERY_REUSE_AND_GENERATION,
            generationOne.map { it.id }.toSet(),
            stored.filter { it.generation == 1L && it.state == RecoveryCodeState.ACTIVE }.map { it.id }.toSet(),
            "active replacement recovery codes"
        )
    }

    private suspend fun deviceTokenReplayAndFamilyRevocation(context: ConformanceContext) {
        val pending = IdentityFixtures.deviceGrant(
            id = IdentityFixtures.deviceGrantId(label("device-grant"))
        )
        val create = CompareAndSetDeviceGrantCommand(
            expectedVersion = null,
            replacement = pending,
            auditEvent = audit(
                key = "device-create",
                action = AuditAction.DEVICE_GRANT_CHANGED,
                targetType = AuditTargetType.DEVICE_GRANT,
                targetId = pending.id.value,
                organizationId = context.organization.id
            )
        )
        val creationRace = race { store.compareAndSetDeviceGrant(create) }
        exactlyOneWinner(
            case = IdentityStoreConformanceCase.CONCURRENT_RETRY_SEMANTICS,
            results = creationRace,
            allowedFailureCodes = setOf(IdentityStoreErrorCode.VERSION_CONFLICT),
            requireRetryableFailure = true
        )

        val authorizedAt = time(7_000)
        val authorized = pending.copy(
            approvedCapabilities = pending.requestedCapabilities,
            state = DeviceGrantState.AUTHORIZED,
            userId = context.user.id,
            organizationId = context.organization.id,
            membershipId = context.owner.id,
            membershipVersion = context.owner.version,
            authorizedByUserId = context.user.id,
            version = pending.version + 1,
            authorizedAt = authorizedAt
        )
        success(
            "authorize device grant",
            store.compareAndSetDeviceGrant(
                CompareAndSetDeviceGrantCommand(
                    expectedVersion = pending.version,
                    replacement = authorized,
                    auditEvent = audit(
                        key = "device-authorize",
                        action = AuditAction.DEVICE_GRANT_CHANGED,
                        targetType = AuditTargetType.DEVICE_GRANT,
                        targetId = pending.id.value,
                        organizationId = context.organization.id,
                        occurredAt = authorizedAt
                    )
                )
            )
        )

        val exchangedAt = time(8_000)
        val family = DeviceTokenFamily(
            id = IdentityFixtures.deviceTokenFamilyId(label("device-family")),
            deviceGrantId = pending.id,
            clientId = pending.clientId,
            userId = context.user.id,
            organizationId = context.organization.id,
            membershipId = context.owner.id,
            membershipVersion = context.owner.version,
            capabilities = authorized.approvedCapabilities,
            createdAt = exchangedAt,
            expiresAt = time(500_000)
        )
        val access = accessToken("access-one", family.id, exchangedAt)
        val refresh = refreshToken("refresh-one", family.id, exchangedAt, 0)
        val staleFamily = family.copy(
            id = IdentityFixtures.deviceTokenFamilyId(label("device-family-stale-membership")),
            membershipVersion = family.membershipVersion + 1
        )
        failure(
            IdentityStoreConformanceCase.DEVICE_TOKEN_REPLAY_AND_FAMILY_REVOCATION,
            store.exchangeDeviceGrant(
                ExchangeDeviceGrantCommand(
                    deviceGrantId = pending.id,
                    expectedDeviceGrantVersion = authorized.version,
                    family = staleFamily,
                    accessToken = accessToken("access-stale-membership", staleFamily.id, exchangedAt),
                    refreshToken = refreshToken("refresh-stale-membership", staleFamily.id, exchangedAt, 0),
                    exchangedAt = exchangedAt,
                    auditEvent = audit(
                        key = "device-exchange-stale-membership",
                        action = AuditAction.DEVICE_TOKEN_ISSUED,
                        targetType = AuditTargetType.DEVICE_GRANT,
                        targetId = pending.id.value,
                        organizationId = context.organization.id,
                        occurredAt = exchangedAt
                    )
                )
            ),
            IdentityStoreErrorCode.INVALID_TRANSITION
        )
        val exchangeCommand = ExchangeDeviceGrantCommand(
            deviceGrantId = pending.id,
            expectedDeviceGrantVersion = authorized.version,
            family = family,
            accessToken = access,
            refreshToken = refresh,
            exchangedAt = exchangedAt,
            auditEvent = audit(
                key = "device-exchange",
                action = AuditAction.DEVICE_TOKEN_ISSUED,
                targetType = AuditTargetType.DEVICE_GRANT,
                targetId = pending.id.value,
                organizationId = context.organization.id,
                occurredAt = exchangedAt
            )
        )
        val exchangeRace = race { store.exchangeDeviceGrant(exchangeCommand) }
        exactlyOneWinner(
            case = IdentityStoreConformanceCase.DEVICE_TOKEN_REPLAY_AND_FAMILY_REVOCATION,
            results = exchangeRace,
            allowedFailureCodes = setOf(IdentityStoreErrorCode.VERSION_CONFLICT)
        )

        val rotatedAt = time(9_000)
        val nextAccess = accessToken("access-two", family.id, rotatedAt)
        val nextRefresh = refreshToken("refresh-two", family.id, rotatedAt, 1)
        val rotationCommand = RotateDeviceRefreshTokenCommand(
            refreshTokenId = refresh.id,
            expectedRefreshTokenVersion = refresh.version,
            expectedFamilyVersion = family.version,
            replacementAccessToken = nextAccess,
            replacementRefreshToken = nextRefresh,
            rotatedAt = rotatedAt,
            auditEvent = audit(
                key = "device-rotate",
                action = AuditAction.DEVICE_TOKEN_REFRESHED,
                targetType = AuditTargetType.DEVICE_GRANT,
                targetId = family.deviceGrantId.value,
                organizationId = context.organization.id,
                occurredAt = rotatedAt
            )
        )
        val rotation = success("rotate device refresh token", store.rotateDeviceRefreshToken(rotationCommand))
        equal(
            IdentityStoreConformanceCase.DEVICE_TOKEN_REPLAY_AND_FAMILY_REVOCATION,
            DeviceRefreshTokenState.ROTATED,
            rotation.previousRefreshToken.state,
            "consumed refresh-token state"
        )
        val replayFailure = failure(
            IdentityStoreConformanceCase.DEVICE_TOKEN_REPLAY_AND_FAMILY_REVOCATION,
            store.rotateDeviceRefreshToken(rotationCommand),
            IdentityStoreErrorCode.VERSION_CONFLICT,
            IdentityStoreErrorCode.INVALID_TRANSITION
        )
        truth(
            IdentityStoreConformanceCase.DEVICE_TOKEN_REPLAY_AND_FAMILY_REVOCATION,
            replayFailure.error.code == IdentityStoreErrorCode.VERSION_CONFLICT ||
                replayFailure.error.code == IdentityStoreErrorCode.INVALID_TRANSITION,
            "refresh replay must not succeed"
        )

        val membershipChangedAt = time(9_500)
        success(
            "advance device membership authorization version",
            store.mutateMembership(
                MutateMembershipCommand(
                    membershipId = context.owner.id,
                    expectedVersion = context.owner.version,
                    replacement = context.owner.copy(
                        version = context.owner.version + 1,
                        updatedAt = membershipChangedAt
                    ),
                    auditEvent = audit(
                        key = "device-membership-version",
                        action = AuditAction.MEMBERSHIP_CHANGED,
                        targetType = AuditTargetType.MEMBERSHIP,
                        targetId = context.owner.id.value,
                        organizationId = context.organization.id,
                        occurredAt = membershipChangedAt
                    )
                )
            )
        )
        failure(
            IdentityStoreConformanceCase.DEVICE_TOKEN_REPLAY_AND_FAMILY_REVOCATION,
            store.rotateDeviceRefreshToken(
                RotateDeviceRefreshTokenCommand(
                    refreshTokenId = nextRefresh.id,
                    expectedRefreshTokenVersion = nextRefresh.version,
                    expectedFamilyVersion = family.version,
                    replacementAccessToken = accessToken("access-stale-after-membership", family.id, membershipChangedAt),
                    replacementRefreshToken = refreshToken(
                        "refresh-stale-after-membership",
                        family.id,
                        membershipChangedAt,
                        2
                    ),
                    rotatedAt = membershipChangedAt,
                    auditEvent = audit(
                        key = "device-rotate-stale-membership",
                        action = AuditAction.DEVICE_TOKEN_REFRESHED,
                        targetType = AuditTargetType.DEVICE_GRANT,
                        targetId = family.deviceGrantId.value,
                        organizationId = context.organization.id,
                        occurredAt = membershipChangedAt
                    )
                )
            ),
            IdentityStoreErrorCode.INVALID_TRANSITION
        )

        val revokedAt = time(10_000)
        val revocation = success(
            "revoke replayed device family",
            store.revokeDeviceTokenFamily(
                RevokeDeviceTokenFamilyCommand(
                    familyId = family.id,
                    expectedFamilyVersion = family.version,
                    revokedAt = revokedAt,
                    reasonCode = "conformance_refresh_replay",
                    replayDetected = true,
                    auditEvent = audit(
                        key = "device-replay",
                        action = AuditAction.DEVICE_TOKEN_REPLAY_DETECTED,
                        targetType = AuditTargetType.DEVICE_GRANT,
                        targetId = family.id.value,
                        organizationId = context.organization.id,
                        occurredAt = revokedAt
                    )
                )
            )
        )
        equal(
            IdentityStoreConformanceCase.DEVICE_TOKEN_REPLAY_AND_FAMILY_REVOCATION,
            DeviceTokenFamilyState.REVOKED,
            revocation.family.state,
            "revoked token-family state"
        )
        equal(
            IdentityStoreConformanceCase.DEVICE_TOKEN_REPLAY_AND_FAMILY_REVOCATION,
            DeviceAccessTokenState.REVOKED,
            present("read replacement access token", store.findDeviceAccessTokenBySelector(nextAccess.publicSelector)).state,
            "replacement access-token state"
        )
        equal(
            IdentityStoreConformanceCase.DEVICE_TOKEN_REPLAY_AND_FAMILY_REVOCATION,
            DeviceRefreshTokenState.REVOKED,
            present("read replacement refresh token", store.findDeviceRefreshTokenBySelector(nextRefresh.publicSelector)).state,
            "replacement refresh-token state"
        )
    }

    private suspend fun serviceCredentialTransitions(context: ConformanceContext) {
        val identity = IdentityFixtures.serviceIdentity(
            id = IdentityFixtures.serviceIdentityId(label("service")),
            organizationId = context.organization.id
        )
        val initial = IdentityFixtures.serviceCredential(
            id = IdentityFixtures.serviceCredentialId(label("service-credential-one")),
            serviceIdentityId = identity.id
        )
        success(
            "create service identity",
            store.createServiceIdentity(
                CreateServiceIdentityCommand(
                    identity = identity,
                    initialCredential = initial,
                    auditEvent = audit(
                        key = "service-create",
                        action = AuditAction.SERVICE_IDENTITY_CREATED,
                        targetType = AuditTargetType.SERVICE_IDENTITY,
                        targetId = identity.id.value,
                        organizationId = context.organization.id
                    )
                )
            )
        )

        val rotatedAt = time(11_000)
        val replacement = IdentityFixtures.serviceCredential(
            id = IdentityFixtures.serviceCredentialId(label("service-credential-two")),
            serviceIdentityId = identity.id
        ).copy(createdAt = rotatedAt, expiresAt = time(86_411_000))
        val rotation = success(
            "rotate service credential",
            store.rotateServiceCredential(
                RotateServiceCredentialCommand(
                    credentialId = initial.id,
                    expectedVersion = initial.version,
                    replacement = replacement,
                    rotatedAt = rotatedAt,
                    auditEvent = audit(
                        key = "service-rotate",
                        action = AuditAction.SERVICE_CREDENTIAL_ROTATED,
                        targetType = AuditTargetType.SERVICE_CREDENTIAL,
                        targetId = initial.id.value,
                        organizationId = context.organization.id,
                        occurredAt = rotatedAt
                    )
                )
            )
        )
        equal(
            IdentityStoreConformanceCase.SERVICE_CREDENTIAL_TRANSITIONS,
            ServiceCredentialState.ROTATED,
            rotation.previous.state,
            "rotated service-credential state"
        )
        equal(
            IdentityStoreConformanceCase.SERVICE_CREDENTIAL_TRANSITIONS,
            replacement.id,
            rotation.previous.rotatedToId,
            "service-credential rotation link"
        )
        val revokedAt = time(12_000)
        val revoked = success(
            "revoke replacement service credential",
            store.revokeServiceCredential(
                RevokeServiceCredentialCommand(
                    credentialId = replacement.id,
                    expectedVersion = replacement.version,
                    revokedAt = revokedAt,
                    auditEvent = audit(
                        key = "service-revoke",
                        action = AuditAction.SERVICE_CREDENTIAL_REVOKED,
                        targetType = AuditTargetType.SERVICE_CREDENTIAL,
                        targetId = replacement.id.value,
                        organizationId = context.organization.id,
                        occurredAt = revokedAt
                    )
                )
            )
        )
        equal(
            IdentityStoreConformanceCase.SERVICE_CREDENTIAL_TRANSITIONS,
            ServiceCredentialState.REVOKED,
            revoked.state,
            "revoked service-credential state"
        )
        equal(
            IdentityStoreConformanceCase.SERVICE_CREDENTIAL_TRANSITIONS,
            ServiceCredentialState.REVOKED,
            present(
                "read revoked service credential",
                store.findServiceCredentialByPrefix(replacement.publicPrefix)
            ).state,
            "persisted service-credential state"
        )
    }

    private suspend fun federationProviderLifecycle(context: ConformanceContext) {
        val case = IdentityStoreConformanceCase.FEDERATION_PROVIDER_LIFECYCLE
        val kind = FederationProviderKind.OIDC
        val providerId = "lifecycle"
        val storageKey = IdentityFixtures.federationProviderStorageKey(kind, label(providerId))
        val initialLease = success(
            "acquire initial federation provider lease",
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organizationId = context.organization.id,
                    kind = kind,
                    providerId = providerId,
                    storageKey = storageKey,
                    acquiredAt = time(20_000)
                )
            )
        )
        equal(case, 0L, initialLease.sessionEpoch, "initial provider session epoch")
        equal(case, 0L, initialLease.version, "initial provider version")
        val currentUser = present("read current user for federated session", store.findUser(context.user.id))

        val remappedStorageKey = IdentityFixtures.federationProviderStorageKey(kind, label("$providerId-remap"))
        failure(
            case,
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    context.organization.id,
                    kind,
                    providerId,
                    remappedStorageKey,
                    time(20_010)
                )
            ),
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT
        )
        failure(
            case,
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    context.organization.id,
                    kind,
                    "lifecycle-remap",
                    storageKey,
                    time(20_020)
                )
            ),
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT
        )
        val initialControl = present(
            "read provider before remap CAS",
            store.findFederationProviderControl(context.organization.id, providerId)
        )
        val remappedAt = time(20_030)
        val remapReason = "conformance_provider_remap"
        failure(
            case,
            store.compareAndSetFederationProviderState(
                CompareAndSetFederationProviderStateCommand(
                    expectedVersion = initialControl.version,
                    replacement = initialControl.copy(
                        storageKey = remappedStorageKey,
                        state = FederationProviderState.DISABLED,
                        sessionEpoch = initialControl.sessionEpoch + 1,
                        version = initialControl.version + 1,
                        updatedAt = remappedAt,
                        disabledAt = remappedAt,
                        disabledReasonCode = remapReason
                    ),
                    auditEvent = audit(
                        key = "federation-provider-remap",
                        action = AuditAction.FEDERATION_PROVIDER_DISABLED,
                        targetType = AuditTargetType.FEDERATION_PROVIDER,
                        targetId = remappedStorageKey,
                        organizationId = context.organization.id,
                        occurredAt = remappedAt,
                        reasonCode = remapReason
                    )
                )
            ),
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT
        )

        val oldSession = IdentityFixtures.session(
            id = IdentityFixtures.sessionId(label("federation-old-session")),
            userId = context.user.id,
            userSessionEpoch = currentUser.sessionEpoch,
            assurance = AuthenticationAssurance.SESSION,
            authenticationMethod = SessionAuthenticationMethod.OIDC,
            federationOrganizationId = context.organization.id,
            federationProviderKey = storageKey,
            federationProviderSessionEpoch = initialLease.sessionEpoch,
            externalIdentityId = IdentityFixtures.externalIdentityId(label("federation-old-session")),
            createdAt = time(20_000)
        )
        success(
            "create initial federated session",
            store.createSession(
                CreateSessionCommand(
                    oldSession,
                    audit(
                        key = "federation-old-session-created",
                        action = AuditAction.SESSION_CREATED,
                        targetType = AuditTargetType.SESSION,
                        targetId = oldSession.id.value,
                        organizationId = context.organization.id,
                        occurredAt = time(20_000)
                    )
                )
            )
        )
        val touchedOld = success(
            "touch initial federated session",
            store.touchIdentitySession(
                TouchIdentitySessionCommand(
                    oldSession.id,
                    oldSession.version,
                    time(20_100),
                    oldSession.idleExpiresAt
                )
            )
        )

        val enabled = present(
            "read enabled federation provider",
            store.findFederationProviderControl(context.organization.id, providerId)
        )
        val disableReason = "conformance_provider_disabled"
        val disabledAt = time(21_000)
        val disabled = enabled.copy(
            state = FederationProviderState.DISABLED,
            sessionEpoch = enabled.sessionEpoch + 1,
            version = enabled.version + 1,
            updatedAt = disabledAt,
            disabledAt = disabledAt,
            disabledReasonCode = disableReason
        )
        success(
            "disable federation provider",
            store.compareAndSetFederationProviderState(
                CompareAndSetFederationProviderStateCommand(
                    expectedVersion = enabled.version,
                    replacement = disabled,
                    auditEvent = audit(
                        key = "federation-provider-disabled",
                        action = AuditAction.FEDERATION_PROVIDER_DISABLED,
                        targetType = AuditTargetType.FEDERATION_PROVIDER,
                        targetId = storageKey,
                        organizationId = context.organization.id,
                        occurredAt = disabledAt,
                        reasonCode = disableReason
                    )
                )
            )
        )
        failure(
            case,
            store.validateFederationProviderLease(initialLease),
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED
        )
        failure(
            case,
            store.touchIdentitySession(
                TouchIdentitySessionCommand(
                    oldSession.id,
                    touchedOld.version,
                    time(21_100),
                    touchedOld.idleExpiresAt
                )
            ),
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED
        )
        equal(
            case,
            SessionState.ACTIVE,
            present("read retained old federation session", store.findSession(oldSession.id)).state,
            "disable must not bulk-mutate session rows"
        )

        val enabledAt = time(22_000)
        val reenabled = disabled.copy(
            state = FederationProviderState.ENABLED,
            version = disabled.version + 1,
            updatedAt = enabledAt,
            disabledAt = null,
            disabledReasonCode = null
        )
        success(
            "re-enable federation provider",
            store.compareAndSetFederationProviderState(
                CompareAndSetFederationProviderStateCommand(
                    expectedVersion = disabled.version,
                    replacement = reenabled,
                    auditEvent = audit(
                        key = "federation-provider-enabled",
                        action = AuditAction.FEDERATION_PROVIDER_ENABLED,
                        targetType = AuditTargetType.FEDERATION_PROVIDER,
                        targetId = storageKey,
                        organizationId = context.organization.id,
                        occurredAt = enabledAt,
                        reasonCode = "conformance_provider_enabled"
                    )
                )
            )
        )
        failure(
            case,
            store.validateFederationProviderLease(initialLease),
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED
        )
        failure(
            case,
            store.touchIdentitySession(
                TouchIdentitySessionCommand(
                    oldSession.id,
                    touchedOld.version,
                    time(22_100),
                    touchedOld.idleExpiresAt
                )
            ),
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED
        )
        val currentLease = success(
            "acquire re-enabled federation provider lease",
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    context.organization.id,
                    kind,
                    providerId,
                    storageKey,
                    time(22_200)
                )
            )
        )
        equal(case, 1L, currentLease.sessionEpoch, "re-enabled provider session epoch")
        equal(case, 2L, currentLease.version, "re-enabled provider version")

        val newSession = IdentityFixtures.session(
            id = IdentityFixtures.sessionId(label("federation-new-session")),
            userId = context.user.id,
            userSessionEpoch = currentUser.sessionEpoch,
            assurance = AuthenticationAssurance.SESSION,
            authenticationMethod = SessionAuthenticationMethod.OIDC,
            federationOrganizationId = context.organization.id,
            federationProviderKey = storageKey,
            federationProviderSessionEpoch = currentLease.sessionEpoch,
            externalIdentityId = IdentityFixtures.externalIdentityId(label("federation-new-session")),
            createdAt = time(22_200)
        )
        success(
            "create current-epoch federated session",
            store.createSession(
                CreateSessionCommand(
                    newSession,
                    audit(
                        key = "federation-new-session-created",
                        action = AuditAction.SESSION_CREATED,
                        targetType = AuditTargetType.SESSION,
                        targetId = newSession.id.value,
                        organizationId = context.organization.id,
                        occurredAt = time(22_200)
                    )
                )
            )
        )
        success(
            "touch current-epoch federated session",
            store.touchIdentitySession(
                TouchIdentitySessionCommand(
                    newSession.id,
                    newSession.version,
                    time(22_300),
                    newSession.idleExpiresAt
                )
            )
        )

        federationProviderDisableCasRace(context, case)
        federationProviderInitialStartDisableRace(context, case)
    }

    private suspend fun federationProviderDisableCasRace(
        context: ConformanceContext,
        case: IdentityStoreConformanceCase
    ) {
        val kind = FederationProviderKind.SAML
        val providerId = "disable-race"
        val storageKey = IdentityFixtures.federationProviderStorageKey(kind, label(providerId))
        val lease = success(
            "acquire disable-race provider lease",
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    context.organization.id,
                    kind,
                    providerId,
                    storageKey,
                    time(23_000)
                )
            )
        )
        val current = present(
            "read disable-race provider",
            store.findFederationProviderControl(context.organization.id, providerId)
        )
        val changedAt = time(23_100)
        val reason = "conformance_disable_race"
        val command = CompareAndSetFederationProviderStateCommand(
            expectedVersion = current.version,
            replacement = current.copy(
                state = FederationProviderState.DISABLED,
                sessionEpoch = lease.sessionEpoch + 1,
                version = current.version + 1,
                updatedAt = changedAt,
                disabledAt = changedAt,
                disabledReasonCode = reason
            ),
            auditEvent = audit(
                key = "federation-disable-race",
                action = AuditAction.FEDERATION_PROVIDER_DISABLED,
                targetType = AuditTargetType.FEDERATION_PROVIDER,
                targetId = storageKey,
                organizationId = context.organization.id,
                occurredAt = changedAt,
                reasonCode = reason
            )
        )
        exactlyOneWinner(
            case,
            race { store.compareAndSetFederationProviderState(command) },
            setOf(IdentityStoreErrorCode.VERSION_CONFLICT),
            requireRetryableFailure = true
        )
        val events = success(
            "read provider lifecycle audit events",
            store.listAuditEventsForOrganization(OrganizationAuditEventPageRequest(context.organization.id, limit = 100))
        ).events
        equal(
            case,
            1,
            events.count {
                it.action == AuditAction.FEDERATION_PROVIDER_DISABLED && it.target?.id == storageKey
            },
            "disable race audit count"
        )
    }

    private suspend fun federationProviderInitialStartDisableRace(
        context: ConformanceContext,
        case: IdentityStoreConformanceCase
    ) {
        val kind = FederationProviderKind.OIDC
        val providerId = "initial-race"
        val storageKey = IdentityFixtures.federationProviderStorageKey(kind, label(providerId))
        val at = time(24_000)
        val reason = "conformance_initial_disable"
        val initialDisabled = FederationProviderControl(
            organizationId = context.organization.id,
            kind = kind,
            providerId = providerId,
            storageKey = storageKey,
            state = FederationProviderState.DISABLED,
            sessionEpoch = 1,
            createdAt = at,
            updatedAt = at,
            disabledAt = at,
            disabledReasonCode = reason
        )
        val disable = CompareAndSetFederationProviderStateCommand(
            expectedVersion = null,
            replacement = initialDisabled,
            auditEvent = audit(
                key = "federation-initial-disable",
                action = AuditAction.FEDERATION_PROVIDER_DISABLED,
                targetType = AuditTargetType.FEDERATION_PROVIDER,
                targetId = storageKey,
                organizationId = context.organization.id,
                occurredAt = at,
                reasonCode = reason
            )
        )
        val results: List<StoreResult<*>> = raceIndexed { index ->
            if (index == 0) {
                store.acquireFederationProviderLease(
                    AcquireFederationProviderLeaseCommand(
                        context.organization.id,
                        kind,
                        providerId,
                        storageKey,
                        at
                    )
                )
            } else {
                store.compareAndSetFederationProviderState(disable)
            }
        }
        exactlyOneWinner(
            case,
            results,
            setOf(
                IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
                IdentityStoreErrorCode.VERSION_CONFLICT
            )
        )
        var current = present(
            "read initial start-disable race provider",
            store.findFederationProviderControl(context.organization.id, providerId)
        )
        if (current.state == FederationProviderState.ENABLED) {
            val retryAt = time(24_100)
            current = success(
                "disable provider after acquire won initial race",
                store.compareAndSetFederationProviderState(
                    CompareAndSetFederationProviderStateCommand(
                        expectedVersion = current.version,
                        replacement = current.copy(
                            state = FederationProviderState.DISABLED,
                            sessionEpoch = current.sessionEpoch + 1,
                            version = current.version + 1,
                            updatedAt = retryAt,
                            disabledAt = retryAt,
                            disabledReasonCode = reason
                        ),
                        auditEvent = disable.auditEvent.copy(
                            id = IdentityFixtures.auditEventId(label("audit-federation-initial-disable-retry")),
                            occurredAt = retryAt
                        )
                    )
                )
            ).control
        }
        equal(case, FederationProviderState.DISABLED, current.state, "initial race final provider state")
        failure(
            case,
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    context.organization.id,
                    kind,
                    providerId,
                    storageKey,
                    time(24_200)
                )
            ),
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED
        )
    }

    private suspend fun federationLinkConflictsAndReplayReceipts(context: ConformanceContext) {
        val providerStorageKey = IdentityFixtures.federationProviderStorageKey(
            FederationProviderKind.OIDC,
            label("federation")
        )
        val lease = success(
            "acquire external-link provider lease",
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organizationId = context.organization.id,
                    kind = FederationProviderKind.OIDC,
                    providerId = "external-link",
                    storageKey = providerStorageKey,
                    acquiredAt = time(12_500)
                )
            )
        )
        val subject = ExternalSubject("subject-${label("federation")}")
        val identity = IdentityFixtures.externalIdentity(
            id = IdentityFixtures.externalIdentityId(label("external-one")),
            userId = context.user.id,
            provider = providerStorageKey,
            subject = subject
        )
        val linkReceipt = IdentityFixtures.replayReceipt(
            id = IdentityFixtures.replayReceiptId(label("link-receipt")),
            provider = providerStorageKey
        )
        success(
            "link external identity",
            store.linkExternalIdentity(
                LinkExternalIdentityCommand(
                    identity = identity,
                    replayReceipt = linkReceipt,
                    federationProviderLease = lease,
                    auditEvent = audit(
                        key = "external-link",
                        action = AuditAction.EXTERNAL_IDENTITY_LINKED,
                        targetType = AuditTargetType.EXTERNAL_IDENTITY,
                        targetId = identity.id.value,
                        organizationId = context.organization.id
                    )
                )
            )
        )
        equal(
            IdentityStoreConformanceCase.FEDERATION_LINK_CONFLICTS_AND_REPLAY_RECEIPTS,
            identity,
            present("read linked external identity", store.findExternalIdentity(providerStorageKey, subject)),
            "linked external identity"
        )
        failure(
            IdentityStoreConformanceCase.FEDERATION_LINK_CONFLICTS_AND_REPLAY_RECEIPTS,
            store.recordExternalIdentityReplay(RecordExternalIdentityReplayCommand(linkReceipt, lease)),
            IdentityStoreErrorCode.REPLAY_DETECTED
        )

        val secondUser = IdentityFixtures.user(IdentityFixtures.userId(label("external-conflict-user")))
        val secondUserMutation = ScimMutation(
            operationId = IdentityFixtures.scimOperationId(label("create-conflict-user")),
            provider = provider("conflict-user"),
            type = ScimMutationType.UPSERT_USER,
            externalSubject = ExternalSubject("subject-${label("conflict-user")}"),
            user = secondUser,
            occurredAt = time(13_000)
        )
        success(
            "create external-link conflict user",
            store.applyScimMutation(
                ApplyScimMutationCommand(
                    mutation = secondUserMutation,
                    auditEvent = audit(
                        key = "create-conflict-user",
                        action = AuditAction.SCIM_MUTATION_APPLIED,
                        targetType = AuditTargetType.USER,
                        targetId = secondUser.id.value,
                        occurredAt = time(13_000)
                    )
                )
            )
        )
        val conflictReceipt = IdentityFixtures.replayReceipt(
            id = IdentityFixtures.replayReceiptId(label("conflict-receipt")),
            provider = providerStorageKey
        ).copy(receivedAt = time(14_000), expiresAt = time(614_000))
        val conflict = IdentityFixtures.externalIdentity(
            id = IdentityFixtures.externalIdentityId(label("external-conflict")),
            userId = secondUser.id,
            provider = providerStorageKey,
            subject = subject
        ).copy(createdAt = time(14_000), updatedAt = time(14_000))
        failure(
            IdentityStoreConformanceCase.FEDERATION_LINK_CONFLICTS_AND_REPLAY_RECEIPTS,
            store.linkExternalIdentity(
                LinkExternalIdentityCommand(
                    identity = conflict,
                    replayReceipt = conflictReceipt,
                    federationProviderLease = lease,
                    auditEvent = audit(
                        key = "external-conflict",
                        action = AuditAction.EXTERNAL_IDENTITY_LINKED,
                        targetType = AuditTargetType.EXTERNAL_IDENTITY,
                        targetId = conflict.id.value,
                        organizationId = context.organization.id,
                        occurredAt = time(14_000)
                    )
                )
            ),
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT
        )
        success(
            "record receipt after rolled-back external-link conflict",
            store.recordExternalIdentityReplay(RecordExternalIdentityReplayCommand(conflictReceipt, lease))
        )
        failure(
            IdentityStoreConformanceCase.FEDERATION_LINK_CONFLICTS_AND_REPLAY_RECEIPTS,
            store.recordExternalIdentityReplay(RecordExternalIdentityReplayCommand(conflictReceipt, lease)),
            IdentityStoreErrorCode.REPLAY_DETECTED
        )

        val control = present(
            "read external-link provider control",
            store.findFederationProviderControl(context.organization.id, "external-link")
        )
        val disabledAt = time(15_000)
        val disableReason = "conformance_link_provider_disabled"
        val disabled = success(
            "disable external-link provider",
            store.compareAndSetFederationProviderState(
                CompareAndSetFederationProviderStateCommand(
                    expectedVersion = control.version,
                    replacement = control.copy(
                        state = FederationProviderState.DISABLED,
                        sessionEpoch = control.sessionEpoch + 1,
                        version = control.version + 1,
                        updatedAt = disabledAt,
                        disabledAt = disabledAt,
                        disabledReasonCode = disableReason
                    ),
                    auditEvent = audit(
                        key = "external-link-provider-disabled",
                        action = AuditAction.FEDERATION_PROVIDER_DISABLED,
                        targetType = AuditTargetType.FEDERATION_PROVIDER,
                        targetId = providerStorageKey,
                        organizationId = context.organization.id,
                        occurredAt = disabledAt,
                        reasonCode = disableReason
                    )
                )
            )
        ).control
        equal(
            IdentityStoreConformanceCase.FEDERATION_LINK_CONFLICTS_AND_REPLAY_RECEIPTS,
            identity,
            present(
                "read retained link while provider disabled",
                store.findExternalIdentity(providerStorageKey, subject)
            ),
            "provider disable must retain external links"
        )
        val enabledAt = time(15_100)
        success(
            "re-enable external-link provider",
            store.compareAndSetFederationProviderState(
                CompareAndSetFederationProviderStateCommand(
                    expectedVersion = disabled.version,
                    replacement = disabled.copy(
                        state = FederationProviderState.ENABLED,
                        version = disabled.version + 1,
                        updatedAt = enabledAt,
                        disabledAt = null,
                        disabledReasonCode = null
                    ),
                    auditEvent = audit(
                        key = "external-link-provider-enabled",
                        action = AuditAction.FEDERATION_PROVIDER_ENABLED,
                        targetType = AuditTargetType.FEDERATION_PROVIDER,
                        targetId = providerStorageKey,
                        organizationId = context.organization.id,
                        occurredAt = enabledAt,
                        reasonCode = "conformance_link_provider_enabled"
                    )
                )
            )
        )
        val currentLease = success(
            "acquire re-enabled external-link provider lease",
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    context.organization.id,
                    FederationProviderKind.OIDC,
                    "external-link",
                    providerStorageKey,
                    time(15_200)
                )
            )
        )
        failure(
            IdentityStoreConformanceCase.FEDERATION_LINK_CONFLICTS_AND_REPLAY_RECEIPTS,
            store.recordExternalIdentityReplay(RecordExternalIdentityReplayCommand(linkReceipt, currentLease)),
            IdentityStoreErrorCode.REPLAY_DETECTED
        )
    }

    private suspend fun federationJitAtomicity(context: ConformanceContext) {
        val case = IdentityStoreConformanceCase.FEDERATION_JIT_ATOMICITY
        val kind = FederationProviderKind.OIDC
        val providerId = "jit"
        val storageKey = IdentityFixtures.federationProviderStorageKey(kind, label(providerId))
        val lease = success(
            "acquire JIT federation provider lease",
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    context.organization.id,
                    kind,
                    providerId,
                    storageKey,
                    time(16_000)
                )
            )
        )

        fun provisioning(key: String, at: Instant): FederationJitProvisioning {
            val user = IdentityFixtures.user(IdentityFixtures.userId(label("jit-user-$key"))).copy(
                displayName = "Federated user",
                primaryEmail = null,
                createdAt = at,
                updatedAt = at,
                activatedAt = at
            )
            val membership = IdentityFixtures.membership(
                id = IdentityFixtures.membershipId(label("jit-membership-$key")),
                organizationId = context.organization.id,
                userId = user.id,
                role = OrganizationRole.VIEWER
            ).copy(createdAt = at, updatedAt = at)
            return FederationJitProvisioning(user, membership)
        }

        fun linkCommand(
            key: String,
            at: Instant,
            provisioning: FederationJitProvisioning,
            subject: ExternalSubject,
            receipt: ExternalIdentityReplayReceipt = IdentityFixtures.replayReceipt(
                id = IdentityFixtures.replayReceiptId(label("jit-receipt-$key")),
                provider = storageKey
            ).copy(receivedAt = at, expiresAt = Instant.fromEpochMilliseconds(at.toEpochMilliseconds() + 600_000))
        ): LinkExternalIdentityCommand {
            val identity = IdentityFixtures.externalIdentity(
                id = IdentityFixtures.externalIdentityId(label("jit-identity-$key")),
                userId = provisioning.user.id,
                provider = storageKey,
                subject = subject
            ).copy(
                email = context.user.primaryEmail,
                createdAt = at,
                updatedAt = at
            )
            return LinkExternalIdentityCommand(
                identity = identity,
                replayReceipt = receipt,
                federationProviderLease = lease,
                auditEvent = audit(
                    key = "jit-link-$key",
                    action = AuditAction.EXTERNAL_IDENTITY_LINKED,
                    targetType = AuditTargetType.EXTERNAL_IDENTITY,
                    targetId = identity.id.value,
                    organizationId = context.organization.id,
                    occurredAt = at
                ),
                jitProvisioning = provisioning
            )
        }

        val successAt = time(17_000)
        val successfulProvisioning = provisioning("success", successAt)
        val successfulSubject = ExternalSubject("subject-${label("jit-success")}")
        val existingUserBeforeJit = present(
            "read existing user before JIT",
            store.findUser(context.user.id)
        )
        val successfulCommand = linkCommand(
            "success",
            successAt,
            successfulProvisioning,
            successfulSubject
        )
        val commit = success("atomically JIT-link external identity", store.linkExternalIdentity(successfulCommand))
        equal(case, successfulProvisioning.user, commit.provisionedUser, "JIT commit user")
        equal(case, successfulProvisioning.membership, commit.provisionedMembership, "JIT commit membership")
        equal(
            case,
            existingUserBeforeJit,
            present("read pre-existing same-email user", store.findUser(context.user.id)),
            "JIT must not merge or mutate an existing same-email user"
        )
        equal(
            case,
            null,
            successfulProvisioning.user.primaryEmail,
            "JIT user email identity key"
        )

        val conflictAt = time(18_000)
        val conflictProvisioning = provisioning("conflict", conflictAt)
        val conflictCommand = linkCommand(
            "conflict",
            conflictAt,
            conflictProvisioning,
            successfulSubject
        )
        failure(case, store.linkExternalIdentity(conflictCommand), IdentityStoreErrorCode.UNIQUE_CONSTRAINT)
        equal(case, null, success("read rolled-back JIT conflict user", store.findUser(conflictProvisioning.user.id)),
            "JIT conflict user rollback")
        equal(case, null, success("read rolled-back JIT conflict membership",
            store.findMembership(conflictProvisioning.membership.id)), "JIT conflict membership rollback")

        val replayAt = time(19_000)
        val replayProvisioning = provisioning("replay", replayAt)
        val replayReceipt = IdentityFixtures.replayReceipt(
            id = IdentityFixtures.replayReceiptId(label("jit-receipt-replay")),
            provider = storageKey
        ).copy(
            assertionDigest = successfulCommand.replayReceipt.assertionDigest,
            receivedAt = replayAt,
            expiresAt = time(619_000)
        )
        val replayCommand = linkCommand(
            "replay",
            replayAt,
            replayProvisioning,
            ExternalSubject("subject-${label("jit-replay")}"),
            replayReceipt
        )
        failure(case, store.linkExternalIdentity(replayCommand), IdentityStoreErrorCode.REPLAY_DETECTED)
        equal(case, null, success("read rolled-back JIT replay user", store.findUser(replayProvisioning.user.id)),
            "JIT replay user rollback")
        equal(case, null, success("read rolled-back JIT replay membership",
            store.findMembership(replayProvisioning.membership.id)), "JIT replay membership rollback")

        val current = present(
            "read JIT provider control",
            store.findFederationProviderControl(context.organization.id, providerId)
        )
        val disabledAt = time(20_000)
        val reason = "conformance_jit_provider_disabled"
        success(
            "disable JIT provider",
            store.compareAndSetFederationProviderState(
                CompareAndSetFederationProviderStateCommand(
                    current.version,
                    current.copy(
                        state = FederationProviderState.DISABLED,
                        sessionEpoch = current.sessionEpoch + 1,
                        version = current.version + 1,
                        updatedAt = disabledAt,
                        disabledAt = disabledAt,
                        disabledReasonCode = reason
                    ),
                    audit(
                        key = "jit-provider-disabled",
                        action = AuditAction.FEDERATION_PROVIDER_DISABLED,
                        targetType = AuditTargetType.FEDERATION_PROVIDER,
                        targetId = storageKey,
                        organizationId = context.organization.id,
                        occurredAt = disabledAt,
                        reasonCode = reason
                    )
                )
            )
        )
        val disabledAttemptAt = time(20_100)
        val disabledProvisioning = provisioning("disabled", disabledAttemptAt)
        val disabledCommand = linkCommand(
            "disabled",
            disabledAttemptAt,
            disabledProvisioning,
            ExternalSubject("subject-${label("jit-disabled")}")
        )
        failure(
            case,
            store.linkExternalIdentity(disabledCommand),
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED
        )
        equal(case, null, success("read rolled-back disabled-provider JIT user",
            store.findUser(disabledProvisioning.user.id)), "disabled-provider JIT user rollback")
        equal(case, null, success("read rolled-back disabled-provider JIT membership",
            store.findMembership(disabledProvisioning.membership.id)), "disabled-provider JIT membership rollback")
    }

    private suspend fun administrativeRecoveryActivation(user: User) {
        val challenge = IdentityFixtures.challenge(
            id = IdentityFixtures.challengeId(label("administrative-recovery-ticket")),
            purpose = ChallengePurpose.ACCOUNT_RECOVERY,
            userId = user.id
        )
        success(
            "create inactive administrative recovery ticket",
            store.createChallenge(CreateChallengeCommand(challenge))
        )
        val redeemedAt = time(16_000)
        val recoverySession = IdentityFixtures.session(
            id = IdentityFixtures.sessionId(label("administrative-recovery-session")),
            userId = user.id,
            userSessionEpoch = user.sessionEpoch,
            assurance = AuthenticationAssurance.RECOVERY,
            createdAt = redeemedAt
        )
        val usedAudit = audit(
            key = "administrative-recovery-used",
            action = AuditAction.RECOVERY_ADMIN_TICKET_USED,
            targetType = AuditTargetType.USER,
            targetId = user.id.value,
            occurredAt = redeemedAt
        )
        val inactiveRedemption = RedeemAdministrativeRecoveryTicketCommand(
            challengeId = challenge.id,
            expectedChallengeVersion = challenge.version,
            redeemedAt = redeemedAt,
            recoverySession = recoverySession,
            auditEvent = usedAudit
        )
        failure(
            IdentityStoreConformanceCase.ADMINISTRATIVE_RECOVERY_ACTIVATION,
            store.redeemAdministrativeRecoveryTicket(inactiveRedemption),
            IdentityStoreErrorCode.INVALID_TRANSITION
        )
        equal(
            IdentityStoreConformanceCase.ADMINISTRATIVE_RECOVERY_ACTIVATION,
            null,
            success("read rejected administrative recovery session", store.findSession(recoverySession.id)),
            "inactive ticket must not create a recovery session"
        )

        val activatedAt = time(15_000)
        val conflictingDeliveryAudit = audit(
            key = "administrative-recovery-delivery-conflict",
            action = AuditAction.RECOVERY_ADMIN_TICKET_DELIVERED,
            targetType = AuditTargetType.USER,
            targetId = user.id.value,
            occurredAt = activatedAt
        )
        success(
            "reserve conflicting administrative recovery audit",
            store.appendAuditEvent(conflictingDeliveryAudit)
        )
        failure(
            IdentityStoreConformanceCase.ADMINISTRATIVE_RECOVERY_ACTIVATION,
            store.activateAdministrativeRecoveryTicket(
                ActivateAdministrativeRecoveryTicketCommand(
                    challengeId = challenge.id,
                    expectedChallengeVersion = challenge.version,
                    activatedAt = activatedAt,
                    auditEvent = conflictingDeliveryAudit
                )
            ),
            IdentityStoreErrorCode.ALREADY_EXISTS,
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT
        )
        val stillInactive = present(
            "read ticket after failed delivery audit",
            store.findChallenge(challenge.id)
        )
        equal(
            IdentityStoreConformanceCase.ADMINISTRATIVE_RECOVERY_ACTIVATION,
            null,
            stillInactive.activatedAt,
            "failed delivery audit must not activate a ticket"
        )
        equal(
            IdentityStoreConformanceCase.ADMINISTRATIVE_RECOVERY_ACTIVATION,
            challenge.version,
            stillInactive.version,
            "failed delivery audit must roll back the challenge version"
        )
        val deliveryAudit = audit(
            key = "administrative-recovery-delivered",
            action = AuditAction.RECOVERY_ADMIN_TICKET_DELIVERED,
            targetType = AuditTargetType.USER,
            targetId = user.id.value,
            occurredAt = activatedAt
        )
        val activation = success(
            "activate delivered administrative recovery ticket",
            store.activateAdministrativeRecoveryTicket(
                ActivateAdministrativeRecoveryTicketCommand(
                    challengeId = challenge.id,
                    expectedChallengeVersion = challenge.version,
                    activatedAt = activatedAt,
                    auditEvent = deliveryAudit
                )
            )
        )
        equal(
            IdentityStoreConformanceCase.ADMINISTRATIVE_RECOVERY_ACTIVATION,
            activatedAt,
            activation.challenge.activatedAt,
            "ticket activation timestamp"
        )
        equal(
            IdentityStoreConformanceCase.ADMINISTRATIVE_RECOVERY_ACTIVATION,
            deliveryAudit,
            activation.auditEvent,
            "delivery audit must commit with activation"
        )
        success(
            "redeem activated administrative recovery ticket",
            store.redeemAdministrativeRecoveryTicket(
                inactiveRedemption.copy(expectedChallengeVersion = activation.challenge.version)
            )
        )
    }

    private fun accessToken(key: String, familyId: DeviceTokenFamilyId, createdAt: Instant): DeviceAccessToken =
        DeviceAccessToken(
            id = IdentityFixtures.deviceAccessTokenId(label(key)),
            familyId = familyId,
            publicSelector = "access_${IdentityFixtures.canonicalUuidV7(key.hashCode().toLong() and 0xFFFF_FFFFL).takeLast(12)}",
            secretDigest = IdentityFixtures.digest(label("secret-$key")),
            createdAt = createdAt,
            expiresAt = time(200_000)
        )

    private fun refreshToken(
        key: String,
        familyId: DeviceTokenFamilyId,
        createdAt: Instant,
        rotationCounter: Long
    ): DeviceRefreshToken = DeviceRefreshToken(
        id = IdentityFixtures.deviceRefreshTokenId(label(key)),
        familyId = familyId,
        publicSelector = "refresh_${IdentityFixtures.canonicalUuidV7(key.hashCode().toLong() and 0xFFFF_FFFFL).takeLast(12)}",
        secretDigest = IdentityFixtures.digest(label("secret-$key")),
        rotationCounter = rotationCounter,
        createdAt = createdAt,
        expiresAt = time(400_000)
    )

    private fun audit(
        key: String,
        action: AuditAction,
        targetType: AuditTargetType,
        targetId: String,
        organizationId: OrganizationId? = null,
        occurredAt: Instant = time(0),
        reasonCode: String? = null
    ): AuditEvent = AuditEvent(
        id = IdentityFixtures.auditEventId(label("audit-$key")),
        actor = AuditActor(AuditActorType.SYSTEM),
        organizationId = organizationId,
        action = action,
        target = AuditTarget(targetType, targetId),
        outcome = AuditOutcome.SUCCEEDED,
        reasonCode = reasonCode,
        occurredAt = occurredAt
    )

    private fun time(offsetMilliseconds: Long): Instant = IdentityFixtures.instant(offsetMilliseconds)
    private fun label(value: String): String = "$namespace-$value"
    private fun provider(value: String): String = "conformance:$namespace:$value"
    private fun slug(): String = "conformance-${namespace.lowercase()}".take(63).trimEnd('-')

    private suspend fun <T> race(block: suspend () -> StoreResult<T>): List<StoreResult<T>> = coroutineScope {
        listOf(async { block() }, async { block() }).awaitAll()
    }

    private suspend fun <T> raceIndexed(block: suspend (Int) -> T): List<T> = coroutineScope {
        listOf(async { block(0) }, async { block(1) }).awaitAll()
    }

    private fun <T> success(operation: String, result: StoreResult<T>): T = when (result) {
        is StoreResult.Success -> result.value
        is StoreResult.Failure -> fail(null, "$operation failed with ${result.error.code}")
    }

    private fun <T : Any> present(operation: String, result: StoreResult<T?>): T =
        success(operation, result) ?: fail(null, "$operation returned no value")

    private fun failure(
        case: IdentityStoreConformanceCase,
        result: StoreResult<*>,
        vararg expected: IdentityStoreErrorCode
    ): StoreResult.Failure {
        val actual = result as? StoreResult.Failure
            ?: fail(case, "expected failure ${expected.toSet()}, but the operation succeeded")
        if (actual.error.code !in expected.toSet()) {
            fail(case, "expected failure ${expected.toSet()}, got ${actual.error.code}")
        }
        return actual
    }

    private fun exactlyOneWinner(
        case: IdentityStoreConformanceCase,
        results: List<StoreResult<*>>,
        allowedFailureCodes: Set<IdentityStoreErrorCode>,
        requireRetryableFailure: Boolean = false
    ) {
        val successes = results.filter { it is StoreResult.Success<*> }
        val failures = results.filterIsInstance<StoreResult.Failure>()
        if (successes.size != 1 || failures.size != 1) {
            val failureSummary = failures.joinToString(prefix = "[", postfix = "]") {
                "${it.error.code}(retryable=${it.error.retryable})"
            }
            fail(
                case,
                "expected one race winner and one loser; got ${successes.size} successes and " +
                    "${failures.size} failures $failureSummary"
            )
        }
        val failure = failures.single()
        if (failure.error.code !in allowedFailureCodes) {
            fail(case, "race loser returned ${failure.error.code}, expected one of $allowedFailureCodes")
        }
        if (requireRetryableFailure && !failure.error.retryable) {
            fail(case, "race loser ${failure.error.code} was not marked retryable")
        }
    }

    private fun truth(case: IdentityStoreConformanceCase, condition: Boolean, message: String) {
        if (!condition) fail(case, message)
    }

    private fun equal(case: IdentityStoreConformanceCase, expected: Any?, actual: Any?, description: String) {
        if (expected != actual) fail(case, "$description: expected <$expected>, got <$actual>")
    }

    private fun containsExactly(
        case: IdentityStoreConformanceCase,
        expected: Set<*>,
        actual: Set<*>,
        description: String
    ) = equal(case, expected, actual, description)

    private fun fail(case: IdentityStoreConformanceCase?, message: String): Nothing {
        val prefix = case?.let { "[${it.name}] " }.orEmpty()
        throw IdentityStoreConformanceException("$prefix$message")
    }

    private data class ConformanceContext(
        val user: User,
        val organization: Organization,
        val owner: Membership
    )
}

enum class IdentityStoreConformanceCase {
    CHALLENGE_SINGLE_CONSUMPTION,
    CREDENTIAL_UNIQUENESS,
    SESSION_IDLE_TOUCH,
    SESSION_EPOCH_AND_REVOCATION,
    LAST_OWNER_PROTECTION,
    RECOVERY_REUSE_AND_GENERATION,
    DEVICE_TOKEN_REPLAY_AND_FAMILY_REVOCATION,
    SERVICE_CREDENTIAL_TRANSITIONS,
    FEDERATION_PROVIDER_LIFECYCLE,
    FEDERATION_LINK_CONFLICTS_AND_REPLAY_RECEIPTS,
    FEDERATION_JIT_ATOMICITY,
    ADMINISTRATIVE_RECOVERY_ACTIVATION,
    CONCURRENT_RETRY_SEMANTICS
}

data class IdentityStoreConformanceReport(
    val namespace: String,
    val cases: Set<IdentityStoreConformanceCase>
)

class IdentityStoreConformanceException(message: String) : IllegalStateException(message)
