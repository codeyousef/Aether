package codes.yousef.aether.auth.firestore

import codes.yousef.aether.auth.*
import codes.yousef.aether.auth.testkit.DeterministicIdentityClock
import codes.yousef.aether.auth.testkit.DeterministicIdentityHttpClient
import codes.yousef.aether.auth.testkit.DeterministicIdentityRuntime
import codes.yousef.aether.auth.testkit.IdentityFixtures
import codes.yousef.aether.auth.testkit.IdentityStoreConformanceCase
import codes.yousef.aether.auth.testkit.IdentityStoreConformanceSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FirestoreIdentityStoreTest {
    private val config = FirestoreIdentityConfig(
        environment = IdentityEnvironment.TEST,
        namespace = "identity_test",
        projectId = "aether-test-project",
        apiBaseUrl = "http://127.0.0.1:8080/v1"
    )

    @Test
    fun productionRejectsEmulatorAndCrossEnvironmentNamespace() {
        assertFailsWith<IllegalArgumentException> {
            FirestoreIdentityConfig(
                environment = IdentityEnvironment.PRODUCTION,
                namespace = "identity_production",
                projectId = "aether-prod-project",
                apiBaseUrl = "http://127.0.0.1:8080/v1"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            config.copy(environment = IdentityEnvironment.PRODUCTION)
        }
    }

    @Test
    fun initializationFailsClosedUntilExactMarkerIsProvisioned() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = store(backend)

        assertEquals(IdentityStoreErrorCode.NOT_FOUND, assertFailure(store.initialize()).error.code)
        assertIs<StoreResult.Success<Unit>>(store.provisionEnvironmentMarker())
        assertIs<StoreResult.Success<Unit>>(store.provisionEnvironmentMarker())
        val marker = backend.documentEndingWith("/aetherIdentityEnvironment/current")
        assertEquals("test", marker.fields.getValue("environment").stringValue)
        assertEquals("identity_test", marker.fields.getValue("namespace").stringValue)
        assertEquals(
            FirestoreIdentityStore.FIRESTORE_ENVIRONMENT_MARKER_SCHEMA_VERSION.toString(),
            marker.fields.getValue("schemaVersion").integerValue
        )
        assertIs<StoreResult.Success<Unit>>(store.initialize())

        val conflictingConfig = config.copy(namespace = "secondary_test")
        val conflictingStore = store(backend, conflictingConfig)
        assertEquals(
            IdentityStoreErrorCode.INTERNAL,
            assertFailure(conflictingStore.provisionEnvironmentMarker()).error.code
        )
        assertEquals(
            IdentityStoreErrorCode.INTERNAL,
            assertFailure(conflictingStore.initialize()).error.code
        )

        backend.corruptEnvironmentMarker(config, "development")
        val mismatched = store(backend)
        assertEquals(IdentityStoreErrorCode.INTERNAL, assertFailure(mismatched.initialize()).error.code)
    }

    @Test
    fun organizationAuditReadUsesBoundedStableKeysetAndTenantIndex() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)
        val organizationId = IdentityFixtures.organizationId("firestore-audit-org")
        val otherOrganizationId = IdentityFixtures.organizationId("firestore-other-org")
        suspend fun append(id: String, organization: OrganizationId, offset: Long) {
            assertSuccess(
                store.appendAuditEvent(
                    audit(id, AuditAction.ORGANIZATION_CHANGED).copy(
                        organizationId = organization,
                        occurredAt = IdentityFixtures.instant(offset)
                    )
                )
            )
        }
        append("audit-firestore-3000", organizationId, 3_000)
        append("audit-firestore-2000-b", organizationId, 2_000)
        append("audit-firestore-2000-a", organizationId, 2_000)
        append("audit-firestore-1000", organizationId, 1_000)
        append("audit-firestore-other", otherOrganizationId, 4_000)

        val first = assertSuccess(
            store.listAuditEventsForOrganization(
                OrganizationAuditEventPageRequest(organizationId, limit = 2)
            )
        ).value
        assertEquals(
            listOf(
                IdentityFixtures.auditEventId("audit-firestore-3000").value,
                IdentityFixtures.auditEventId("audit-firestore-2000-b").value
            ),
            first.events.map { it.id.value }
        )
        val firstQuery = backend.queries.last()
        assertEquals(3, firstQuery.limit)
        assertEquals(
            listOf("occurredAt", "entityId"),
            firstQuery.orderBy.map { it.field.fieldPath }
        )
        assertNotNull(backend.documentEndingWith("/${IdentityFixtures.auditEventId("audit-firestore-3000").value}")
            .fields.getValue("occurredAt").timestampValue)

        append("audit-firestore-4000-new", organizationId, 4_000)
        val second = assertSuccess(
            store.listAuditEventsForOrganization(
                OrganizationAuditEventPageRequest(organizationId, cursor = first.nextCursor, limit = 2)
            )
        ).value
        assertEquals(
            listOf(
                IdentityFixtures.auditEventId("audit-firestore-2000-a").value,
                IdentityFixtures.auditEventId("audit-firestore-1000").value
            ),
            second.events.map { it.id.value }
        )
        assertNull(second.nextCursor)
        assertEquals(false, backend.queries.last().startAt?.before)
        assertNotNull(backend.queries.last().startAt?.values?.first()?.timestampValue)
    }

    @Test
    fun auditRetentionUsesStrictCutoffAndPreconditionedBoundedDeletes() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)
        val organizationId = OrganizationId("018f0f2e-7b00-7000-8000-000000000010")
        suspend fun append(id: String, offset: Long) {
            assertSuccess(
                store.appendAuditEvent(
                    audit(id, AuditAction.ORGANIZATION_CHANGED).copy(
                        organizationId = organizationId,
                        occurredAt = IdentityFixtures.instant(offset)
                    )
                )
            )
        }
        append("018f0f2e-7b00-7000-8000-000000000011", 1_000)
        append("018f0f2e-7b00-7000-8000-000000000012", 2_000)
        append("018f0f2e-7b00-7000-8000-000000000013", 3_000)

        val first = assertSuccess(
            store.purgeAuditEvents(
                PurgeAuditEventsCommand(IdentityFixtures.instant(3_000), maximumEvents = 1)
            )
        ).value
        assertEquals(PurgeAuditEventsCommit(deletedCount = 1, hasMore = true), first)
        assertEquals("LESS_THAN", backend.queries.last().where?.fieldFilter?.op)
        assertEquals(2, backend.queries.last().limit)

        val second = assertSuccess(
            store.purgeAuditEvents(
                PurgeAuditEventsCommand(IdentityFixtures.instant(3_000), maximumEvents = 1)
            )
        ).value
        assertEquals(PurgeAuditEventsCommit(deletedCount = 1, hasMore = false), second)
        val retained = assertSuccess(
            store.listAuditEventsForOrganization(OrganizationAuditEventPageRequest(organizationId))
        ).value.events
        assertEquals(listOf("018f0f2e-7b00-7000-8000-000000000013"), retained.map { it.id.value })
    }

    @Test
    fun challengeCreateIsAtomicWithDeterministicUniquenessClaim() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)
        val first = IdentityFixtures.challenge()
        val duplicateDigest = IdentityFixtures.challenge(id = IdentityFixtures.challengeId("challenge-2")).copy(
            challengeDigest = first.challengeDigest
        )

        assertEquals(first, assertSuccess(store.createChallenge(CreateChallengeCommand(first))).value)
        assertEquals(first, assertSuccess(store.findChallenge(first.id)).value)
        assertEquals(
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
            assertFailure(store.createChallenge(CreateChallengeCommand(duplicateDigest))).error.code
        )
        assertNull(assertSuccess(store.findChallenge(duplicateDigest.id)).value)
    }

    @Test
    fun federationProviderRouteAndStorageClaimsRejectCrossProviderRemapping() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)
        val organization = IdentityFixtures.organization(
            id = IdentityFixtures.organizationId("firestore-provider-uniqueness"),
            slug = "firestore-provider-uniqueness"
        )
        backend.seed(
            config,
            "organizations",
            organization.id.value,
            organization,
            Organization.serializer(),
            mapOf("state" to "active", "slug" to organization.slug)
        )
        val firstStorageKey = IdentityFixtures.federationProviderStorageKey(
            FederationProviderKind.OIDC,
            "firestore-provider-one"
        )
        val secondStorageKey = IdentityFixtures.federationProviderStorageKey(
            FederationProviderKind.OIDC,
            "firestore-provider-two"
        )
        val lease = assertSuccess(
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organization.id,
                    FederationProviderKind.OIDC,
                    "workforce",
                    firstStorageKey,
                    IdentityFixtures.instant()
                )
            )
        ).value

        assertEquals(
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
            assertFailure(
                store.acquireFederationProviderLease(
                    AcquireFederationProviderLeaseCommand(
                        organization.id,
                        FederationProviderKind.OIDC,
                        "workforce",
                        secondStorageKey,
                        IdentityFixtures.instant(1_000)
                    )
                )
            ).error.code
        )
        assertEquals(
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
            assertFailure(
                store.acquireFederationProviderLease(
                    AcquireFederationProviderLeaseCommand(
                        organization.id,
                        FederationProviderKind.OIDC,
                        "partners",
                        firstStorageKey,
                        IdentityFixtures.instant(1_000)
                    )
                )
            ).error.code
        )
        assertEquals(
            lease,
            assertSuccess(store.validateFederationProviderLease(lease)).value
        )
        assertEquals(
            lease.storageKey,
            assertNotNull(
                assertSuccess(store.findFederationProviderControl(organization.id, "workforce")).value
            ).storageKey
        )
        assertNull(assertSuccess(store.findFederationProviderControl(organization.id, "partners")).value)
        assertNull(assertSuccess(store.findFederationProviderControlByStorageKey(secondStorageKey)).value)
        assertEquals(1, backend.countCollection(config, "federationProviderControls"))
        assertEquals(2, backend.countCollection(config, "unique"))
    }

    @Test
    fun externalLinkChallengeLeaseRemainsStaleAcrossDisableAndReenable() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)
        val organization = IdentityFixtures.organization(
            id = IdentityFixtures.organizationId("firestore-challenge-provider"),
            slug = "firestore-challenge-provider"
        )
        backend.seed(
            config,
            "organizations",
            organization.id.value,
            organization,
            Organization.serializer(),
            mapOf("state" to "active", "slug" to organization.slug)
        )
        val providerId = "challenge-provider"
        val storageKey = IdentityFixtures.federationProviderStorageKey(
            FederationProviderKind.OIDC,
            "firestore-challenge-provider"
        )
        val initialLease = assertSuccess(
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organization.id,
                    FederationProviderKind.OIDC,
                    providerId,
                    storageKey,
                    IdentityFixtures.instant()
                )
            )
        ).value
        val challenge = IdentityFixtures.challenge(
            id = IdentityFixtures.challengeId("firestore-external-link-challenge"),
            purpose = ChallengePurpose.EXTERNAL_IDENTITY_LINK,
            userId = null,
            organizationId = organization.id,
            federationProviderLease = initialLease
        )
        assertSuccess(store.createChallenge(CreateChallengeCommand(challenge)))

        val enabled = assertNotNull(
            assertSuccess(store.findFederationProviderControl(organization.id, providerId)).value
        )
        val disabledAt = IdentityFixtures.instant(1_000)
        val disableReason = "firestore_test_provider_disabled"
        val disabled = assertSuccess(
            store.compareAndSetFederationProviderState(
                CompareAndSetFederationProviderStateCommand(
                    expectedVersion = enabled.version,
                    replacement = enabled.copy(
                        state = FederationProviderState.DISABLED,
                        sessionEpoch = enabled.sessionEpoch + 1,
                        version = enabled.version + 1,
                        updatedAt = disabledAt,
                        disabledAt = disabledAt,
                        disabledReasonCode = disableReason
                    ),
                    auditEvent = federationProviderAudit(
                        id = "audit-firestore-provider-disabled",
                        action = AuditAction.FEDERATION_PROVIDER_DISABLED,
                        organizationId = organization.id,
                        storageKey = storageKey,
                        occurredAt = disabledAt,
                        reasonCode = disableReason
                    )
                )
            )
        ).value.control
        assertEquals(
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
            assertFailure(
                store.consumeChallenge(
                    ConsumeChallengeCommand(
                        challenge.id,
                        challenge.version,
                        ChallengeState.CONSUMED,
                        IdentityFixtures.instant(1_100),
                        federationProviderLease = initialLease
                    )
                )
            ).error.code
        )
        val rejectedCreation = IdentityFixtures.challenge(
            id = IdentityFixtures.challengeId("firestore-disabled-link-challenge"),
            purpose = ChallengePurpose.EXTERNAL_IDENTITY_LINK,
            userId = null,
            organizationId = organization.id,
            federationProviderLease = initialLease
        )
        assertEquals(
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
            assertFailure(store.createChallenge(CreateChallengeCommand(rejectedCreation))).error.code
        )
        assertNull(assertSuccess(store.findChallenge(rejectedCreation.id)).value)

        val enabledAt = IdentityFixtures.instant(2_000)
        assertSuccess(
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
                    auditEvent = federationProviderAudit(
                        id = "audit-firestore-provider-enabled",
                        action = AuditAction.FEDERATION_PROVIDER_ENABLED,
                        organizationId = organization.id,
                        storageKey = storageKey,
                        occurredAt = enabledAt
                    )
                )
            )
        )
        val currentLease = assertSuccess(
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organization.id,
                    FederationProviderKind.OIDC,
                    providerId,
                    storageKey,
                    IdentityFixtures.instant(2_100)
                )
            )
        ).value
        assertEquals(
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
            assertFailure(
                store.consumeChallenge(
                    ConsumeChallengeCommand(
                        challenge.id,
                        challenge.version,
                        ChallengeState.CONSUMED,
                        IdentityFixtures.instant(2_200),
                        federationProviderLease = initialLease
                    )
                )
            ).error.code
        )
        assertEquals(
            IdentityStoreErrorCode.INVALID_TRANSITION,
            assertFailure(
                store.consumeChallenge(
                    ConsumeChallengeCommand(
                        challenge.id,
                        challenge.version,
                        ChallengeState.CONSUMED,
                        IdentityFixtures.instant(2_200),
                        federationProviderLease = currentLease
                    )
                )
            ).error.code
        )
        assertEquals(
            ChallengeState.PENDING,
            assertNotNull(assertSuccess(store.findChallenge(challenge.id)).value).state
        )
    }

    @Test
    fun federatedSessionRotationValidatesBothReplacementAndPredecessorEpochs() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)
        val organization = IdentityFixtures.organization(
            id = IdentityFixtures.organizationId("firestore-session-provider"),
            slug = "firestore-session-provider"
        )
        val user = IdentityFixtures.user(IdentityFixtures.userId("firestore-session-user"))
        backend.seed(
            config,
            "organizations",
            organization.id.value,
            organization,
            Organization.serializer(),
            mapOf("state" to "active", "slug" to organization.slug)
        )
        backend.seed(
            config,
            "users",
            user.id.value,
            user,
            User.serializer(),
            mapOf("state" to "active")
        )
        val providerId = "session-provider"
        val storageKey = IdentityFixtures.federationProviderStorageKey(
            FederationProviderKind.OIDC,
            "firestore-session-provider"
        )
        val initialLease = assertSuccess(
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organization.id,
                    FederationProviderKind.OIDC,
                    providerId,
                    storageKey,
                    IdentityFixtures.instant()
                )
            )
        ).value
        val initial = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("firestore-federated-predecessor"),
            userId = user.id,
            assurance = AuthenticationAssurance.SESSION,
            authenticationMethod = SessionAuthenticationMethod.OIDC,
            federationOrganizationId = organization.id,
            federationProviderKey = storageKey,
            federationProviderSessionEpoch = initialLease.sessionEpoch,
            externalIdentityId = IdentityFixtures.externalIdentityId("firestore-federated-predecessor")
        )
        assertSuccess(
            store.createSession(
                CreateSessionCommand(
                    initial,
                    sessionAudit(
                        "audit-firestore-federated-session-created",
                        AuditAction.SESSION_CREATED,
                        organization.id,
                        initial.id,
                        initial.createdAt
                    )
                )
            )
        )

        val replacementAt = IdentityFixtures.instant(1_000)
        val wrongEpochReplacement = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("firestore-wrong-epoch-replacement"),
            familyId = initial.familyId,
            userId = user.id,
            assurance = AuthenticationAssurance.SESSION,
            authenticationMethod = SessionAuthenticationMethod.OIDC,
            federationOrganizationId = organization.id,
            federationProviderKey = storageKey,
            federationProviderSessionEpoch = initialLease.sessionEpoch + 1,
            externalIdentityId = initial.externalIdentityId,
            rotationCounter = initial.rotationCounter + 1,
            createdAt = replacementAt,
            rotatedFromId = initial.id
        )
        assertEquals(
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
            assertFailure(
                store.rotateSession(
                    RotateSessionCommand(
                        initial.id,
                        initial.version,
                        wrongEpochReplacement,
                        replacementAt,
                        sessionAudit(
                            "audit-firestore-wrong-replacement-epoch",
                            AuditAction.SESSION_ROTATED,
                            organization.id,
                            initial.id,
                            replacementAt
                        )
                    )
                )
            ).error.code
        )
        assertNull(assertSuccess(store.findSession(wrongEpochReplacement.id)).value)

        val current = assertNotNull(
            assertSuccess(store.findFederationProviderControl(organization.id, providerId)).value
        )
        val disabledAt = IdentityFixtures.instant(2_000)
        val reason = "firestore_session_provider_disabled"
        val disabled = assertSuccess(
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
                    federationProviderAudit(
                        "audit-firestore-session-provider-disabled",
                        AuditAction.FEDERATION_PROVIDER_DISABLED,
                        organization.id,
                        storageKey,
                        disabledAt,
                        reason
                    )
                )
            )
        ).value.control
        val enabledAt = IdentityFixtures.instant(3_000)
        assertSuccess(
            store.compareAndSetFederationProviderState(
                CompareAndSetFederationProviderStateCommand(
                    disabled.version,
                    disabled.copy(
                        state = FederationProviderState.ENABLED,
                        version = disabled.version + 1,
                        updatedAt = enabledAt,
                        disabledAt = null,
                        disabledReasonCode = null
                    ),
                    federationProviderAudit(
                        "audit-firestore-session-provider-enabled",
                        AuditAction.FEDERATION_PROVIDER_ENABLED,
                        organization.id,
                        storageKey,
                        enabledAt
                    )
                )
            )
        )
        val currentLease = assertSuccess(
            store.acquireFederationProviderLease(
                AcquireFederationProviderLeaseCommand(
                    organization.id,
                    FederationProviderKind.OIDC,
                    providerId,
                    storageKey,
                    IdentityFixtures.instant(3_100)
                )
            )
        ).value
        val currentReplacementAt = IdentityFixtures.instant(3_200)
        val currentReplacement = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("firestore-current-epoch-replacement"),
            familyId = initial.familyId,
            userId = user.id,
            assurance = AuthenticationAssurance.SESSION,
            authenticationMethod = SessionAuthenticationMethod.OIDC,
            federationOrganizationId = organization.id,
            federationProviderKey = storageKey,
            federationProviderSessionEpoch = currentLease.sessionEpoch,
            externalIdentityId = initial.externalIdentityId,
            rotationCounter = initial.rotationCounter + 1,
            createdAt = currentReplacementAt,
            rotatedFromId = initial.id
        )
        assertEquals(
            IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED,
            assertFailure(
                store.rotateSession(
                    RotateSessionCommand(
                        initial.id,
                        initial.version,
                        currentReplacement,
                        currentReplacementAt,
                        sessionAudit(
                            "audit-firestore-stale-predecessor",
                            AuditAction.SESSION_ROTATED,
                            organization.id,
                            initial.id,
                            currentReplacementAt
                        )
                    )
                )
            ).error.code
        )
        assertEquals(SessionState.ACTIVE, assertNotNull(assertSuccess(store.findSession(initial.id)).value).state)
        assertNull(assertSuccess(store.findSession(currentReplacement.id)).value)
    }

    @Test
    fun fakeFirestoreRunsSharedProviderRaceAndJitAtomicityConformance() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)

        val report = IdentityStoreConformanceSuite(store, "firestore-fake").runAll()

        assertTrue(IdentityStoreConformanceCase.FEDERATION_PROVIDER_LIFECYCLE in report.cases)
        assertTrue(IdentityStoreConformanceCase.FEDERATION_LINK_CONFLICTS_AND_REPLAY_RECEIPTS in report.cases)
        assertTrue(IdentityStoreConformanceCase.FEDERATION_JIT_ATOMICITY in report.cases)
    }

    @Test
    fun providerBackendFailuresMapToStableSafeStoreErrors() {
        assertEquals(
            IdentityStoreError(IdentityStoreErrorCode.ALREADY_EXISTS),
            FirestoreFailureMapper.fromProvider("ALREADY_EXISTS")
        )
        assertEquals(
            IdentityStoreError(IdentityStoreErrorCode.VERSION_CONFLICT, retryable = true),
            FirestoreFailureMapper.fromProvider("FAILED_PRECONDITION")
        )
        assertEquals(
            IdentityStoreError(IdentityStoreErrorCode.UNAVAILABLE, retryable = true),
            FirestoreFailureMapper.fromProvider("RESOURCE_EXHAUSTED")
        )
        assertEquals(
            IdentityStoreError(IdentityStoreErrorCode.INTERNAL),
            FirestoreFailureMapper.fromProvider("PERMISSION_DENIED")
        )
        assertEquals(
            IdentityStoreError(IdentityStoreErrorCode.INTERNAL),
            FirestoreFailureMapper.fromProvider("provider-secret-detail", httpStatus = 400)
        )
    }

    @Test
    fun webAuthnCredentialIdIsGloballyUniqueIndependentlyOfInternalId() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)
        val user = IdentityFixtures.user()
        backend.seed(config, "users", user.id.value, user, User.serializer(), mapOf("state" to "active"))
        val challengeOne = IdentityFixtures.challenge(
            id = IdentityFixtures.challengeId("registration-1"),
            purpose = ChallengePurpose.WEBAUTHN_REGISTRATION
        )
        val challengeTwo = IdentityFixtures.challenge(
            id = IdentityFixtures.challengeId("registration-2"),
            purpose = ChallengePurpose.WEBAUTHN_REGISTRATION
        )
        assertSuccess(store.createChallenge(CreateChallengeCommand(challengeOne)))
        assertSuccess(store.createChallenge(CreateChallengeCommand(challengeTwo)))
        val credentialOne = IdentityFixtures.credential()
        val credentialTwo = IdentityFixtures.credential(
            id = IdentityFixtures.credentialId("credential-2"),
            webAuthnId = credentialOne.webAuthnId
        )

        val first = CompleteCredentialRegistrationCommand(
            challengeId = challengeOne.id,
            expectedChallengeVersion = 0,
            credential = credentialOne,
            auditEvent = audit("audit-registration-1", AuditAction.CREDENTIAL_REGISTERED),
            rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(challengeOne.id)
        )
        assertNotNull(assertSuccess(store.completeCredentialRegistration(first)).value.completion)
        assertEquals(credentialOne, assertSuccess(store.findCredentialByWebAuthnId(credentialOne.webAuthnId)).value)

        val second = CompleteCredentialRegistrationCommand(
            challengeId = challengeTwo.id,
            expectedChallengeVersion = 0,
            credential = credentialTwo,
            auditEvent = audit("audit-registration-2", AuditAction.CREDENTIAL_REGISTERED),
            rejectionAuditEvent = IdentityFixtures.webAuthnStoreRejectionAudit(challengeTwo.id)
        )
        assertEquals(
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
            assertSuccess(store.completeCredentialRegistration(second)).value.rejection?.error?.code
        )
        assertNull(assertSuccess(store.findCredential(credentialTwo.id)).value)
        assertEquals(ChallengeState.FAILED, assertNotNull(assertSuccess(store.findChallenge(challengeTwo.id)).value).state)
    }

    @Test
    fun transientAbortedCommitRetriesWholeTransaction() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)
        backend.abortNextCommit = true
        val challenge = IdentityFixtures.challenge()

        assertIs<StoreResult.Success<Challenge>>(store.createChallenge(CreateChallengeCommand(challenge)))
        assertEquals(2, backend.transactionsBegun)
        assertEquals(challenge, assertSuccess(store.findChallenge(challenge.id)).value)
    }

    @Test
    fun versionPreconditionFailureIsSafeAndRetryable() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)
        val challenge = IdentityFixtures.challenge()
        assertSuccess(store.createChallenge(CreateChallengeCommand(challenge)))

        val result = store.consumeChallenge(
            ConsumeChallengeCommand(
                challengeId = challenge.id,
                expectedVersion = 7,
                terminalState = ChallengeState.CONSUMED,
                consumedAt = IdentityFixtures.instant(1_000)
            )
        )
        val error = assertFailure(result).error
        assertEquals(IdentityStoreErrorCode.VERSION_CONFLICT, error.code)
        assertEquals(true, error.retryable)
    }

    @Test
    fun sessionIdleRenewalIsAtomicCasWithoutAuditAndCannotRegressOrRevive() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)
        val session = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("firestore-idle-renewal")
        )
        backend.seed(
            config,
            "sessions",
            session.id.value,
            session,
            IdentitySession.serializer(),
            mapOf(
                "userId" to session.userId.value,
                "state" to "active",
                "lastUsedAt" to session.lastUsedAt.toString()
            )
        )

        val renewedAt = Instant.fromEpochMilliseconds(session.lastUsedAt.toEpochMilliseconds() + 60_000)
        val renewedIdleExpiry = Instant.fromEpochMilliseconds(session.idleExpiresAt.toEpochMilliseconds() + 60_000)
        val renewed = assertSuccess(
            store.touchIdentitySession(
                TouchIdentitySessionCommand(
                    sessionId = session.id,
                    expectedVersion = session.version,
                    lastUsedAt = renewedAt,
                    idleExpiresAt = renewedIdleExpiry
                )
            )
        ).value
        assertEquals(session.version + 1, renewed.version)
        assertEquals(renewedAt, renewed.lastUsedAt)
        assertEquals(renewedIdleExpiry, renewed.idleExpiresAt)
        assertEquals(renewed, assertSuccess(store.findSession(session.id)).value)
        assertEquals(0, backend.countCollection(config, "auditEvents"))

        val shortenedIdleExpiry = Instant.fromEpochMilliseconds(renewedAt.toEpochMilliseconds() + 600_000)
        val shortened = assertSuccess(
            store.touchIdentitySession(
                TouchIdentitySessionCommand(
                    sessionId = session.id,
                    expectedVersion = renewed.version,
                    lastUsedAt = renewedAt,
                    idleExpiresAt = shortenedIdleExpiry
                )
            )
        ).value
        assertEquals(renewed.version + 1, shortened.version)
        assertEquals(renewedAt, shortened.lastUsedAt)
        assertEquals(shortenedIdleExpiry, shortened.idleExpiresAt)

        assertEquals(
            IdentityStoreErrorCode.VERSION_CONFLICT,
            assertFailure(
                store.touchIdentitySession(
                    TouchIdentitySessionCommand(
                        sessionId = session.id,
                        expectedVersion = session.version,
                        lastUsedAt = renewedAt,
                        idleExpiresAt = renewedIdleExpiry
                    )
                )
            ).error.code
        )
        assertEquals(
            IdentityStoreErrorCode.INVALID_TRANSITION,
            assertFailure(
                store.touchIdentitySession(
                    TouchIdentitySessionCommand(
                        sessionId = session.id,
                        expectedVersion = shortened.version,
                        lastUsedAt = session.lastUsedAt,
                        idleExpiresAt = shortenedIdleExpiry
                    )
                )
            ).error.code
        )
        assertEquals(
            IdentityStoreErrorCode.INVALID_TRANSITION,
            assertFailure(
                store.touchIdentitySession(
                    TouchIdentitySessionCommand(
                        sessionId = session.id,
                        expectedVersion = shortened.version,
                        lastUsedAt = shortened.lastUsedAt,
                        idleExpiresAt = Instant.fromEpochMilliseconds(
                            shortened.absoluteExpiresAt.toEpochMilliseconds() + 1
                        )
                    )
                )
            ).error.code
        )
        assertEquals(shortened, assertSuccess(store.findSession(session.id)).value)

        val expired = session.copy(
            id = IdentityFixtures.sessionId("firestore-expired-idle-renewal"),
            familyId = IdentityFixtures.sessionId("firestore-expired-idle-renewal"),
            idleExpiresAt = renewedAt
        )
        backend.seed(
            config,
            "sessions",
            expired.id.value,
            expired,
            IdentitySession.serializer(),
            mapOf(
                "userId" to expired.userId.value,
                "state" to "active",
                "lastUsedAt" to expired.lastUsedAt.toString()
            )
        )
        assertEquals(
            IdentityStoreErrorCode.SESSION_EXPIRED,
            assertFailure(
                store.touchIdentitySession(
                    TouchIdentitySessionCommand(
                        sessionId = expired.id,
                        expectedVersion = expired.version,
                        lastUsedAt = expired.idleExpiresAt,
                        idleExpiresAt = renewedIdleExpiry
                    )
                )
            ).error.code
        )
        assertEquals(expired, assertSuccess(store.findSession(expired.id)).value)

        val revoked = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("firestore-revoked-idle-renewal"),
            state = SessionState.REVOKED
        )
        backend.seed(
            config,
            "sessions",
            revoked.id.value,
            revoked,
            IdentitySession.serializer(),
            mapOf(
                "userId" to revoked.userId.value,
                "state" to "revoked",
                "lastUsedAt" to revoked.lastUsedAt.toString()
            )
        )
        assertEquals(
            IdentityStoreErrorCode.SESSION_NOT_ACTIVE,
            assertFailure(
                store.touchIdentitySession(
                    TouchIdentitySessionCommand(
                        sessionId = revoked.id,
                        expectedVersion = revoked.version,
                        lastUsedAt = renewedAt,
                        idleExpiresAt = shortenedIdleExpiry
                    )
                )
            ).error.code
        )
        assertEquals(revoked, assertSuccess(store.findSession(revoked.id)).value)
        assertEquals(0, backend.countCollection(config, "auditEvents"))
    }

    @Test
    fun invitationEnrollmentIsSingleUseAndRollsBackEveryDependentWrite() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)
        val organization = IdentityFixtures.organization()
        backend.seed(
            config,
            "organizations",
            organization.id.value,
            organization,
            Organization.serializer(),
            mapOf("state" to "active", "slug" to organization.slug)
        )

        val invitation = IdentityFixtures.invitation(
            id = IdentityFixtures.invitationId("firestore-invitation-race"),
            organizationId = organization.id
        ).copy(email = EmailAddress("new-firestore-user@example.test"))
        createInvitation(store, invitation, "audit-firestore-invitation-race-create")
        val command = invitationEnrollmentCommand(invitation, "race", IdentityFixtures.instant(20_000))
        val raced = listOf(
            async { store.enrollInvitation(command) },
            async { store.enrollInvitation(command) }
        ).awaitAll()
        val committed = assertSuccess(
            raced.single { it is StoreResult.Success }
        ).value
        assertEquals(InvitationState.ACCEPTED, committed.invitation.state)
        assertEquals(SessionAuthenticationMethod.INVITATION, committed.enrollmentSession.authenticationMethod)
        assertEquals(
            IdentityStoreErrorCode.VERSION_CONFLICT,
            assertFailure(raced.single { it is StoreResult.Failure }).error.code
        )
        assertEquals(committed.user, assertSuccess(store.findUser(committed.user.id)).value)
        assertEquals(committed.membership, assertSuccess(store.findMembership(committed.membership.id)).value)
        assertEquals(committed.enrollmentSession, assertSuccess(store.findSession(committed.enrollmentSession.id)).value)

        val wrongTokenInvitation = IdentityFixtures.invitation(
            id = IdentityFixtures.invitationId("firestore-invitation-wrong-token"),
            organizationId = organization.id
        ).copy(email = EmailAddress("wrong-token-firestore@example.test"))
        createInvitation(store, wrongTokenInvitation, "audit-firestore-invitation-wrong-token-create")
        val wrongToken = invitationEnrollmentCommand(
            wrongTokenInvitation,
            "wrong-token",
            IdentityFixtures.instant(21_000)
        ).copy(expectedTokenDigest = wrongTokenInvitation.tokenDigest.copy(encoded = "wrong-token-digest"))
        assertEquals(
            IdentityStoreErrorCode.INVALID_TRANSITION,
            assertFailure(store.enrollInvitation(wrongToken)).error.code
        )
        assertInvitationEnrollmentRolledBack(store, wrongTokenInvitation, wrongToken)

        val expiredInvitation = IdentityFixtures.invitation(
            id = IdentityFixtures.invitationId("firestore-invitation-expired"),
            organizationId = organization.id
        ).copy(email = EmailAddress("expired-firestore@example.test"))
        createInvitation(store, expiredInvitation, "audit-firestore-invitation-expired-create")
        val expired = invitationEnrollmentCommand(expiredInvitation, "expired", expiredInvitation.expiresAt)
        assertEquals(
            IdentityStoreErrorCode.INVALID_TRANSITION,
            assertFailure(store.enrollInvitation(expired)).error.code
        )
        assertInvitationEnrollmentRolledBack(store, expiredInvitation, expired)

        val existingUser = IdentityFixtures.user(IdentityFixtures.userId("firestore-existing-user"))
        assertSuccess(
            store.applyScimMutation(
                ApplyScimMutationCommand(
                    IdentityFixtures.scimMutation(
                        IdentityFixtures.scimOperationId("firestore-existing-user-create"),
                        existingUser
                    ),
                    IdentityFixtures.auditEvent(
                        IdentityFixtures.auditEventId("audit-firestore-existing-user-create"),
                        AuditAction.SCIM_MUTATION_APPLIED,
                        existingUser.id.value
                    )
                )
            )
        )
        val duplicateEmailInvitation = IdentityFixtures.invitation(
            id = IdentityFixtures.invitationId("firestore-invitation-duplicate-email"),
            organizationId = organization.id
        ).copy(email = requireNotNull(existingUser.primaryEmail))
        createInvitation(store, duplicateEmailInvitation, "audit-firestore-invitation-duplicate-email-create")
        val duplicateEmail = invitationEnrollmentCommand(
            duplicateEmailInvitation,
            "duplicate-email",
            IdentityFixtures.instant(22_000)
        )
        assertEquals(
            IdentityStoreErrorCode.UNIQUE_CONSTRAINT,
            assertFailure(store.enrollInvitation(duplicateEmail)).error.code
        )
        assertInvitationEnrollmentRolledBack(store, duplicateEmailInvitation, duplicateEmail)
    }

    @Test
    fun scimBatchAtomicallyCreatesUserMembershipGroupAuditsAndIdempotencyReceipt() = runTest {
        val backend = FakeFirestoreDocumentTransport()
        val store = initializedStore(backend)
        val organization = IdentityFixtures.organization()
        backend.seed(
            config,
            "organizations",
            organization.id.value,
            organization,
            Organization.serializer(),
            mapOf("state" to "active", "slug" to organization.slug)
        )
        val user = IdentityFixtures.user(IdentityFixtures.userId("scim-batch-user"))
        val membership = IdentityFixtures.membership(
            id = IdentityFixtures.membershipId("scim-batch-membership"),
            userId = user.id,
            role = OrganizationRole.VIEWER
        )
        val provider = "test-directory:${organization.id.value}"
        val userMutation = scimMutationCommand(
            operationId = "scim-user-child",
            provider = provider,
            user = user,
            membership = null,
            type = ScimMutationType.UPSERT_USER,
            auditId = "audit-scim-user-child"
        )
        val membershipMutation = scimMutationCommand(
            operationId = "scim-membership-child",
            provider = provider,
            user = null,
            membership = membership,
            type = ScimMutationType.UPSERT_MEMBERSHIP,
            auditId = "audit-scim-membership-child"
        )
        val group = ScimGroup(
            id = "scim-batch-group",
            organizationId = organization.id,
            provider = provider,
            displayName = "SCIM batch group",
            memberUserIds = setOf(user.id),
            version = 1,
            createdAt = IdentityFixtures.instant(),
            updatedAt = IdentityFixtures.instant(1_000)
        )
        val command = ApplyScimBatchCommand(
            operationId = IdentityFixtures.scimOperationId("scim-batch"),
            organizationId = organization.id,
            provider = provider,
            mutations = listOf(userMutation, membershipMutation),
            group = group,
            expectedGroupVersion = 0,
            auditEvent = AuditEvent(
                id = IdentityFixtures.auditEventId("audit-scim-batch"),
                actor = AuditActor(AuditActorType.SYSTEM),
                organizationId = organization.id,
                action = AuditAction.SCIM_GROUP_CHANGED,
                target = AuditTarget(AuditTargetType.SCIM_GROUP, group.id),
                outcome = AuditOutcome.SUCCEEDED,
                occurredAt = IdentityFixtures.instant(1_000)
            )
        )

        val first = assertSuccess(store.applyScimBatch(command)).value
        assertEquals(false, first.alreadyApplied)
        assertEquals(user, assertSuccess(store.findUser(user.id)).value)
        assertEquals(membership, assertSuccess(store.findMembership(membership.id)).value)
        assertEquals(group, assertSuccess(store.findScimGroup(provider, organization.id, group.id)).value)

        val retry = assertSuccess(store.applyScimBatch(command)).value
        assertTrue(retry.alreadyApplied)
        assertNull(retry.auditEvent)
        assertTrue(retry.mutationCommits.all { it.alreadyApplied && it.auditEvent == null })

        val conflict = command.copy(group = group.copy(displayName = "Different payload"))
        assertEquals(
            IdentityStoreErrorCode.IDEMPOTENCY_CONFLICT,
            assertFailure(store.applyScimBatch(conflict)).error.code
        )
    }

    @Test
    fun accessTokenProviderCachesThenRefreshesBeforeExpiry() = runTest {
        val clock = DeterministicIdentityClock()
        var refreshCount = 0
        val provider = RefreshingFirestoreAccessTokenProvider(
            clock = clock,
            credentialSource = FirestoreOAuthCredentialSource {
                refreshCount += 1
                FirestoreAccessToken("token-$refreshCount", clock.now() + 120.seconds)
            },
            refreshSkew = 30.seconds
        )

        val first = provider.accessToken()
        assertEquals(first, provider.accessToken())
        assertEquals(1, refreshCount)
        clock.advanceMilliseconds(91_000)
        val second = provider.accessToken()
        assertEquals(2, refreshCount)
        kotlin.test.assertNotEquals(first, second)
        assertEquals("FirestoreAccessToken(value=<redacted>, expiresAt=${second.expiresAt})", second.toString())
    }

    @Test
    fun restTransportUsesV1TransactionEndpointAndBearerAuthorization() = runTest {
        val http = DeterministicIdentityHttpClient(
            listOf(IdentityHttpResponse(200, body = """{"transaction":"dHJhbnNhY3Rpb24"}""".encodeToByteArray()))
        )
        val runtime = DeterministicIdentityRuntime(deterministicHttp = http).runtime
        val transport = FirestoreRestTransport(
            config = config,
            runtime = runtime,
            accessTokens = FirestoreAccessTokenProvider {
                FirestoreAccessToken("test-oauth-token", IdentityFixtures.instant(60_000))
            }
        )

        assertEquals("dHJhbnNhY3Rpb24", transport.beginTransaction())
        val request = http.recordedRequests().single()
        assertEquals(IdentityHttpMethod.POST, request.method)
        assertEquals("${config.documentsRoot}:beginTransaction", request.url)
        assertEquals("Bearer test-oauth-token", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("FirestoreAccessToken(value=<redacted>, expiresAt=${IdentityFixtures.instant(60_000)})",
            FirestoreAccessToken("another-token", IdentityFixtures.instant(60_000)).toString())
        kotlin.test.assertContains(request.bodyBytes().decodeToString(), "readWrite")
    }

    @Test
    fun restTransportInvalidatesRejectedOAuthTokenAndRetriesExactlyOnce() = runTest {
        val clock = DeterministicIdentityClock()
        var refreshCount = 0
        val provider = RefreshingFirestoreAccessTokenProvider(
            clock = clock,
            credentialSource = FirestoreOAuthCredentialSource {
                refreshCount += 1
                FirestoreAccessToken("rotated-token-$refreshCount", clock.now() + 120.seconds)
            },
            refreshSkew = 30.seconds
        )
        val http = DeterministicIdentityHttpClient(
            listOf(
                IdentityHttpResponse(
                    403,
                    body = """{"error":{"status":"UNAUTHENTICATED","message":"provider detail"}}"""
                        .encodeToByteArray()
                ),
                IdentityHttpResponse(200, body = """{"transaction":"dHJhbnNhY3Rpb24"}""".encodeToByteArray())
            )
        )
        val transport = FirestoreRestTransport(
            config = config,
            runtime = DeterministicIdentityRuntime(deterministicHttp = http).runtime,
            accessTokens = provider
        )

        assertEquals("dHJhbnNhY3Rpb24", transport.beginTransaction())
        assertEquals(2, refreshCount)
        val requests = http.recordedRequests()
        assertEquals(2, requests.size)
        assertEquals("Bearer rotated-token-1", requests[0].headers["Authorization"])
        assertEquals("Bearer rotated-token-2", requests[1].headers["Authorization"])
    }

    @Test
    fun restTransportStopsAfterOneOAuthRetryAndRedactsRejectedTokens() = runTest {
        val clock = DeterministicIdentityClock()
        var refreshCount = 0
        val provider = RefreshingFirestoreAccessTokenProvider(
            clock = clock,
            credentialSource = FirestoreOAuthCredentialSource {
                refreshCount += 1
                FirestoreAccessToken("never-log-token-$refreshCount", clock.now() + 120.seconds)
            },
            refreshSkew = 30.seconds
        )
        val http = DeterministicIdentityHttpClient(
            listOf(
                IdentityHttpResponse(401, body = "rejected".encodeToByteArray()),
                IdentityHttpResponse(401, body = "rejected again".encodeToByteArray())
            )
        )
        val transport = FirestoreRestTransport(
            config = config,
            runtime = DeterministicIdentityRuntime(deterministicHttp = http).runtime,
            accessTokens = provider
        )

        val failure = assertFailsWith<FirestoreStoreException> { transport.beginTransaction() }
        assertEquals(2, refreshCount)
        assertEquals(2, http.recordedRequests().size)
        assertFalse(failure.toString().contains("never-log-token"))
        assertFalse(failure.message.orEmpty().contains("rejected"))
    }

    @Test
    fun restQueryWireAlwaysIncludesRequiredEnumsAndAcceptsEmulatorTermination() {
        val wire = defaultFirestoreJson().encodeToString(
            RunQueryRequest(
                FirestoreStructuredQuery(
                    from = listOf(FirestoreCollectionSelector("sessions")),
                    where = FirestoreFilter(
                        fieldFilter = FirestoreFieldFilter(
                            field = FirestoreFieldReference("userId"),
                            op = "EQUAL",
                            value = stringValue("user-1")
                        )
                    ),
                    orderBy = listOf(
                        FirestoreOrder(FirestoreFieldReference("updatedAt"), "DESCENDING")
                    )
                )
            )
        )
        kotlin.test.assertContains(wire, "\"op\":\"EQUAL\"")
        kotlin.test.assertContains(wire, "\"direction\":\"DESCENDING\"")

        val response = defaultFirestoreJson().decodeFromString<List<RunQueryResponse>>(
            """[{"readTime":"2026-07-15T00:00:00Z","done":true}]"""
        )
        assertEquals(true, response.single().done)
        assertNull(response.single().document)
    }

    private suspend fun initializedStore(backend: FakeFirestoreDocumentTransport): FirestoreIdentityStore =
        store(backend).also {
            assertIs<StoreResult.Success<Unit>>(it.provisionEnvironmentMarker())
            assertIs<StoreResult.Success<Unit>>(it.initialize())
        }

    private fun store(
        backend: FakeFirestoreDocumentTransport,
        storeConfig: FirestoreIdentityConfig = config
    ): FirestoreIdentityStore = FirestoreIdentityStore(
        config = storeConfig,
        runtime = DeterministicIdentityRuntime().runtime,
        transport = backend
    )

    private fun federationProviderAudit(
        id: String,
        action: AuditAction,
        organizationId: OrganizationId,
        storageKey: String,
        occurredAt: Instant,
        reasonCode: String? = null
    ): AuditEvent = IdentityFixtures.auditEvent(
        id = IdentityFixtures.auditEventId(id),
        action = action,
        targetId = storageKey
    ).copy(
        organizationId = organizationId,
        target = AuditTarget(AuditTargetType.FEDERATION_PROVIDER, storageKey),
        occurredAt = occurredAt,
        reasonCode = reasonCode
    )

    private fun sessionAudit(
        id: String,
        action: AuditAction,
        organizationId: OrganizationId,
        sessionId: SessionId,
        occurredAt: Instant
    ): AuditEvent = IdentityFixtures.auditEvent(
        id = IdentityFixtures.auditEventId(id),
        action = action,
        targetId = sessionId.value
    ).copy(
        organizationId = organizationId,
        target = AuditTarget(AuditTargetType.SESSION, sessionId.value),
        occurredAt = occurredAt
    )

    private fun audit(id: String, action: AuditAction): AuditEvent = IdentityFixtures.auditEvent(
        id = AuditEventId.parseOrNull(id) ?: IdentityFixtures.auditEventId(id),
        action = action
    )

    private suspend fun createInvitation(
        store: FirestoreIdentityStore,
        invitation: Invitation,
        auditId: String
    ) {
        val event = IdentityFixtures.auditEvent(
            IdentityFixtures.auditEventId(auditId),
            AuditAction.INVITATION_CREATED,
            invitation.id.value
        ).copy(
            organizationId = invitation.organizationId,
            target = AuditTarget(AuditTargetType.INVITATION, invitation.id.value)
        )
        assertEquals(
            invitation,
            assertSuccess(store.createInvitation(CreateInvitationCommand(invitation, event))).value
        )
    }

    private fun invitationEnrollmentCommand(
        invitation: Invitation,
        suffix: String,
        enrolledAt: kotlin.time.Instant
    ): EnrollInvitationCommand {
        val user = IdentityFixtures.user(IdentityFixtures.userId("firestore-invited-user-$suffix")).copy(
            primaryEmail = invitation.email,
            createdAt = enrolledAt,
            updatedAt = enrolledAt,
            activatedAt = enrolledAt
        )
        val membership = IdentityFixtures.membership(
            id = IdentityFixtures.membershipId("firestore-invited-membership-$suffix"),
            organizationId = invitation.organizationId,
            userId = user.id,
            role = invitation.role
        ).copy(createdAt = enrolledAt, updatedAt = enrolledAt)
        val expiresAt = kotlin.time.Instant.fromEpochMilliseconds(enrolledAt.toEpochMilliseconds() + 900_000)
        val session = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("firestore-invited-session-$suffix"),
            userId = user.id,
            assurance = AuthenticationAssurance.RECOVERY,
            authenticationMethod = SessionAuthenticationMethod.INVITATION,
            createdAt = enrolledAt
        ).copy(idleExpiresAt = expiresAt, absoluteExpiresAt = expiresAt)
        val event = IdentityFixtures.auditEvent(
            IdentityFixtures.auditEventId("audit-firestore-invitation-enrollment-$suffix"),
            AuditAction.INVITATION_ACCEPTED,
            invitation.id.value
        ).copy(
            organizationId = invitation.organizationId,
            target = AuditTarget(AuditTargetType.INVITATION, invitation.id.value),
            occurredAt = enrolledAt
        )
        return EnrollInvitationCommand(
            invitationId = invitation.id,
            expectedInvitationVersion = invitation.version,
            expectedTokenDigest = invitation.tokenDigest,
            user = user,
            membership = membership,
            enrollmentSession = session,
            enrolledAt = enrolledAt,
            auditEvent = event
        )
    }

    private suspend fun assertInvitationEnrollmentRolledBack(
        store: FirestoreIdentityStore,
        invitation: Invitation,
        command: EnrollInvitationCommand
    ) {
        assertEquals(InvitationState.PENDING, assertSuccess(store.findInvitation(invitation.id)).value?.state)
        assertNull(assertSuccess(store.findUser(command.user.id)).value)
        assertNull(assertSuccess(store.findMembership(command.membership.id)).value)
        assertNull(assertSuccess(store.findSession(command.enrollmentSession.id)).value)
    }

    private fun scimMutationCommand(
        operationId: String,
        provider: String,
        user: User?,
        membership: Membership?,
        type: ScimMutationType,
        auditId: String
    ): ApplyScimMutationCommand {
        val target = membership?.let { AuditTarget(AuditTargetType.MEMBERSHIP, it.id.value) }
            ?: AuditTarget(AuditTargetType.USER, requireNotNull(user).id.value)
        return ApplyScimMutationCommand(
            mutation = ScimMutation(
                operationId = IdentityFixtures.scimOperationId(operationId),
                provider = provider,
                type = type,
                externalSubject = ExternalSubject("subject-scim-batch-user"),
                user = user,
                membership = membership,
                occurredAt = IdentityFixtures.instant(1_000)
            ),
            auditEvent = AuditEvent(
                id = IdentityFixtures.auditEventId(auditId),
                actor = AuditActor(AuditActorType.SYSTEM),
                organizationId = IdentityFixtures.organizationId(),
                action = AuditAction.SCIM_MUTATION_APPLIED,
                target = target,
                outcome = AuditOutcome.SUCCEEDED,
                occurredAt = IdentityFixtures.instant(1_000)
            )
        )
    }

    private fun <T> assertSuccess(result: StoreResult<T>): StoreResult.Success<T> = assertIs(result)
    private fun assertFailure(result: StoreResult<*>): StoreResult.Failure = assertIs(result)
}

