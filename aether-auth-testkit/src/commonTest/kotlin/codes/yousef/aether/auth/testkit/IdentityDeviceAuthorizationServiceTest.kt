package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityDeviceAuthorizationServiceTest {
    @Test
    fun `start returns 256-bit device codes and unambiguous 40-bit user codes`() = runTest {
        val fixture = DeviceAuthorizationFixture()

        val first = fixture.start(setOf(Capability.ORGANIZATION_READ))
        val second = fixture.start(setOf(Capability.ORGANIZATION_READ))

        assertEquals(32, Base64Url.decode(first.deviceCode).size)
        assertTrue(USER_CODE_PATTERN.matches(first.userCode))
        assertEquals(8, first.userCode.count { it.isLetterOrDigit() })
        assertNotEquals(first.deviceCode, second.deviceCode)
        assertNotEquals(first.userCode, second.userCode)
        assertEquals("http://localhost:8080/identity", first.verificationUri)
        assertEquals(600L, first.expiresIn)
        assertEquals(5, first.interval)

        val snapshot = fixture.store.snapshot()
        assertEquals(2, snapshot.deviceGrants.size)
        snapshot.deviceGrants.forEach { grant ->
            assertEquals(DEVICE_CLIENT_ID, grant.clientId)
            assertEquals(DigestAlgorithm.HMAC_SHA256, grant.deviceCodeDigest.algorithm)
            assertEquals(fixture.config.keys.deviceTokenPepper.version, grant.deviceCodeDigest.keyVersion)
            assertEquals(32, Base64Url.decode(grant.deviceCodeDigest.encoded).size)
            assertEquals(32, Base64Url.decode(grant.userCodeDigest.encoded).size)
        }
        assertFalse(snapshot.toString().contains(first.deviceCode))
        assertFalse(snapshot.toString().contains(first.userCode))
    }

    @Test
    fun `pending polls enforce the five-second interval and cumulative slow down`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val started = fixture.start(setOf(Capability.ORGANIZATION_READ))

        fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID)
            .expectError(OAuthDeviceErrorCode.AUTHORIZATION_PENDING, interval = 5)
        fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID)
            .expectError(OAuthDeviceErrorCode.SLOW_DOWN, interval = 10)

        fixture.runtime.deterministicClock.advanceMilliseconds(10_000)
        fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID)
            .expectError(OAuthDeviceErrorCode.AUTHORIZATION_PENDING, interval = 10)

        val grant = fixture.store.snapshot().deviceGrants.single()
        assertEquals(DeviceGrantState.PENDING, grant.state)
        assertEquals(3, grant.pollCount)
        assertEquals(10, grant.pollingIntervalSeconds)
        assertEquals(fixture.runtime.deterministicClock.now(), grant.lastPolledAt)
    }

    @Test
    fun `approval requires an explicit accessible organization and requested scope subset`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val started = fixture.start(setOf(Capability.ORGANIZATION_READ, Capability.AUDIT_READ))

        assertEquals(
            IdentityErrorCode.NOT_FOUND,
            fixture.service.approve(
                started.userCode,
                fixture.approver,
                OrganizationId("unavailable-organization"),
                setOf(Capability.ORGANIZATION_READ)
            ).expectFailure()
        )
        assertEquals(
            IdentityErrorCode.NOT_FOUND,
            fixture.service.approve(
                started.userCode,
                fixture.approver,
                fixture.organization.id,
                setOf(Capability.CONTENT_READ)
            ).expectFailure()
        )

        fixture.service.approve(
            started.userCode,
            fixture.approver,
            fixture.organization.id,
            setOf(Capability.ORGANIZATION_READ)
        ).expectSuccess()

        val grant = fixture.store.snapshot().deviceGrants.single()
        assertEquals(DeviceGrantState.AUTHORIZED, grant.state)
        assertEquals(fixture.organization.id, grant.organizationId)
        assertEquals(fixture.membership.id, grant.membershipId)
        assertEquals(fixture.membership.version, grant.membershipVersion)
        assertEquals(fixture.user.id, grant.userId)
        assertEquals(fixture.user.id, grant.authorizedByUserId)
        assertEquals(setOf(Capability.ORGANIZATION_READ), grant.approvedCapabilities)
        assertNotNull(grant.authorizedAt)
    }

    @Test
    fun `membership demotion invalidates an authorized grant before exchange`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val started = fixture.authorize(setOf(Capability.ORGANIZATION_READ))

        fixture.changeMembership(role = OrganizationRole.VIEWER)

        fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID)
            .expectError(OAuthDeviceErrorCode.INVALID_GRANT)
        val snapshot = fixture.store.snapshot()
        assertEquals(DeviceGrantState.CANCELLED, snapshot.deviceGrants.single().state)
        assertTrue(snapshot.deviceTokenFamilies.isEmpty())
        assertTrue(snapshot.auditEvents.any {
            it.action == AuditAction.DEVICE_GRANT_CHANGED &&
                it.reasonCode == "membership_authorization_changed"
        })
    }

    @Test
    fun `membership demotion rejects refresh and revokes the token family`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val started = fixture.authorize(setOf(Capability.ORGANIZATION_READ))
        val issued = fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID).expectTokenSuccess()

        fixture.changeMembership(role = OrganizationRole.VIEWER)

        fixture.service.refresh(issued.refreshToken, DEVICE_CLIENT_ID)
            .expectError(OAuthDeviceErrorCode.INVALID_GRANT)
        val snapshot = fixture.store.snapshot()
        assertEquals(DeviceTokenFamilyState.REVOKED, snapshot.deviceTokenFamilies.single().state)
        assertEquals(
            "membership_authorization_changed",
            snapshot.deviceTokenFamilies.single().revocationReasonCode
        )
        assertTrue(snapshot.deviceAccessTokens.none { it.state == DeviceAccessTokenState.ACTIVE })
        assertTrue(snapshot.deviceRefreshTokens.none { it.state == DeviceRefreshTokenState.ACTIVE })
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            fixture.service.authenticateAccessToken(issued.accessToken).expectFailure()
        )
    }

    @Test
    fun `membership removal rejects access authentication and revokes the token family`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val started = fixture.authorize(setOf(Capability.ORGANIZATION_READ))
        val issued = fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID).expectTokenSuccess()

        fixture.changeMembership(state = MembershipState.REMOVED)

        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            fixture.service.authenticateAccessToken(issued.accessToken).expectFailure()
        )
        val snapshot = fixture.store.snapshot()
        assertEquals(DeviceTokenFamilyState.REVOKED, snapshot.deviceTokenFamilies.single().state)
        assertEquals(
            "membership_authorization_changed",
            snapshot.deviceTokenFamilies.single().revocationReasonCode
        )
        assertTrue(snapshot.auditEvents.any {
            it.action == AuditAction.DEVICE_TOKEN_REVOKED &&
                it.reasonCode == "membership_authorization_changed"
        })
    }

    @Test
    fun `exchange re-evaluates application capability mappings`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val applicationPublish = Capability("package.publish")
        var mappingEnabled = true
        val resolver = CapabilityResolver { context ->
            if (mappingEnabled && context.membership?.role == OrganizationRole.OWNER) {
                setOf(applicationPublish)
            } else {
                emptySet()
            }
        }
        val service = fixture.serviceWith(
            identityStore = fixture.store,
            allowedCapabilities = setOf(applicationPublish),
            capabilityResolver = resolver
        )
        val started = service.start(
            clientId = DEVICE_CLIENT_ID,
            requestedCapabilities = setOf(applicationPublish)
        ).expectSuccess()
        service.approve(
            started.userCode,
            fixture.approver,
            fixture.organization.id,
            setOf(applicationPublish)
        ).expectSuccess()

        mappingEnabled = false

        service.poll(started.deviceCode, DEVICE_CLIENT_ID)
            .expectError(OAuthDeviceErrorCode.INVALID_GRANT)
        assertEquals(DeviceGrantState.CANCELLED, fixture.store.snapshot().deviceGrants.single().state)
    }

    @Test
    fun `concurrent exchange has one winner and access is bound to its organization and scopes`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val started = fixture.authorize(setOf(Capability.ORGANIZATION_READ, Capability.AUDIT_READ))

        val results = List(2) { async { fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID) } }.awaitAll()

        val response = results.filterIsInstance<DeviceTokenEndpointResult.Success>().single().response
        assertEquals(1, results.filterIsInstance<DeviceTokenEndpointResult.Error>().count {
            it.code == OAuthDeviceErrorCode.INVALID_GRANT
        })
        val snapshot = fixture.store.snapshot()
        assertEquals(DeviceGrantState.CONSUMED, snapshot.deviceGrants.single().state)
        assertEquals(1, snapshot.deviceTokenFamilies.size)
        assertEquals(1, snapshot.deviceAccessTokens.size)
        assertEquals(1, snapshot.deviceRefreshTokens.size)

        val context = fixture.service.authenticateAccessToken(response.accessToken).expectSuccess()
        assertEquals(IdentityPrincipalKind.DEVICE, context.principal?.kind)
        assertEquals(fixture.user.id, context.principal?.userId)
        assertEquals(snapshot.deviceTokenFamilies.single().id, context.principal?.deviceTokenFamilyId)
        assertEquals(fixture.organization.id, context.organization?.id)
        assertEquals(setOf(Capability.ORGANIZATION_READ, Capability.AUDIT_READ), context.effectiveCapabilities)
        assertNull(context.session)
        assertNull(context.membership)
        assertFalse(context.hasRole(OrganizationRole.VIEWER))
    }

    @Test
    fun `only selectors and keyed digests persist for issued credentials`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val started = fixture.authorize(setOf(Capability.ORGANIZATION_READ))
        val issued = fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID).expectTokenSuccess()

        val snapshot = fixture.store.snapshot()
        val accessSecret = issued.accessToken.substringAfter('.')
        val refreshSecret = issued.refreshToken.substringAfter('.')
        val access = snapshot.deviceAccessTokens.single()
        val refresh = snapshot.deviceRefreshTokens.single()

        assertEquals(issued.accessToken.substringBefore('.'), access.publicSelector)
        assertEquals(issued.refreshToken.substringBefore('.'), refresh.publicSelector)
        assertEquals(DigestAlgorithm.HMAC_SHA256, access.secretDigest.algorithm)
        assertEquals(DigestAlgorithm.HMAC_SHA256, refresh.secretDigest.algorithm)
        assertEquals(fixture.config.keys.deviceTokenPepper.version, access.secretDigest.keyVersion)
        assertEquals(fixture.config.keys.deviceTokenPepper.version, refresh.secretDigest.keyVersion)
        assertNotEquals(accessSecret, access.secretDigest.encoded)
        assertNotEquals(refreshSecret, refresh.secretDigest.encoded)
        assertFalse(snapshot.toString().contains(issued.accessToken))
        assertFalse(snapshot.toString().contains(issued.refreshToken))
        assertFalse(snapshot.toString().contains(accessSecret))
        assertFalse(snapshot.toString().contains(refreshSecret))
        assertFalse(snapshot.toString().contains(started.deviceCode))
        assertFalse(snapshot.toString().contains(started.userCode))
    }

    @Test
    fun `device and refresh credentials remain bound to the initiating client id`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val pending = fixture.start(setOf(Capability.ORGANIZATION_READ))

        fixture.service.poll(pending.deviceCode, "other-client")
            .expectError(OAuthDeviceErrorCode.INVALID_GRANT)
        assertEquals(0, fixture.store.snapshot().deviceGrants.single().pollCount)

        fixture.service.approve(
            pending.userCode,
            fixture.approver,
            fixture.organization.id,
            setOf(Capability.ORGANIZATION_READ)
        ).expectSuccess()
        fixture.service.poll(pending.deviceCode, "other-client")
            .expectError(OAuthDeviceErrorCode.INVALID_GRANT)
        val issued = fixture.service.poll(pending.deviceCode, DEVICE_CLIENT_ID).expectTokenSuccess()
        assertEquals(DEVICE_CLIENT_ID, fixture.store.snapshot().deviceTokenFamilies.single().clientId)

        fixture.service.refresh(issued.refreshToken, "other-client")
            .expectError(OAuthDeviceErrorCode.INVALID_GRANT)
        assertEquals(
            DeviceRefreshTokenState.ACTIVE,
            fixture.store.snapshot().deviceRefreshTokens.single().state
        )
        fixture.service.revokeRefreshToken(issued.refreshToken, "other-client").expectSuccess()
        assertEquals(DeviceTokenFamilyState.ACTIVE, fixture.store.snapshot().deviceTokenFamilies.single().state)
        fixture.service.revokeRefreshToken(issued.refreshToken, DEVICE_CLIENT_ID).expectSuccess()
        assertEquals(DeviceTokenFamilyState.REVOKED, fixture.store.snapshot().deviceTokenFamilies.single().state)
    }

    @Test
    fun `refresh rotation rejects replay and revokes the entire token family`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val started = fixture.authorize(setOf(Capability.ORGANIZATION_READ))
        val initial = fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID).expectTokenSuccess()

        fixture.runtime.deterministicClock.advanceMilliseconds(1_000)
        val rotated = fixture.service.refresh(initial.refreshToken, DEVICE_CLIENT_ID).expectTokenSuccess()
        assertNotEquals(initial.accessToken, rotated.accessToken)
        assertNotEquals(initial.refreshToken, rotated.refreshToken)
        var snapshot = fixture.store.snapshot()
        assertEquals(DeviceTokenFamilyState.ACTIVE, snapshot.deviceTokenFamilies.single().state)
        assertEquals(DeviceRefreshTokenState.ROTATED, snapshot.deviceRefreshTokens.single {
            it.publicSelector == initial.refreshToken.substringBefore('.')
        }.state)

        fixture.service.refresh(initial.refreshToken, DEVICE_CLIENT_ID)
            .expectError(OAuthDeviceErrorCode.INVALID_GRANT)

        snapshot = fixture.store.snapshot()
        assertEquals(DeviceTokenFamilyState.REVOKED, snapshot.deviceTokenFamilies.single().state)
        assertEquals("refresh_token_replay", snapshot.deviceTokenFamilies.single().revocationReasonCode)
        assertTrue(snapshot.deviceAccessTokens.none { it.state == DeviceAccessTokenState.ACTIVE })
        assertTrue(snapshot.deviceRefreshTokens.none { it.state == DeviceRefreshTokenState.ACTIVE })
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            fixture.service.authenticateAccessToken(rotated.accessToken).expectFailure()
        )
        assertTrue(snapshot.auditEvents.any {
            it.action == AuditAction.DEVICE_TOKEN_REPLAY_DETECTED && it.outcome == AuditOutcome.DENIED
        })
    }

    @Test
    fun `concurrent refresh conflict rereads the rotated token and revokes its family`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val started = fixture.authorize(setOf(Capability.ORGANIZATION_READ))
        val initial = fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID).expectTokenSuccess()
        fixture.runtime.deterministicClock.advanceMilliseconds(1_000)
        val service = fixture.serviceWith(CoordinatedRefreshStore(fixture.store))

        val results = List(2) {
            async { service.refresh(initial.refreshToken, DEVICE_CLIENT_ID) }
        }.awaitAll()

        val winner = results.filterIsInstance<DeviceTokenEndpointResult.Success>().single()
        results.filterIsInstance<DeviceTokenEndpointResult.Error>().single()
            .also { assertEquals(OAuthDeviceErrorCode.INVALID_GRANT, it.code) }
        assertTrue(results.none { it is DeviceTokenEndpointResult.Unavailable })
        val snapshot = fixture.store.snapshot()
        assertEquals(DeviceTokenFamilyState.REVOKED, snapshot.deviceTokenFamilies.single().state)
        assertEquals("refresh_token_replay", snapshot.deviceTokenFamilies.single().revocationReasonCode)
        assertTrue(snapshot.deviceAccessTokens.none { it.state == DeviceAccessTokenState.ACTIVE })
        assertTrue(snapshot.deviceRefreshTokens.none { it.state == DeviceRefreshTokenState.ACTIVE })
        assertEquals(
            IdentityErrorCode.INVALID_CREDENTIALS,
            service.authenticateAccessToken(winner.response.accessToken).expectFailure()
        )
        assertEquals(
            1,
            snapshot.auditEvents.count {
                it.action == AuditAction.DEVICE_TOKEN_REPLAY_DETECTED && it.outcome == AuditOutcome.DENIED
            }
        )
    }

    @Test
    fun `concurrent refresh reports unavailable when the conflict reread fails`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val started = fixture.authorize(setOf(Capability.ORGANIZATION_READ))
        val initial = fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID).expectTokenSuccess()
        fixture.runtime.deterministicClock.advanceMilliseconds(1_000)
        val service = fixture.serviceWith(
            CoordinatedRefreshStore(fixture.store, failConflictReread = true)
        )

        val results = List(2) {
            async { service.refresh(initial.refreshToken, DEVICE_CLIENT_ID) }
        }.awaitAll()

        results.filterIsInstance<DeviceTokenEndpointResult.Success>().single()
        assertEquals(
            IdentityErrorCode.SERVICE_UNAVAILABLE,
            results.filterIsInstance<DeviceTokenEndpointResult.Unavailable>().single().code
        )
        assertTrue(results.none { it is DeviceTokenEndpointResult.Error })
        val snapshot = fixture.store.snapshot()
        assertEquals(DeviceTokenFamilyState.ACTIVE, snapshot.deviceTokenFamilies.single().state)
        assertTrue(snapshot.deviceAccessTokens.any { it.state == DeviceAccessTokenState.ACTIVE })
        assertTrue(snapshot.deviceRefreshTokens.any { it.state == DeviceRefreshTokenState.ACTIVE })
        assertTrue(snapshot.auditEvents.none { it.action == AuditAction.DEVICE_TOKEN_REPLAY_DETECTED })
    }

    @Test
    fun `refresh replay reports store failure unless family revocation is confirmed`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val started = fixture.authorize(setOf(Capability.ORGANIZATION_READ))
        val initial = fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID).expectTokenSuccess()
        fixture.runtime.deterministicClock.advanceMilliseconds(1_000)
        fixture.service.refresh(initial.refreshToken, DEVICE_CLIENT_ID).expectTokenSuccess()
        val unavailableStore = object : IdentityStore by fixture.store {
            override suspend fun revokeDeviceTokenFamily(
                command: RevokeDeviceTokenFamilyCommand
            ): StoreResult<DeviceTokenFamilyRevocationCommit> = StoreResult.Failure(
                IdentityStoreError(IdentityStoreErrorCode.UNAVAILABLE, retryable = true)
            )
        }
        val service = fixture.serviceWith(unavailableStore)

        val replay = service.refresh(initial.refreshToken, DEVICE_CLIENT_ID)

        assertEquals(
            IdentityErrorCode.SERVICE_UNAVAILABLE,
            (replay as DeviceTokenEndpointResult.Unavailable).code
        )
        val snapshot = fixture.store.snapshot()
        assertEquals(DeviceTokenFamilyState.ACTIVE, snapshot.deviceTokenFamilies.single().state)
        assertTrue(snapshot.deviceAccessTokens.any { it.state == DeviceAccessTokenState.ACTIVE })
        assertTrue(snapshot.deviceRefreshTokens.any { it.state == DeviceRefreshTokenState.ACTIVE })
        assertTrue(snapshot.auditEvents.none { it.action == AuditAction.DEVICE_TOKEN_REPLAY_DETECTED })
    }

    @Test
    fun `denied and cancelled grants cannot be exchanged`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val denied = fixture.start(setOf(Capability.ORGANIZATION_READ))
        fixture.service.deny(denied.userCode, fixture.approver).expectSuccess()
        fixture.service.poll(denied.deviceCode, DEVICE_CLIENT_ID).expectError(OAuthDeviceErrorCode.ACCESS_DENIED)

        val cancelled = fixture.start(setOf(Capability.ORGANIZATION_READ))
        fixture.service.cancel(cancelled.deviceCode).expectSuccess()
        fixture.service.poll(cancelled.deviceCode, DEVICE_CLIENT_ID).expectError(OAuthDeviceErrorCode.INVALID_GRANT)

        val cancelledAfterApproval = fixture.authorize(setOf(Capability.ORGANIZATION_READ))
        fixture.service.cancel(cancelledAfterApproval.deviceCode).expectSuccess()
        fixture.service.poll(cancelledAfterApproval.deviceCode, DEVICE_CLIENT_ID).expectError(OAuthDeviceErrorCode.INVALID_GRANT)

        val states = fixture.store.snapshot().deviceGrants.associate { it.id to it.state }.values.toSet()
        assertEquals(setOf(DeviceGrantState.DENIED, DeviceGrantState.CANCELLED), states)
    }

    @Test
    fun `polling at expiration atomically expires an unconsumed grant`() = runTest {
        val fixture = DeviceAuthorizationFixture()
        val started = fixture.start(setOf(Capability.ORGANIZATION_READ))
        fixture.runtime.deterministicClock.advanceMilliseconds(600_000)

        fixture.service.poll(started.deviceCode, DEVICE_CLIENT_ID).expectError(OAuthDeviceErrorCode.EXPIRED_TOKEN)

        val grant = fixture.store.snapshot().deviceGrants.single()
        assertEquals(DeviceGrantState.EXPIRED, grant.state)
        assertEquals(fixture.runtime.deterministicClock.now(), grant.expiredAt)
        assertTrue(fixture.store.snapshot().auditEvents.any {
            it.action == AuditAction.DEVICE_GRANT_CHANGED && it.reasonCode == "device_authorization_expired"
        })
    }
}

