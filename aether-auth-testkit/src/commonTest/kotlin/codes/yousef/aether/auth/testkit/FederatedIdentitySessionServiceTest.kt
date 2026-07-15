package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class FederatedIdentitySessionServiceTest {
    @Test
    fun `federated issuance requires an active organization membership`() = runTest {
        val fixture = FederatedSessionFixture()
        val suspendedOrganization = fixture.organization.copy(state = OrganizationState.SUSPENDED)
        val suspendedMembership = fixture.membership.copy(state = MembershipState.SUSPENDED)
        val seeds = listOf(
            InMemoryIdentityStoreSeed(users = listOf(fixture.user)),
            InMemoryIdentityStoreSeed(
                users = listOf(fixture.user),
                organizations = listOf(suspendedOrganization),
                memberships = listOf(fixture.membership),
                federationProviderControls = listOf(fixture.providerControl)
            ),
            InMemoryIdentityStoreSeed(
                users = listOf(fixture.user),
                organizations = listOf(fixture.organization),
                federationProviderControls = listOf(fixture.providerControl)
            ),
            InMemoryIdentityStoreSeed(
                users = listOf(fixture.user),
                organizations = listOf(fixture.organization),
                memberships = listOf(suspendedMembership),
                federationProviderControls = listOf(fixture.providerControl)
            )
        )

        for (seed in seeds) {
            val store = InMemoryIdentityStore(seed)
            val result = FederatedIdentitySessionService(store, fixture.runtime.runtime, fixture.config)
                .create(fixture.request())

            assertEquals(
                IdentityErrorCode.INVALID_CREDENTIALS,
                assertIs<IdentityOperationResult.Failure>(result).code
            )
            assertEquals(emptyList(), store.snapshot().sessions)
            assertEquals(emptyList(), store.snapshot().auditEvents)
        }
    }

    @Test
    fun `federated callback atomically rotates an authenticated predecessor`() = runTest {
        val fixture = FederatedSessionFixture()
        val predecessor = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("federation-predecessor"),
            userId = fixture.user.id
        )
        val store = fixture.store(sessions = listOf(predecessor))

        val result = FederatedIdentitySessionService(store, fixture.runtime.runtime, fixture.config).create(
            fixture.request(
                predecessorSessionId = predecessor.id,
                expectedPredecessorVersion = predecessor.version
            )
        )

        val issued = assertIs<IdentityOperationResult.Success<IssuedIdentitySession>>(result).value
        val sessions = store.snapshot().sessions.associateBy(IdentitySession::id)
        val rotated = sessions.getValue(predecessor.id)
        val replacement = sessions.getValue(issued.session.id)
        assertNotEquals(predecessor.id, replacement.id)
        assertEquals(SessionState.ROTATED, rotated.state)
        assertEquals(replacement.id, rotated.rotatedToId)
        assertNull(rotated.revokedAt)
        assertEquals(SessionState.ACTIVE, replacement.state)
        assertEquals(predecessor.familyId, replacement.familyId)
        assertEquals(predecessor.rotationCounter + 1, replacement.rotationCounter)
        assertEquals(predecessor.id, replacement.rotatedFromId)
        assertEquals(fixture.organization.id, replacement.federationOrganizationId)
        assertEquals(fixture.providerLease.sessionEpoch, replacement.federationProviderSessionEpoch)
        assertEquals(AuditAction.SESSION_ROTATED, store.snapshot().auditEvents.single().action)
    }

    @Test
    fun `only one concurrent federated callback can rotate a predecessor`() = runTest {
        val fixture = FederatedSessionFixture()
        val predecessor = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("raced-federation-predecessor"),
            userId = fixture.user.id
        )
        val store = fixture.store(sessions = listOf(predecessor))
        val service = FederatedIdentitySessionService(store, fixture.runtime.runtime, fixture.config)
        val request = fixture.request(
            predecessorSessionId = predecessor.id,
            expectedPredecessorVersion = predecessor.version
        )

        val results = listOf(
            async { service.create(request) },
            async { service.create(request) }
        ).awaitAll()

        assertEquals(1, results.count { it is IdentityOperationResult.Success })
        assertEquals(1, results.count { it is IdentityOperationResult.Failure })
        assertTrue(
            (results.single { it is IdentityOperationResult.Failure } as IdentityOperationResult.Failure).code in
                setOf(IdentityErrorCode.INVALID_CREDENTIALS, IdentityErrorCode.CONFLICT)
        )
        val snapshot = store.snapshot()
        assertEquals(2, snapshot.sessions.size)
        assertEquals(SessionState.ROTATED, snapshot.sessions.single { it.id == predecessor.id }.state)
        assertEquals(1, snapshot.sessions.count { it.state == SessionState.ACTIVE })
        assertEquals(listOf(AuditAction.SESSION_ROTATED), snapshot.auditEvents.map { it.action })
    }

    @Test
    fun `stale predecessor cannot leave an orphan federated session`() = runTest {
        val fixture = FederatedSessionFixture()
        val predecessor = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("stale-federation-predecessor"),
            userId = fixture.user.id,
            version = 1
        )
        val store = fixture.store(sessions = listOf(predecessor))

        val result = FederatedIdentitySessionService(store, fixture.runtime.runtime, fixture.config).create(
            fixture.request(
                predecessorSessionId = predecessor.id,
                expectedPredecessorVersion = 0
            )
        )

        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            assertIs<IdentityOperationResult.Failure>(result).code
        )
        assertEquals(listOf(predecessor), store.snapshot().sessions)
        assertEquals(emptyList(), store.snapshot().auditEvents)
    }

    @Test
    fun `federated callback cannot rotate another users session`() = runTest {
        val fixture = FederatedSessionFixture()
        val otherUser = IdentityFixtures.user(IdentityFixtures.userId("other-federated-user"))
        val otherSession = IdentityFixtures.session(
            id = IdentityFixtures.sessionId("other-user-predecessor"),
            userId = otherUser.id
        )
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(fixture.user, otherUser),
                sessions = listOf(otherSession),
                organizations = listOf(fixture.organization),
                memberships = listOf(fixture.membership),
                federationProviderControls = listOf(fixture.providerControl)
            )
        )

        val result = FederatedIdentitySessionService(store, fixture.runtime.runtime, fixture.config).create(
            fixture.request(
                predecessorSessionId = otherSession.id,
                expectedPredecessorVersion = otherSession.version
            )
        )

        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            assertIs<IdentityOperationResult.Failure>(result).code
        )
        assertEquals(listOf(otherSession), store.snapshot().sessions)
        assertEquals(emptyList(), store.snapshot().auditEvents)
    }
}