private class FakeFirestoreDocumentTransport : FirestoreDocumentTransport {
    private val documents = mutableMapOf<String, FirestoreDocument>()
    private var updateCounter = 0L
    var abortNextCommit: Boolean = false
    var transactionsBegun: Int = 0
        private set
    val queries = mutableListOf<FirestoreStructuredQuery>()

    fun documentEndingWith(suffix: String): FirestoreDocument =
        documents.values.single { it.name.endsWith(suffix) }

    fun countCollection(config: FirestoreIdentityConfig, collection: String): Int {
        val prefix = "projects/${config.projectId}/databases/${config.databaseId}/documents/" +
            "${config.namespaceDocument}/$collection/"
        return documents.keys.count { name ->
            name.startsWith(prefix) && '/' !in name.substring(prefix.length)
        }
    }

    override suspend fun get(documentName: String): FirestoreDocument? = documents[documentName]

    override suspend fun beginTransaction(): String = "transaction-${++transactionsBegun}"

    override suspend fun batchGet(
        documentNames: List<String>,
        transaction: String
    ): Map<String, FirestoreDocument?> = documentNames.associateWith { documents[it] }

    override suspend fun runQuery(
        parent: String,
        query: FirestoreStructuredQuery,
        transaction: String?
    ): List<FirestoreDocument> {
        queries += query
        val collection = query.from.single().collectionId
        val prefix = "$parent/$collection/"
        val filter = query.where?.fieldFilter
        val comparator = Comparator<FirestoreDocument> { left, right ->
            for (order in query.orderBy) {
                val compared = compareValues(
                    left.fields[order.field.fieldPath],
                    right.fields[order.field.fieldPath]
                )
                if (compared != 0) {
                    return@Comparator if (order.direction == "DESCENDING") -compared else compared
                }
            }
            left.name.compareTo(right.name)
        }
        var selected = documents.values.filter { document ->
            document.name.startsWith(prefix) && document.name.substring(prefix.length).let { '/' !in it } &&
                (filter == null || when (filter.op) {
                    "EQUAL" -> document.fields[filter.field.fieldPath] == filter.value
                    "LESS_THAN" -> compareValues(document.fields[filter.field.fieldPath], filter.value) < 0
                    else -> error("Unsupported fake Firestore filter ${filter.op}")
                })
        }.let { values ->
            if (query.orderBy.isEmpty()) values.sortedBy { it.name } else values.sortedWith(comparator)
        }
        query.startAt?.let { cursor ->
            require(cursor.values.size == query.orderBy.size)
            selected = selected.filter { document ->
                val compared = compareDocumentToCursor(document, query.orderBy, cursor.values)
                if (cursor.before) compared >= 0 else compared > 0
            }
        }
        return query.limit?.let(selected::take) ?: selected
    }