private class CoordinatedRefreshStore(
    private val delegate: IdentityStore,
    private val failConflictReread: Boolean = false
) : IdentityStore by delegate {
    private val lock = Mutex()
    private val rotationsReady = CompletableDeferred<Unit>()
    private var refreshLookups = 0
    private var rotationCalls = 0

    override suspend fun findDeviceRefreshTokenBySelector(
        publicSelector: String
    ): StoreResult<DeviceRefreshToken?> {
        val lookup = lock.withLock {
            refreshLookups += 1
            refreshLookups
        }
        if (failConflictReread && lookup > 2) {
            return StoreResult.Failure(
                IdentityStoreError(IdentityStoreErrorCode.UNAVAILABLE, retryable = true)
            )
        }
        return delegate.findDeviceRefreshTokenBySelector(publicSelector)
    }

    override suspend fun rotateDeviceRefreshToken(
        command: RotateDeviceRefreshTokenCommand
    ): StoreResult<DeviceTokenRotationCommit> {
        lock.withLock {
            rotationCalls += 1
            if (rotationCalls == 2) rotationsReady.complete(Unit)
        }
        rotationsReady.await()
        return delegate.rotateDeviceRefreshToken(command)
    }
}

private class DeviceAuthorizationFixture {
    val user: User = IdentityFixtures.user()
    val organization: Organization = IdentityFixtures.organization()
    val membership: Membership = IdentityFixtures.membership(
        organizationId = organization.id,
        userId = user.id,
        role = OrganizationRole.OWNER
    )
    private val otherOwnerUser: User = IdentityFixtures.user(IdentityFixtures.userId("device-other-owner"))
    private val otherOwnerMembership: Membership = IdentityFixtures.membership(
        id = IdentityFixtures.membershipId("device-other-owner"),
        organizationId = organization.id,
        userId = otherOwnerUser.id,
        role = OrganizationRole.OWNER
    )
    private val session: IdentitySession = IdentityFixtures.session(userId = user.id)
    val config: IdentityConfig = deviceAuthorizationConfig()
    val runtime = DeterministicIdentityRuntime(
        deterministicSecrets = DeterministicIdentitySecretResolver(
            mapOf(config.keys.deviceTokenPepper to ByteArray(32) { 0x53 })
        )
    )
    val store = InMemoryIdentityStore(
        InMemoryIdentityStoreSeed(
            users = listOf(user, otherOwnerUser),
            sessions = listOf(session),
            organizations = listOf(organization),
            memberships = listOf(membership, otherOwnerMembership)
        )
    )
    val service = serviceWith(store)

