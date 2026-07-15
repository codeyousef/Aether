package codes.yousef.aether.auth.firestore

import codes.yousef.aether.auth.*
import codes.yousef.aether.auth.testkit.IdentityFixtures
import codes.yousef.aether.auth.testkit.IdentityStoreConformanceCase
import codes.yousef.aether.auth.testkit.IdentityStoreConformanceSuite
import java.net.URI
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Release-gate coverage through the actual Firestore v1 REST emulator.
 *
 * Ordinary JVM test runs explicitly skip this class. The dedicated `firestoreEmulatorTest` task
 * enables it and therefore fails closed when [FIRESTORE_EMULATOR_HOST] is absent or unreachable.
 * Every run flushes a dedicated test project before and after the suite; production credentials
 * and non-loopback endpoints are rejected before any request is sent.
 */
class FirestoreIdentityStoreEmulatorTest {
    private val exchanges = ArrayDeque<String>()

    @Test
    @Timeout(180)
    fun realRestTransactionsPreserveIdentityAtomicity() = runBlocking {
        assumeTrue(
            System.getProperty(EMULATOR_GATE_PROPERTY) == "true",
            "Run :aether-auth-firestore:firestoreEmulatorTest to enable the real-emulator gate"
        )
        val endpoint = emulatorEndpoint()
        val projectId = emulatorProjectId()
        val namespace = uniqueNamespace()
        val runtime = jvmIdentityRuntime(
            secrets = IdentitySecretResolver { IdentitySecret.fromUtf8("emulator-gate-only-secret") },
            http = RecordingIdentityHttpClient(JvmIdentityHttpClient())
        )
        val config = FirestoreIdentityConfig(
            environment = IdentityEnvironment.TEST,
            namespace = namespace,
            projectId = projectId,
            apiBaseUrl = "${endpoint.apiBaseUrl}/v1",
            maximumTransactionAttempts = 10
        )
        val store = FirestoreIdentityStore(config, runtime, FirestoreEmulatorAccessTokenProvider)

        flushEmulator(runtime, endpoint, projectId)
        try {
            assertEquals(IdentityStoreErrorCode.NOT_FOUND, failure(store.initialize()).error.code)
            success(store.provisionEnvironmentMarker())
            success(store.provisionEnvironmentMarker())
            success(store.initialize())
            val conflictingConfig = config.copy(namespace = "other_$namespace")
            val conflictingStore = FirestoreIdentityStore(
                conflictingConfig,
                runtime,
                FirestoreEmulatorAccessTokenProvider
            )
            assertEquals(
                IdentityStoreErrorCode.INTERNAL,
                failure(conflictingStore.provisionEnvironmentMarker()).error.code
            )
            assertEquals(
                IdentityStoreErrorCode.INTERNAL,
                failure(conflictingStore.initialize()).error.code
            )

            val baseline = bootstrap(store)
            val conformance = IdentityStoreConformanceSuite(store, "firestore-real").runAll()
            assertTrue(IdentityStoreConformanceCase.FEDERATION_PROVIDER_LIFECYCLE in conformance.cases)
            assertTrue(IdentityStoreConformanceCase.FEDERATION_JIT_ATOMICITY in conformance.cases)
            verifyConcurrentChallengeConsumption(store)
            verifyCredentialUniquenessRollback(store, baseline.user)
            val touchedSession = verifySessionIdleRenewal(store, baseline.enrollmentSession)
            val currentUser = verifySessionRotationAndEpoch(store, baseline, touchedSession)
            verifyRecoveryCodeReuse(store, currentUser)
            verifyLastOwnerProtection(store, baseline.ownerMembership)
            verifyInvitationEnrollment(store, baseline.organization)
            verifyDeviceExchangeAndRefreshReplay(store, baseline)
            verifyScimIdempotency(store, baseline.organization)
        } finally {
            flushEmulator(runtime, endpoint, projectId)
        }
    }