    override suspend fun commit(transaction: String?, writes: List<FirestoreWrite>): CommitResponse {
        if (abortNextCommit) {
            abortNextCommit = false
            throw FirestoreStoreException(
                FirestoreFailureMapper.versionConflict(),
                transactionRetryable = true
            )
        }
        writes.forEach(::validate)
        writes.forEach { write ->
            write.delete?.let { documents.remove(it) }
            write.update?.let { update ->
                documents[update.name] = update.copy(updateTime = "update-${++updateCounter}")
            }
        }
        return CommitResponse(commitTime = "commit-${updateCounter}")
    }

    override suspend fun rollback(transaction: String) = Unit

    fun <T> seed(
        config: FirestoreIdentityConfig,
        collection: String,
        id: String,
        value: T,
        serializer: KSerializer<T>,
        indexed: Map<String, String> = emptyMap()
    ) {
        val fields = mutableMapOf(
            "payload" to stringValue(defaultFirestoreJson().encodeToString(serializer, value)),
            "entityId" to stringValue(id),
            "environment" to stringValue(config.environment.wireName),
            "namespace" to stringValue(config.namespace),
            "schemaVersion" to integerValue(FirestoreIdentityStore.FIRESTORE_SCHEMA_VERSION.toLong())
        )
        indexed.forEach { (key, item) -> fields[key] = stringValue(item) }
        val name = "projects/${config.projectId}/databases/${config.databaseId}/documents/" +
            "${config.namespaceDocument}/$collection/$id"
        documents[name] = FirestoreDocument(name, fields, updateTime = "update-${++updateCounter}")
    }