    fun serviceWith(
        identityStore: IdentityStore,
        allowedCapabilities: Set<Capability> = setOf(Capability.ORGANIZATION_READ, Capability.AUDIT_READ),
        capabilityResolver: CapabilityResolver = CapabilityResolver.NONE
    ) = IdentityDeviceAuthorizationService(
        store = identityStore,
        runtime = runtime.runtime,
        config = config,
        allowedCapabilities = allowedCapabilities,
        capabilityResolver = capabilityResolver
    )
    val approver = IdentityContext(
        principal = IdentityPrincipal(
            kind = IdentityPrincipalKind.USER,
            userId = user.id,
            displayName = user.displayName,
            assurance = AuthenticationAssurance.PASSKEY,
            authenticatedAt = session.authenticatedAt,
            sessionId = session.id
        ),
        session = session,
        organization = organization,
        membership = membership
    )

    suspend fun start(capabilities: Set<Capability>): DeviceAuthorizationResponse =
        service.start(
            clientId = DEVICE_CLIENT_ID,
            requestedCapabilities = capabilities,
            clientName = "Aether deterministic CLI"
        ).expectSuccess()

    suspend fun authorize(capabilities: Set<Capability>): DeviceAuthorizationResponse {
        val started = start(capabilities)
        service.approve(started.userCode, approver, organization.id, capabilities).expectSuccess()
        return started
    }

