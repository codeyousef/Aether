package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryIdentityStoreInvariantTest {
    @Test
    fun `session create rotate revoke and epoch revocation preserve CAS invariants`() = runTest {
        val user = IdentityFixtures.user()
        val session = IdentityFixtures.session()
        val store = InMemoryIdentityStore(InMemoryIdentityStoreSeed(users = listOf(user)))
        store.createSession(
            CreateSessionCommand(
                session,
                IdentityFixtures.auditEvent(AuditEventId("audit-session-create"), AuditAction.SESSION_CREATED)
            )
        ).expectSuccess()

        val rotatedAt = IdentityFixtures.instant(1_000)
        val replacement = IdentityFixtures.session(
            id = SessionId("session-2"),
            familyId = session.familyId,
            rotationCounter = 1,
            createdAt = rotatedAt,
            rotatedFromId = session.id
        )
        val rotation = store.rotateSession(
            RotateSessionCommand(
                sessionId = session.id,
                expectedVersion = 0,
                replacement = replacement,
                rotatedAt = rotatedAt,
                auditEvent = IdentityFixtures.auditEvent(
                    AuditEventId("audit-session-rotate"),
                    AuditAction.SESSION_ROTATED
                )
            )
        ).expectSuccess()
        assertEquals(SessionState.ROTATED, rotation.previous.state)

        val revoked = store.revokeSession(
            RevokeSessionCommand(
                sessionId = replacement.id,
                expectedVersion = 0,
                revokedAt = IdentityFixtures.instant(2_000),
                reasonCode = "test_logout",
                auditEvent = IdentityFixtures.auditEvent(
                    AuditEventId("audit-session-revoke"),
                    AuditAction.SESSION_REVOKED
                )
            )
        ).expectSuccess()
        assertEquals(SessionState.REVOKED, revoked.state)

        val otherStore = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                sessions = listOf(
                    IdentityFixtures.session(SessionId("epoch-session-1")),
                    IdentityFixtures.session(SessionId("epoch-session-2"))
                )
            )
        )
        val epochCommit = otherStore.revokeUserSessions(
            RevokeUserSessionsCommand(
                userId = user.id,
                expectedUserVersion = 0,
                expectedSessionEpoch = 0,
                newSessionEpoch = 1,
                exceptSessionId = SessionId("epoch-session-1"),
                revokedAt = IdentityFixtures.instant(3_000),
                reasonCode = "security_change",
                auditEvent = IdentityFixtures.auditEvent(
                    AuditEventId("audit-epoch-revoke"),
                    AuditAction.SESSION_REVOKED
                )
            )
        ).expectSuccess()
        assertEquals(1, epochCommit.user.sessionEpoch)
        assertEquals(listOf(SessionId("epoch-session-2")), epochCommit.revokedSessionIds)
        assertEquals(1, otherStore.findSession(SessionId("epoch-session-1")).expectSuccess()?.userSessionEpoch)
    }

    @Test
    fun `recovery replacement and consumption are generation-bound and single-use`() = runTest {
        val user = IdentityFixtures.user()
        val store = InMemoryIdentityStore(InMemoryIdentityStoreSeed(users = listOf(user)))
        val codes = List(10) { index ->
            IdentityFixtures.recoveryCode(RecoveryCodeId("recovery-${index + 1}"), generation = 0)
        }
        store.replaceRecoveryCodes(
            ReplaceRecoveryCodesCommand(
                userId = user.id,
                expectedGeneration = null,
                newGeneration = 0,
                codes = codes,
                auditEvent = IdentityFixtures.auditEvent(
                    AuditEventId("audit-recovery-replace"),
                    AuditAction.RECOVERY_CODES_REPLACED
                )
            )
        ).expectSuccess()

        val consumedAt = IdentityFixtures.instant(1_000)
        val commands = listOf("a", "b").map { suffix ->
            ConsumeRecoveryCodeCommand(
                recoveryCodeId = codes.first().id,
                expectedVersion = 0,
                consumedAt = consumedAt,
                recoverySession = IdentityFixtures.session(
                    id = SessionId("recovery-session-$suffix"),
                    assurance = AuthenticationAssurance.RECOVERY,
                    createdAt = consumedAt
                ),
                auditEvent = IdentityFixtures.auditEvent(
                    AuditEventId("audit-recovery-$suffix"),
                    AuditAction.RECOVERY_CODE_USED
                )
            )
        }
        val results = commands.map { async { store.consumeRecoveryCode(it) } }.awaitAll()
        assertEquals(1, results.count { it is StoreResult.Success })
        assertEquals(1, results.count {
            it is StoreResult.Failure && it.error.code == IdentityStoreErrorCode.RECOVERY_CODE_NOT_ACTIVE
        })
        assertEquals(1, store.snapshot().sessions.size)
    }

    @Test
    fun `last active owner cannot be demoted or removed`() = runTest {
        val user = IdentityFixtures.user()
        val organization = IdentityFixtures.organization()
        val owner = IdentityFixtures.membership()
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                organizations = listOf(organization),
                memberships = listOf(owner)
            )
        )
        val demoted = IdentityFixtures.membership(
            id = owner.id,
            role = OrganizationRole.ADMIN,
            version = 1
        )
        store.mutateMembership(
            MutateMembershipCommand(
                owner.id,
                0,
                demoted,
                IdentityFixtures.auditEvent(AuditEventId("audit-owner-demote"), AuditAction.MEMBERSHIP_CHANGED)
            )
        ).expectFailure(IdentityStoreErrorCode.LAST_OWNER)
        assertEquals(OrganizationRole.OWNER, store.findMembership(owner.id).expectSuccess()?.role)
    }

    @Test
    fun `membership can change after another active owner exists`() = runTest {
        val user1 = IdentityFixtures.user()
        val user2 = IdentityFixtures.user(UserId("user-2"))
        val organization = IdentityFixtures.organization()
        val owner1 = IdentityFixtures.membership()
        val owner2 = IdentityFixtures.membership(
            id = MembershipId("membership-2"),
            userId = user2.id
        )
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user1, user2),
                organizations = listOf(organization),
                memberships = listOf(owner1, owner2)
            )
        )
        val replacement = IdentityFixtures.membership(
            id = owner1.id,
            role = OrganizationRole.ADMIN,
            version = 1
        )

        assertEquals(
            OrganizationRole.ADMIN,
            store.mutateMembership(
                MutateMembershipCommand(
                    owner1.id,
                    0,
                    replacement,
                    IdentityFixtures.auditEvent(AuditEventId("audit-owner-safe"), AuditAction.MEMBERSHIP_CHANGED)
                )
            ).expectSuccess().role
        )
    }

    @Test
    fun `stale membership CAS is retryable and leaves audit untouched`() = runTest {
        val user1 = IdentityFixtures.user()
        val user2 = IdentityFixtures.user(UserId("user-2"))
        val organization = IdentityFixtures.organization()
        val owner1 = IdentityFixtures.membership()
        val owner2 = IdentityFixtures.membership(MembershipId("membership-2"), userId = user2.id)
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user1, user2),
                organizations = listOf(organization),
                memberships = listOf(owner1, owner2)
            )
        )
        val result = store.mutateMembership(
            MutateMembershipCommand(
                membershipId = owner1.id,
                expectedVersion = 1,
                replacement = IdentityFixtures.membership(
                    id = owner1.id,
                    role = OrganizationRole.ADMIN,
                    version = 2
                ),
                auditEvent = IdentityFixtures.auditEvent(
                    AuditEventId("audit-stale-membership"),
                    AuditAction.MEMBERSHIP_CHANGED
                )
            )
        )
        val failure = result.expectFailure(IdentityStoreErrorCode.VERSION_CONFLICT)
        assertTrue(failure.error.retryable)
        assertEquals(emptyList(), store.snapshot().auditEvents)
    }

    @Test
    fun `device grant CAS race has one insertion and one retryable conflict`() = runTest {
        val grant = IdentityFixtures.deviceGrant()
        val command = CompareAndSetDeviceGrantCommand(
            expectedVersion = null,
            replacement = grant,
            auditEvent = IdentityFixtures.auditEvent(
                AuditEventId("audit-device-create"),
                AuditAction.DEVICE_GRANT_CHANGED
            )
        )
        val store = InMemoryIdentityStore()
        val results = listOf(async { store.compareAndSetDeviceGrant(command) }, async {
            store.compareAndSetDeviceGrant(command)
        }).awaitAll()

        assertEquals(1, results.count { it is StoreResult.Success })
        val failure = results.filterIsInstance<StoreResult.Failure>().single()
        assertEquals(IdentityStoreErrorCode.VERSION_CONFLICT, failure.error.code)
        assertTrue(failure.error.retryable)
    }

    @Test
    fun `membership creation service rotation replay and SCIM idempotency use atomic commands`() = runTest {
        val user = IdentityFixtures.user()
        val organization = IdentityFixtures.organization()
        val providerControl = IdentityFixtures.federationProviderControl(organizationId = organization.id)
        val providerLease = IdentityFixtures.federationProviderLease(providerControl)
        val serviceIdentity = IdentityFixtures.serviceIdentity()
        val serviceCredential = IdentityFixtures.serviceCredential()
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                organizations = listOf(organization),
                federationProviderControls = listOf(providerControl),
                serviceIdentities = listOf(serviceIdentity),
                serviceCredentials = listOf(serviceCredential)
            )
        )

        val membership = IdentityFixtures.membership(role = OrganizationRole.VIEWER)
        store.createMembership(
            CreateMembershipCommand(
                membership = membership,
                auditEvent = IdentityFixtures.auditEvent(
                    AuditEventId("audit-membership-create"),
                    AuditAction.MEMBERSHIP_CREATED
                )
            )
        ).expectSuccess()

        val credentialReplacement = IdentityFixtures.serviceCredential(ServiceCredentialId("service-credential-2"))
        store.rotateServiceCredential(
            RotateServiceCredentialCommand(
                credentialId = serviceCredential.id,
                expectedVersion = 0,
                replacement = credentialReplacement,
                rotatedAt = IdentityFixtures.instant(1_000),
                auditEvent = IdentityFixtures.auditEvent(
                    AuditEventId("audit-service-rotate"),
                    AuditAction.SERVICE_CREDENTIAL_ROTATED
                )
            )
        ).expectSuccess()

        val replay = IdentityFixtures.replayReceipt()
        store.recordExternalIdentityReplay(RecordExternalIdentityReplayCommand(replay, providerLease)).expectSuccess()
        store.recordExternalIdentityReplay(RecordExternalIdentityReplayCommand(replay, providerLease))
            .expectFailure(IdentityStoreErrorCode.REPLAY_DETECTED)

        val scimUser = IdentityFixtures.user(UserId("scim-user"))
        val mutation = IdentityFixtures.scimMutation(user = scimUser)
        val scimCommand = ApplyScimMutationCommand(
            mutation,
            IdentityFixtures.auditEvent(AuditEventId("audit-scim"), AuditAction.SCIM_MUTATION_APPLIED)
        )
        assertEquals(false, store.applyScimMutation(scimCommand).expectSuccess().alreadyApplied)
        val retry = store.applyScimMutation(scimCommand).expectSuccess()
        assertTrue(retry.alreadyApplied)
        assertEquals(null, retry.auditEvent)

        val conflictingMutation = IdentityFixtures.scimMutation(
            operationId = mutation.operationId,
            user = IdentityFixtures.user(UserId("different-scim-user"))
        )
        store.applyScimMutation(
            ApplyScimMutationCommand(
                conflictingMutation,
                IdentityFixtures.auditEvent(AuditEventId("audit-scim-conflict"), AuditAction.SCIM_MUTATION_APPLIED)
            )
        ).expectFailure(IdentityStoreErrorCode.IDEMPOTENCY_CONFLICT)
    }

    @Test
    fun `external identity link couples replay receipt and audit`() = runTest {
        val user = IdentityFixtures.user()
        val organization = IdentityFixtures.organization()
        val providerControl = IdentityFixtures.federationProviderControl(organizationId = organization.id)
        val providerLease = IdentityFixtures.federationProviderLease(providerControl)
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                organizations = listOf(organization),
                federationProviderControls = listOf(providerControl)
            )
        )
        val identity = IdentityFixtures.externalIdentity()
        val replay = IdentityFixtures.replayReceipt()
        val audit = IdentityFixtures.auditEvent(
            AuditEventId("audit-external-link"),
            AuditAction.EXTERNAL_IDENTITY_LINKED
        ).copy(organizationId = organization.id)

        store.linkExternalIdentity(LinkExternalIdentityCommand(identity, replay, providerLease, audit)).expectSuccess()
        assertEquals(identity, store.findExternalIdentity(identity.provider, identity.subject).expectSuccess())
        assertEquals(listOf(replay), store.snapshot().replayReceipts)
        assertEquals(listOf(audit), store.snapshot().auditEvents)
    }
}