    fun corruptEnvironmentMarker(config: FirestoreIdentityConfig, environment: String) {
        val name = "projects/${config.projectId}/databases/${config.databaseId}/documents/" +
            config.environmentMarkerDocument
        val current = requireNotNull(documents[name])
        documents[name] = current.copy(
            fields = current.fields + ("environment" to stringValue(environment)),
            updateTime = "update-${++updateCounter}"
        )
    }

    private fun validate(write: FirestoreWrite) {
        val name = write.update?.name ?: requireNotNull(write.delete)
        val existing = documents[name]
        write.currentDocument?.exists?.let { expected ->
            if ((existing != null) != expected) {
                throw FirestoreStoreException(FirestoreFailureMapper.versionConflict())
            }
        }
        write.currentDocument?.updateTime?.let { expected ->
            if (existing?.updateTime != expected) {
                throw FirestoreStoreException(FirestoreFailureMapper.versionConflict())
            }
        }
    }

    private fun compareDocumentToCursor(
        document: FirestoreDocument,
        orderBy: List<FirestoreOrder>,
        values: List<FirestoreValue>
    ): Int {
        orderBy.forEachIndexed { index, order ->
            val compared = compareValues(document.fields[order.field.fieldPath], values[index])
            if (compared != 0) return if (order.direction == "DESCENDING") -compared else compared
        }
        return 0
    }

    private fun compareValues(left: FirestoreValue?, right: FirestoreValue?): Int = when {
        left?.timestampValue != null && right?.timestampValue != null ->
            kotlin.time.Instant.parse(left.timestampValue).compareTo(kotlin.time.Instant.parse(right.timestampValue))
        else -> sortableValue(left).compareTo(sortableValue(right))
    }

    private fun sortableValue(value: FirestoreValue?): String = when {
        value == null -> ""
        value.stringValue != null -> value.stringValue
        value.timestampValue != null -> value.timestampValue
        value.integerValue != null -> value.integerValue.padStart(20, '0')
        value.referenceValue != null -> value.referenceValue
        else -> value.toString()
    }
}