private class FederatedSessionFixture {
    val config = federatedIdentityConfig()
    val runtime = DeterministicIdentityRuntime(
        deterministicSecrets = DeterministicIdentitySecretResolver(
            mapOf(config.keys.sessionPepper to ByteArray(32) { 0x44 })
        )
    )
    val user = IdentityFixtures.user(IdentityFixtures.userId("federated-user"))
    val organization = IdentityFixtures.organization(
        id = IdentityFixtures.organizationId("federated-organization"),
        slug = "federated-organization"
    )
    val membership = IdentityFixtures.membership(
        id = IdentityFixtures.membershipId("federated-membership"),
        organizationId = organization.id,
        userId = user.id,
        role = OrganizationRole.VIEWER
    )
    val providerControl = IdentityFixtures.federationProviderControl(
        organizationId = organization.id,
        providerId = "workforce"
    )
    val providerLease = IdentityFixtures.federationProviderLease(providerControl)

    fun store(sessions: List<IdentitySession> = emptyList()) = InMemoryIdentityStore(
        InMemoryIdentityStoreSeed(
            users = listOf(user),
            sessions = sessions,
            organizations = listOf(organization),
            memberships = listOf(membership),
            federationProviderControls = listOf(providerControl)
        )
    )

    fun request(
        predecessorSessionId: SessionId? = null,
        expectedPredecessorVersion: Long? = null
    ) = FederatedIdentitySessionRequest(
        userId = user.id,
        providerLease = providerLease,
        externalIdentityId = IdentityFixtures.externalIdentityId("federated-external"),
        authenticationMethod = SessionAuthenticationMethod.OIDC,
        authenticatedAt = runtime.deterministicClock.now(),
        predecessorSessionId = predecessorSessionId,
        expectedPredecessorVersion = expectedPredecessorVersion
    )
}

private fun federatedIdentityConfig(): IdentityConfig {
    fun secret(name: String) = SecretReference("test", name, "v1", IdentityEnvironment.TEST)
    return IdentityConfig(
        environment = IdentityEnvironment.TEST,
        publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig(
            id = "localhost",
            name = "Federated session test",
            allowedOrigins = setOf("http://localhost:8080")
        ),
        keys = IdentityKeyConfig(
            sessionPepper = secret("session"),
            recoveryPepper = secret("recovery"),
            deviceTokenPepper = secret("device"),
            serviceCredentialPepper = secret("service"),
            auditPseudonymizationKey = secret("audit"),
            encryptionKey = secret("encryption"),
            signingKey = secret("signing")
        )
    )
}