    suspend fun changeMembership(
        role: OrganizationRole = membership.role,
        state: MembershipState = MembershipState.ACTIVE
    ): Membership {
        val current = store.findMembership(membership.id).expectStoreSuccess()
            ?: error("Fixture membership disappeared")
        val now = runtime.deterministicClock.now()
        val replacement = current.copy(
            role = role,
            state = state,
            version = current.version + 1,
            updatedAt = now,
            removedAt = if (state == MembershipState.REMOVED) now else null
        )
        return store.mutateMembership(
            MutateMembershipCommand(
                membershipId = current.id,
                expectedVersion = current.version,
                replacement = replacement,
                auditEvent = IdentityFixtures.auditEvent(
                    IdentityFixtures.auditEventId("device-membership-change-${replacement.version}"),
                    AuditAction.MEMBERSHIP_CHANGED,
                    current.id.value
                ).copy(
                    organizationId = organization.id,
                    target = AuditTarget(AuditTargetType.MEMBERSHIP, current.id.value),
                    occurredAt = now
                )
            )
        ).expectStoreSuccess()
    }
}

private fun <T> StoreResult<T>.expectStoreSuccess(): T = when (this) {
    is StoreResult.Success -> value
    is StoreResult.Failure -> error("Expected store success, got ${error.code}")
}