    private suspend fun bootstrap(store: FirestoreIdentityStore): BootstrapIdentityCommit {
        val user = IdentityFixtures.user(IdentityFixtures.userId("emulator-owner"))
        val organization = IdentityFixtures.organization(
            id = IdentityFixtures.organizationId("emulator-organization"),
            slug = "emulator-organization"
        )
        val owner = IdentityFixtures.membership(
            id = IdentityFixtures.membershipId("emulator-owner-membership"),
            organizationId = organization.id,
            userId = user.id
        )
        val session = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("emulator-bootstrap-session"),
            userId = user.id,
            assurance = AuthenticationAssurance.RECOVERY,
            authenticationMethod = SessionAuthenticationMethod.BOOTSTRAP
        )
        val command = BootstrapIdentityCommand(
            bootstrapSecretDigest = SecretDigest(DigestAlgorithm.SHA256, "emulator-bootstrap-receipt"),
            user = user,
            organization = organization,
            ownerMembership = owner,
            enrollmentSession = session,
            auditEvent = audit(
                id = "audit-emulator-bootstrap",
                action = AuditAction.IDENTITY_BOOTSTRAPPED,
                targetType = AuditTargetType.USER,
                targetId = user.id.value,
                organizationId = organization.id
            )
        )
        return success(store.bootstrapIdentity(command)).value
    }

    private suspend fun verifyConcurrentChallengeConsumption(store: FirestoreIdentityStore) {
        val challenge = IdentityFixtures.challenge(
            id = IdentityFixtures.challengeId("emulator-concurrent-challenge"),
            userId = IdentityFixtures.userId("emulator-owner")
        )
        success(store.createChallenge(CreateChallengeCommand(challenge)))
        val command = ConsumeChallengeCommand(
            challengeId = challenge.id,
            expectedVersion = 0,
            terminalState = ChallengeState.CONSUMED,
            consumedAt = IdentityFixtures.instant(1_000)
        )
        val results = race { store.consumeChallenge(command) }
        assertEquals(1, results.count { it is StoreResult.Success })
        assertTrue(
            failure(results.single { it is StoreResult.Failure }).error.code in setOf(
                IdentityStoreErrorCode.VERSION_CONFLICT,
                IdentityStoreErrorCode.CHALLENGE_NOT_PENDING
            )
        )
        assertEquals(
            ChallengeState.CONSUMED,
            assertNotNull(success(store.findChallenge(challenge.id)).value).state
        )
    }

    private suspend fun verifyCredentialUniquenessRollback(
        store: FirestoreIdentityStore,
        user: User
    ) {
        val firstChallenge = IdentityFixtures.challenge(
            id = IdentityFixtures.challengeId("emulator-registration-one"),
            purpose = ChallengePurpose.WEBAUTHN_REGISTRATION,
            userId = user.id
        )
        val secondChallenge = IdentityFixtures.challenge(
            id = IdentityFixtures.challengeId("emulator-registration-two"),
            purpose = ChallengePurpose.WEBAUTHN_REGISTRATION,
            userId = user.id
        )
        success(store.createChallenge(CreateChallengeCommand(firstChallenge)))
        success(store.createChallenge(CreateChallengeCommand(secondChallenge)))
        val first = IdentityFixtures.credential(
            id = IdentityFixtures.credentialId("emulator-credential-one"),
            userId = user.id
        )
        success(
            store.completeCredentialRegistration(
                CompleteCredentialRegistrationCommand(
                    challengeId = firstChallenge.id,
                    expectedChallengeVersion = 0,
                    credential = first,
                    auditEvent = audit(
                        "audit-emulator-credential-one",
                        AuditAction.CREDENTIAL_REGISTERED,
                        AuditTargetType.CREDENTIAL,
                        first.id.value
                    ),
                    rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(firstChallenge.id)
                )
            )
        )
        val duplicate = IdentityFixtures.credential(
            id = IdentityFixtures.credentialId("emulator-credential-duplicate"),
            webAuthnId = first.webAuthnId,
            userId = user.id
        )
        val rejected = store.completeCredentialRegistration(
            CompleteCredentialRegistrationCommand(
                challengeId = secondChallenge.id,
                expectedChallengeVersion = 0,
                credential = duplicate,
                auditEvent = audit(
                    "audit-emulator-credential-duplicate",
                    AuditAction.CREDENTIAL_REGISTERED,
                    AuditTargetType.CREDENTIAL,
                    duplicate.id.value
                ),
                rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(secondChallenge.id)
            )
        )
        assertEquals(
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
            success(rejected).value.rejection?.error?.code
        )
        assertNull(success(store.findCredential(duplicate.id)).value)
        assertEquals(
            ChallengeState.FAILED,
            assertNotNull(success(store.findChallenge(secondChallenge.id)).value).state
        )
    }

    private suspend fun verifySessionRotationAndEpoch(
        store: FirestoreIdentityStore,
        baseline: BootstrapIdentityCommit,
        currentSession: IdentitySession
    ): User {
        val rotatedAt = Instant.fromEpochMilliseconds(currentSession.lastUsedAt.toEpochMilliseconds() + 1_000)
        val replacement = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("emulator-rotated-session"),
            familyId = currentSession.familyId,
            userId = baseline.user.id,
            userSessionEpoch = baseline.user.sessionEpoch,
            assurance = AuthenticationAssurance.RECOVERY,
            authenticationMethod = SessionAuthenticationMethod.BOOTSTRAP,
            rotationCounter = 1,
            createdAt = rotatedAt,
            rotatedFromId = currentSession.id
        )
        val rotation = success(
            store.rotateSession(
                RotateSessionCommand(
                    sessionId = currentSession.id,
                    expectedVersion = currentSession.version,
                    replacement = replacement,
                    rotatedAt = rotatedAt,
                    auditEvent = audit(
                        "audit-emulator-session-rotation",
                        AuditAction.SESSION_ROTATED,
                        AuditTargetType.SESSION,
                        currentSession.id.value
                    )
                )
            )
        ).value
        assertEquals(SessionState.ROTATED, rotation.previous.state)

        val revokedAt = Instant.fromEpochMilliseconds(rotatedAt.toEpochMilliseconds() + 1_000)
        val epoch = success(
            store.revokeUserSessions(
                RevokeUserSessionsCommand(
                    userId = baseline.user.id,
                    expectedUserVersion = baseline.user.version,
                    expectedSessionEpoch = baseline.user.sessionEpoch,
                    newSessionEpoch = baseline.user.sessionEpoch + 1,
                    exceptSessionId = replacement.id,
                    revokedAt = revokedAt,
                    reasonCode = "emulator_epoch_gate",
                    auditEvent = audit(
                        "audit-emulator-session-epoch",
                        AuditAction.SESSION_REVOKED,
                        AuditTargetType.USER,
                        baseline.user.id.value
                    )
                )
            )
        ).value
        assertTrue(epoch.revokedSessionIds.isEmpty())
        assertEquals(1, epoch.user.sessionEpoch)
        assertEquals(1, epoch.user.version)
        val retained = assertNotNull(success(store.findSession(replacement.id)).value)
        assertEquals(SessionState.ACTIVE, retained.state)
        assertEquals(1, retained.userSessionEpoch)
        return epoch.user
    }

    private suspend fun verifySessionIdleRenewal(
        store: FirestoreIdentityStore,
        session: IdentitySession
    ): IdentitySession {
        val renewedAt = Instant.fromEpochMilliseconds(session.lastUsedAt.toEpochMilliseconds() + 60_000)
        val renewedIdleExpiry = Instant.fromEpochMilliseconds(session.idleExpiresAt.toEpochMilliseconds() + 60_000)
        val raced = race {
            store.touchIdentitySession(
                TouchIdentitySessionCommand(
                    sessionId = session.id,
                    expectedVersion = session.version,
                    lastUsedAt = renewedAt,
                    idleExpiresAt = renewedIdleExpiry
                )
            )
        }
        val renewed = success(raced.single { it is StoreResult.Success }).value
        assertEquals(1, renewed.version)
        assertEquals(renewedAt, renewed.lastUsedAt)
        assertEquals(renewedIdleExpiry, renewed.idleExpiresAt)
        assertEquals(
            IdentityStoreErrorCode.VERSION_CONFLICT,
            failure(raced.single { it is StoreResult.Failure }).error.code
        )
        assertEquals(renewed, success(store.findSession(session.id)).value)
        return renewed
    }

    private suspend fun verifyRecoveryCodeReuse(store: FirestoreIdentityStore, user: User) {
        val codes = (0 until 10).map { index ->
            IdentityFixtures.recoveryCode(
                id = IdentityFixtures.recoveryCodeId("emulator-recovery-$index"),
                userId = user.id,
                generation = 0
            )
        }
        success(
            store.replaceRecoveryCodes(
                ReplaceRecoveryCodesCommand(
                    userId = user.id,
                    expectedGeneration = null,
                    newGeneration = 0,
                    codes = codes,
                    auditEvent = audit(
                        "audit-emulator-recovery-generation",
                        AuditAction.RECOVERY_CODES_REPLACED,
                        AuditTargetType.USER,
                        user.id.value
                    )
                )
            )
        )
        val consumedAt = IdentityFixtures.instant(5_000)
        val recoverySession = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("emulator-recovery-session"),
            userId = user.id,
            userSessionEpoch = user.sessionEpoch,
            assurance = AuthenticationAssurance.RECOVERY,
            authenticationMethod = SessionAuthenticationMethod.RECOVERY_CODE,
            createdAt = consumedAt
        )
        val command = ConsumeRecoveryCodeCommand(
            recoveryCodeId = codes.first().id,
            expectedVersion = 0,
            consumedAt = consumedAt,
            recoverySession = recoverySession,
            auditEvent = audit(
                "audit-emulator-recovery-consumed",
                AuditAction.RECOVERY_CODE_USED,
                AuditTargetType.USER,
                codes.first().id.value
            )
        )
        val results = race { store.consumeRecoveryCode(command) }
        assertEquals(1, results.count { it is StoreResult.Success })
        assertEquals(
            IdentityStoreErrorCode.RECOVERY_CODE_NOT_ACTIVE,
            failure(results.single { it is StoreResult.Failure }).error.code
        )
        assertEquals(
            RecoveryCodeState.CONSUMED,
            assertNotNull(success(store.findRecoveryCodeBySelector(codes.first().publicSelector)).value).state
        )
    }

    private suspend fun verifyLastOwnerProtection(
        store: FirestoreIdentityStore,
        owner: Membership
    ) {
        val replacement = owner.copy(
            role = OrganizationRole.ADMIN,
            version = owner.version + 1,
            updatedAt = IdentityFixtures.instant(6_000)
        )
        val result = store.mutateMembership(
            MutateMembershipCommand(
                membershipId = owner.id,
                expectedVersion = owner.version,
                replacement = replacement,
                auditEvent = audit(
                    "audit-emulator-last-owner",
                    AuditAction.MEMBERSHIP_CHANGED,
                    AuditTargetType.MEMBERSHIP,
                    owner.id.value,
                    owner.organizationId
                )
            )
        )
        assertEquals(IdentityStoreErrorCode.LAST_OWNER, failure(result).error.code)
        assertEquals(OrganizationRole.OWNER, assertNotNull(success(store.findMembership(owner.id)).value).role)
    }

    private suspend fun verifyInvitationEnrollment(
        store: FirestoreIdentityStore,
        organization: Organization
    ) {
        val invitation = IdentityFixtures.invitation(
            id = IdentityFixtures.invitationId("emulator-invitation"),
            organizationId = organization.id
        ).copy(email = EmailAddress("emulator-invitee@example.test"))
        success(
            store.createInvitation(
                CreateInvitationCommand(
                    invitation,
                    audit(
                        "audit-emulator-invitation-created",
                        AuditAction.INVITATION_CREATED,
                        AuditTargetType.INVITATION,
                        invitation.id.value,
                        organization.id
                    )
                )
            )
        )
        val enrolledAt = IdentityFixtures.instant(7_000)
        val user = IdentityFixtures.user(IdentityFixtures.userId("emulator-invited-user")).copy(
            primaryEmail = invitation.email,
            createdAt = enrolledAt,
            updatedAt = enrolledAt,
            activatedAt = enrolledAt
        )
        val membership = IdentityFixtures.membership(
            id = IdentityFixtures.membershipId("emulator-invited-membership"),
            organizationId = organization.id,
            userId = user.id,
            role = invitation.role
        ).copy(createdAt = enrolledAt, updatedAt = enrolledAt)
        val enrollmentExpiresAt = enrolledAt + 15.minutes
        val session = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("emulator-invited-session"),
            userId = user.id,
            assurance = AuthenticationAssurance.RECOVERY,
            authenticationMethod = SessionAuthenticationMethod.INVITATION,
            createdAt = enrolledAt
        ).copy(idleExpiresAt = enrollmentExpiresAt, absoluteExpiresAt = enrollmentExpiresAt)
        val command = EnrollInvitationCommand(
            invitationId = invitation.id,
            expectedInvitationVersion = invitation.version,
            expectedTokenDigest = invitation.tokenDigest,
            user = user,
            membership = membership,
            enrollmentSession = session,
            enrolledAt = enrolledAt,
            auditEvent = audit(
                "audit-emulator-invitation-enrolled",
                AuditAction.INVITATION_ACCEPTED,
                AuditTargetType.INVITATION,
                invitation.id.value,
                organization.id,
                occurredAt = enrolledAt
            )
        )
        val results = race { store.enrollInvitation(command) }
        val committed = success(results.single { it is StoreResult.Success }).value
        assertEquals(InvitationState.ACCEPTED, committed.invitation.state)
        assertEquals(SessionAuthenticationMethod.INVITATION, committed.enrollmentSession.authenticationMethod)
        assertEquals(
            IdentityStoreErrorCode.VERSION_CONFLICT,
            failure(results.single { it is StoreResult.Failure }).error.code
        )
    }

    private suspend fun verifyDeviceExchangeAndRefreshReplay(
        store: FirestoreIdentityStore,
        baseline: BootstrapIdentityCommit
    ) {
        val pending = IdentityFixtures.deviceGrant(
            id = IdentityFixtures.deviceGrantId("emulator-device-grant"),
            state = DeviceGrantState.PENDING
        )
        success(
            store.compareAndSetDeviceGrant(
                CompareAndSetDeviceGrantCommand(
                    expectedVersion = null,
                    replacement = pending,
                    auditEvent = audit(
                        "audit-emulator-device-pending",
                        AuditAction.DEVICE_GRANT_CHANGED,
                        AuditTargetType.DEVICE_GRANT,
                        pending.id.value,
                        baseline.organization.id
                    )
                )
            )
        )
        val authorizedAt = IdentityFixtures.instant(8_000)
        val authorized = pending.copy(
            approvedCapabilities = pending.requestedCapabilities,
            state = DeviceGrantState.AUTHORIZED,
            userId = baseline.user.id,
            organizationId = baseline.organization.id,
            membershipId = baseline.ownerMembership.id,
            membershipVersion = baseline.ownerMembership.version,
            authorizedByUserId = baseline.user.id,
            version = 1,
            authorizedAt = authorizedAt
        )
        success(
            store.compareAndSetDeviceGrant(
                CompareAndSetDeviceGrantCommand(
                    expectedVersion = 0,
                    replacement = authorized,
                    auditEvent = audit(
                        "audit-emulator-device-authorized",
                        AuditAction.DEVICE_GRANT_CHANGED,
                        AuditTargetType.DEVICE_GRANT,
                        pending.id.value,
                        baseline.organization.id
                    )
                )
            )
        )

        val exchangedAt = IdentityFixtures.instant(9_000)
        val family = DeviceTokenFamily(
            id = IdentityFixtures.deviceTokenFamilyId("emulator-device-family"),
            deviceGrantId = pending.id,
            clientId = pending.clientId,
            userId = baseline.user.id,
            organizationId = baseline.organization.id,
            membershipId = baseline.ownerMembership.id,
            membershipVersion = baseline.ownerMembership.version,
            capabilities = authorized.approvedCapabilities,
            createdAt = exchangedAt,
            expiresAt = IdentityFixtures.instant(500_000)
        )
        val access = accessToken("emulator-access-one", family.id, exchangedAt, 100_000)
        val refresh = refreshToken("emulator-refresh-one", family.id, exchangedAt, 0)
        val exchange = ExchangeDeviceGrantCommand(
            deviceGrantId = pending.id,
            expectedDeviceGrantVersion = authorized.version,
            family = family,
            accessToken = access,
            refreshToken = refresh,
            exchangedAt = exchangedAt,
            auditEvent = audit(
                "audit-emulator-device-exchange",
                AuditAction.DEVICE_TOKEN_ISSUED,
                AuditTargetType.DEVICE_GRANT,
                pending.id.value,
                baseline.organization.id
            )
        )
        success(store.exchangeDeviceGrant(exchange))
        assertEquals(
            IdentityStoreErrorCode.VERSION_CONFLICT,
            failure(store.exchangeDeviceGrant(exchange)).error.code
        )

        val rotatedAt = IdentityFixtures.instant(10_000)
        val replacementAccess = accessToken("emulator-access-two", family.id, rotatedAt, 110_000)
        val replacementRefresh = refreshToken("emulator-refresh-two", family.id, rotatedAt, 1)
        val rotate = RotateDeviceRefreshTokenCommand(
            refreshTokenId = refresh.id,
            expectedRefreshTokenVersion = refresh.version,
            expectedFamilyVersion = family.version,
            replacementAccessToken = replacementAccess,
            replacementRefreshToken = replacementRefresh,
            rotatedAt = rotatedAt,
            auditEvent = audit(
                "audit-emulator-device-refresh",
                AuditAction.DEVICE_TOKEN_REFRESHED,
                AuditTargetType.DEVICE_GRANT,
                family.deviceGrantId.value,
                baseline.organization.id
            )
        )
        success(store.rotateDeviceRefreshToken(rotate))
        assertEquals(
            IdentityStoreErrorCode.VERSION_CONFLICT,
            failure(store.rotateDeviceRefreshToken(rotate)).error.code
        )

        val replayDetectedAt = IdentityFixtures.instant(11_000)
        success(
            store.revokeDeviceTokenFamily(
                RevokeDeviceTokenFamilyCommand(
                    familyId = family.id,
                    expectedFamilyVersion = family.version,
                    revokedAt = replayDetectedAt,
                    reasonCode = "refresh_replay_detected",
                    replayDetected = true,
                    auditEvent = audit(
                        "audit-emulator-device-replay",
                        AuditAction.DEVICE_TOKEN_REPLAY_DETECTED,
                        AuditTargetType.DEVICE_GRANT,
                        family.id.value,
                        baseline.organization.id
                    )
                )
            )
        )
        assertEquals(
            DeviceTokenFamilyState.REVOKED,
            assertNotNull(success(store.findDeviceTokenFamily(family.id)).value).state
        )
        assertEquals(
            DeviceAccessTokenState.REVOKED,
            assertNotNull(success(store.findDeviceAccessTokenBySelector(replacementAccess.publicSelector)).value).state
        )
        assertEquals(
            DeviceRefreshTokenState.REVOKED,
            assertNotNull(success(store.findDeviceRefreshTokenBySelector(replacementRefresh.publicSelector)).value).state
        )
    }

    private suspend fun verifyScimIdempotency(
        store: FirestoreIdentityStore,
        organization: Organization
    ) {
        val provider = "emulator-scim:${organization.id.value}"
        val user = IdentityFixtures.user(IdentityFixtures.userId("emulator-scim-user"))
        val mutation = ScimMutation(
            operationId = IdentityFixtures.scimOperationId("emulator-scim-child"),
            provider = provider,
            type = ScimMutationType.UPSERT_USER,
            externalSubject = ExternalSubject("emulator-scim-subject"),
            user = user,
            occurredAt = IdentityFixtures.instant(12_000)
        )
        val child = ApplyScimMutationCommand(
            mutation,
            audit(
                "audit-emulator-scim-child",
                AuditAction.SCIM_MUTATION_APPLIED,
                AuditTargetType.USER,
                user.id.value,
                organization.id,
                occurredAt = mutation.occurredAt
            )
        )
        val command = ApplyScimBatchCommand(
            operationId = IdentityFixtures.scimOperationId("emulator-scim-batch"),
            organizationId = organization.id,
            provider = provider,
            mutations = listOf(child),
            auditEvent = audit(
                "audit-emulator-scim-batch",
                AuditAction.SCIM_MUTATION_APPLIED,
                AuditTargetType.USER,
                user.id.value,
                organization.id,
                occurredAt = mutation.occurredAt
            )
        )
        assertEquals(false, success(store.applyScimBatch(command)).value.alreadyApplied)
        assertEquals(true, success(store.applyScimBatch(command)).value.alreadyApplied)
        assertEquals(
            IdentityStoreErrorCode.IDEMPOTENCY_CONFLICT,
            failure(
                store.applyScimBatch(
                    command.copy(
                        mutations = listOf(
                            child.copy(mutation = mutation.copy(user = user.copy(displayName = "Changed payload")))
                        )
                    )
                )
            ).error.code
        )
    }

    private fun accessToken(
        suffix: String,
        familyId: DeviceTokenFamilyId,
        createdAt: Instant,
        expiryOffset: Long
    ): DeviceAccessToken = DeviceAccessToken(
        id = IdentityFixtures.deviceAccessTokenId(suffix),
        familyId = familyId,
        publicSelector = "selector_$suffix",
        secretDigest = IdentityFixtures.digest("secret-$suffix"),
        createdAt = createdAt,
        expiresAt = IdentityFixtures.instant(expiryOffset)
    )

    private fun refreshToken(
        suffix: String,
        familyId: DeviceTokenFamilyId,
        createdAt: Instant,
        rotationCounter: Long
    ): DeviceRefreshToken = DeviceRefreshToken(
        id = IdentityFixtures.deviceRefreshTokenId(suffix),
        familyId = familyId,
        publicSelector = "selector_$suffix",
        secretDigest = IdentityFixtures.digest("secret-$suffix"),
        rotationCounter = rotationCounter,
        createdAt = createdAt,
        expiresAt = IdentityFixtures.instant(400_000)
    )

    private fun audit(
        id: String,
        action: AuditAction,
        targetType: AuditTargetType,
        targetId: String,
        organizationId: OrganizationId? = null,
        occurredAt: Instant = IdentityFixtures.instant()
    ): AuditEvent = AuditEvent(
        id = IdentityFixtures.auditEventId(id),
        actor = AuditActor(AuditActorType.SYSTEM),
        organizationId = organizationId,
        action = action,
        target = AuditTarget(targetType, targetId),
        outcome = AuditOutcome.SUCCEEDED,
        occurredAt = occurredAt
    )

    private suspend fun <T> race(block: suspend () -> StoreResult<T>): List<StoreResult<T>> =
        coroutineScope {
            listOf(
                async(Dispatchers.IO) { block() },
                async(Dispatchers.IO) { block() }
            ).awaitAll()
        }

    private fun emulatorEndpoint(): EmulatorEndpoint {
        val raw = requireNotNull(System.getenv("FIRESTORE_EMULATOR_HOST")) {
            "FIRESTORE_EMULATOR_HOST is required by the Firestore emulator release gate"
        }
        require(raw == raw.trim() && "://" !in raw && '/' !in raw && '@' !in raw) {
            "FIRESTORE_EMULATOR_HOST must be a bare loopback host:port"
        }
        val uri = URI.create("http://$raw")
        require(uri.port in 1..65_535 && uri.host in LOOPBACK_HOSTS) {
            "The Firestore emulator release gate only permits an exact loopback host"
        }
        return EmulatorEndpoint("http://$raw")
    }

    private fun emulatorProjectId(): String {
        val projectId = System.getenv("AETHER_FIRESTORE_EMULATOR_PROJECT_ID")
            ?: "aether-identity-emulator-gate"
        require(projectId.startsWith("aether-identity-emulator-") && "prod" !in projectId) {
            "The emulator gate requires a dedicated non-production Aether test project ID"
        }
        return projectId
    }

    private fun uniqueNamespace(): String {
        val nonce = (System.getenv("GITHUB_RUN_ID") ?: ProcessHandle.current().pid().toString())
            .lowercase()
            .filter(Char::isLetterOrDigit)
            .take(24)
        return "identity_test_emulator_$nonce"
    }

    private suspend fun flushEmulator(
        runtime: IdentityRuntime,
        endpoint: EmulatorEndpoint,
        projectId: String
    ) {
        val response = runtime.http.execute(
            IdentityHttpRequest(
                method = IdentityHttpMethod.DELETE,
                url = "${endpoint.apiBaseUrl}/emulator/v1/projects/$projectId/databases/(default)/documents"
            )
        )
        check(response.statusCode in 200..299) {
            "Firestore emulator reset failed with HTTP ${response.statusCode}"
        }
    }

    private fun <T> success(result: StoreResult<T>): StoreResult.Success<T> =
        assertIs(result, "Expected store success but received $result. Recent REST exchanges: $exchanges")
    private fun failure(result: StoreResult<*>): StoreResult.Failure = assertIs(result)

    private data class EmulatorEndpoint(val apiBaseUrl: String)

    private inner class RecordingIdentityHttpClient(
        private val delegate: IdentityHttpClient
    ) : IdentityHttpClient {
        override suspend fun execute(request: IdentityHttpRequest): IdentityHttpResponse {
            val response = delegate.execute(request)
            if (exchanges.size == 24) exchanges.removeFirst()
            exchanges.addLast(
                "${request.method} ${request.url.substringAfter("/v1/")} -> ${response.statusCode}"
            )
            return response
        }
    }

    private companion object {
        const val EMULATOR_GATE_PROPERTY = "aether.firestore.emulator.gate"
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "0:0:0:0:0:0:0:1", "::1")
    }
}