private fun deviceAuthorizationConfig(): IdentityConfig {
    fun secret(name: String) = SecretReference("test", name, "v1", IdentityEnvironment.TEST)
    return IdentityConfig(
        environment = IdentityEnvironment.TEST,
        publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig("localhost", "Aether Device Test", setOf("http://localhost:8080")),
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

private fun DeviceTokenEndpointResult.expectTokenSuccess(): OAuthDeviceTokenResponse = when (this) {
    is DeviceTokenEndpointResult.Success -> response
    is DeviceTokenEndpointResult.Error -> error("Expected token success, got $code")
    is DeviceTokenEndpointResult.Unavailable -> error("Expected token success, store unavailable: $code")
}

private fun DeviceTokenEndpointResult.expectError(
    expected: OAuthDeviceErrorCode,
    interval: Int? = null
) {
    val error = when (this) {
        is DeviceTokenEndpointResult.Error -> this
        is DeviceTokenEndpointResult.Success -> error("Expected $expected, got token success")
        is DeviceTokenEndpointResult.Unavailable -> error("Expected $expected, store unavailable: $code")
    }
    assertEquals(expected, error.code)
    assertEquals(interval, error.pollingIntervalSeconds)
}

private fun <T> IdentityOperationResult<T>.expectSuccess(): T = when (this) {
    is IdentityOperationResult.Success -> value
    is IdentityOperationResult.Failure -> error("Expected identity success, got $code")
}

private fun IdentityOperationResult<*>.expectFailure(): IdentityErrorCode = when (this) {
    is IdentityOperationResult.Success -> error("Expected identity failure")
    is IdentityOperationResult.Failure -> code
}

private val USER_CODE_PATTERN = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}")
private const val DEVICE_CLIENT_ID = "aether-test-client"
