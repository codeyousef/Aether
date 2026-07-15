package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import codes.yousef.aether.core.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class StoreBackedIdentityContextResolverTest {
    @Test
    fun `valid cookie atomically slides idle expiry and returns the persisted session`() = runTest {
        val fixture = resolverFixture()
        fixture.runtime.deterministicClock.advanceMilliseconds(5 * 60_000L)

        val result = fixture.resolver().resolve(fixture.exchange())

        val authenticated = assertIs<IdentityResolutionResult.Authenticated>(result)
        val persisted = fixture.store.findSession(fixture.issued.session.id).expectSuccess()!!
        assertEquals(persisted, authenticated.context.session)
        assertEquals(1, persisted.version)
        assertEquals(fixture.runtime.deterministicClock.now(), persisted.lastUsedAt)
        assertEquals(persisted.lastUsedAt + 24.hours, persisted.idleExpiresAt)
        assertTrue(fixture.store.snapshot().auditEvents.isEmpty())
    }

    @Test
    fun `version race accepts only a concurrently persisted valid touch`() = runTest {
        val fixture = resolverFixture()
        fixture.runtime.deterministicClock.advanceMilliseconds(60_000)
        var injected = false
        val racingStore = object : IdentityStore by fixture.store {
            override suspend fun touchIdentitySession(
                command: TouchIdentitySessionCommand
            ): StoreResult<IdentitySession> {
                if (!injected) {
                    injected = true
                    fixture.store.touchIdentitySession(command).expectSuccess()
                    return StoreResult.Failure(
                        IdentityStoreError(IdentityStoreErrorCode.VERSION_CONFLICT, retryable = true)
                    )
                }
                return fixture.store.touchIdentitySession(command)
            }
        }

        val result = fixture.resolver(racingStore).resolve(fixture.exchange())

        val authenticated = assertIs<IdentityResolutionResult.Authenticated>(result)
        assertEquals(1, authenticated.context.session?.version)
        assertEquals(
            fixture.store.findSession(fixture.issued.session.id).expectSuccess(),
            authenticated.context.session
        )
    }

    @Test
    fun `idle-expired and revoked sessions fail before they can be touched`() = runTest {
        val expiredFixture = resolverFixture()
        expiredFixture.runtime.deterministicClock.advanceMilliseconds(24 * 60 * 60_000L)
        assertEquals(
            IdentityErrorCode.SESSION_EXPIRED,
            assertIs<IdentityResolutionResult.Rejected>(
                expiredFixture.resolver().resolve(expiredFixture.exchange())
            ).code
        )
        assertEquals(0, expiredFixture.store.findSession(expiredFixture.issued.session.id).expectSuccess()?.version)

        val activeFixture = resolverFixture()
        val revokedSession = activeFixture.issued.session.copy(
            state = SessionState.REVOKED,
            revokedAt = activeFixture.issued.session.createdAt,
            revocationReasonCode = "test_revocation"
        )
        val revokedStore = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(activeFixture.user),
                sessions = listOf(revokedSession)
            )
        )
        assertEquals(
            IdentityErrorCode.SESSION_REVOKED,
            assertIs<IdentityResolutionResult.Rejected>(
                activeFixture.resolver(revokedStore).resolve(activeFixture.exchange())
            ).code
        )
        assertEquals(0, revokedStore.findSession(revokedSession.id).expectSuccess()?.version)
    }

    @Test
    fun `touch storage failure fails closed without returning stale authenticated context`() = runTest {
        val fixture = resolverFixture()
        val unavailableStore = object : IdentityStore by fixture.store {
            override suspend fun touchIdentitySession(
                command: TouchIdentitySessionCommand
            ): StoreResult<IdentitySession> = StoreResult.Failure(
                IdentityStoreError(IdentityStoreErrorCode.UNAVAILABLE, retryable = true)
            )
        }

        val result = fixture.resolver(unavailableStore).resolve(fixture.exchange())

        assertEquals(
            IdentityErrorCode.SERVICE_UNAVAILABLE,
            assertIs<IdentityResolutionResult.Rejected>(result).code
        )
        assertEquals(0, fixture.store.findSession(fixture.issued.session.id).expectSuccess()?.version)
    }

    @Test
    fun `federated session resolves only its bound organization`() = runTest {
        val fixture = federatedResolverFixture()

        val boundResult = fixture.resolver(organizationId = fixture.organization.id)
            .resolve(fixture.exchange())
        val boundContext = assertIs<IdentityResolutionResult.Authenticated>(boundResult).context
        assertEquals(fixture.organization.id, boundContext.organization?.id)
        assertEquals(fixture.membership.id, boundContext.membership?.id)

        val otherResult = fixture.resolver(organizationId = fixture.otherOrganization.id)
            .resolve(fixture.exchange())
        val otherContext = assertIs<IdentityResolutionResult.Authenticated>(otherResult).context
        assertEquals(null, otherContext.organization)
        assertEquals(null, otherContext.membership)
        assertEquals(fixture.organization.id, otherContext.session?.federationOrganizationId)

        val guarded = ResolverSessionExchange(Cookies.Empty).also {
            it.attributes.put(IdentityContextAttributeKey, otherContext)
        }
        var continued = false
        requireOrganization(fixture.otherOrganization.id)(guarded) { continued = true }
        assertFalse(continued)
        assertEquals(IdentityErrorCode.NOT_FOUND.httpStatus, guarded.response.statusCode)
    }

    @Test
    fun `federated session with inactive tenant membership is rejected before touch`() = runTest {
        val fixture = federatedResolverFixture(membershipState = MembershipState.SUSPENDED)

        val result = fixture.resolver().resolve(fixture.exchange())

        assertEquals(
            IdentityErrorCode.SESSION_REVOKED,
            assertIs<IdentityResolutionResult.Rejected>(result).code
        )
        assertEquals(0, fixture.store.findSession(fixture.issued.session.id).expectSuccess()?.version)
    }

    @Test
    fun `disabled and re-enabled provider keep an old federated session revoked without touch`() = runTest {
        val fixture = federatedResolverFixture()
        val manager = IdentityFederationProviderManager(fixture.store, fixture.runtime.runtime)

        assertIs<IdentityOperationResult.Success<FederationProviderControl>>(
            manager.disableProvider(
                fixture.organization.id,
                fixture.providerControl.kind,
                fixture.providerControl.providerId,
                fixture.providerControl.storageKey
            )
        )
        assertEquals(
            IdentityErrorCode.SESSION_REVOKED,
            assertIs<IdentityResolutionResult.Rejected>(
                fixture.resolver().resolve(fixture.exchange())
            ).code
        )
        assertEquals(0, fixture.store.findSession(fixture.issued.session.id).expectSuccess()?.version)

        assertIs<IdentityOperationResult.Success<FederationProviderControl>>(
            manager.enableProvider(
                fixture.organization.id,
                fixture.providerControl.kind,
                fixture.providerControl.providerId,
                fixture.providerControl.storageKey
            )
        )
        assertEquals(
            IdentityErrorCode.SESSION_REVOKED,
            assertIs<IdentityResolutionResult.Rejected>(
                fixture.resolver().resolve(fixture.exchange())
            ).code
        )
        assertEquals(0, fixture.store.findSession(fixture.issued.session.id).expectSuccess()?.version)
    }

    @Test
    fun `provider epoch mismatch is rejected before touch`() = runTest {
        val fixture = federatedResolverFixture()
        val snapshot = fixture.store.snapshot()
        val epochStore = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = snapshot.users,
                sessions = snapshot.sessions,
                organizations = snapshot.organizations,
                memberships = snapshot.memberships,
                federationProviderControls = listOf(
                    fixture.providerControl.copy(sessionEpoch = 1, version = 2)
                )
            )
        )

        val result = fixture.resolver(epochStore).resolve(fixture.exchange())

        assertEquals(
            IdentityErrorCode.SESSION_REVOKED,
            assertIs<IdentityResolutionResult.Rejected>(result).code
        )
        assertEquals(0, epochStore.findSession(fixture.issued.session.id).expectSuccess()?.version)
    }

    @Test
    fun `provider control backend failure is service unavailable and never touches session`() = runTest {
        for (code in listOf(IdentityStoreErrorCode.UNAVAILABLE, IdentityStoreErrorCode.INTERNAL)) {
            val fixture = federatedResolverFixture()
            val unavailable = object : IdentityStore by fixture.store {
                override suspend fun findFederationProviderControlByStorageKey(
                    storageKey: String
                ): StoreResult<FederationProviderControl?> = StoreResult.Failure(
                    IdentityStoreError(code, retryable = code == IdentityStoreErrorCode.UNAVAILABLE)
                )
            }

            val result = fixture.resolver(unavailable).resolve(fixture.exchange())

            assertEquals(
                IdentityErrorCode.SERVICE_UNAVAILABLE,
                assertIs<IdentityResolutionResult.Rejected>(result).code
            )
            assertEquals(0, fixture.store.findSession(fixture.issued.session.id).expectSuccess()?.version)
        }
    }

    @Test
    fun `provider disabled during atomic touch maps to session revoked`() = runTest {
        val fixture = federatedResolverFixture()
        val disabledDuringTouch = object : IdentityStore by fixture.store {
            override suspend fun touchIdentitySession(
                command: TouchIdentitySessionCommand
            ): StoreResult<IdentitySession> = StoreResult.Failure(
                IdentityStoreError(IdentityStoreErrorCode.FEDERATION_PROVIDER_DISABLED)
            )
        }

        val result = fixture.resolver(disabledDuringTouch).resolve(fixture.exchange())

        assertEquals(
            IdentityErrorCode.SESSION_REVOKED,
            assertIs<IdentityResolutionResult.Rejected>(result).code
        )
        assertEquals(0, fixture.store.findSession(fixture.issued.session.id).expectSuccess()?.version)
    }
}

private data class ResolverFixture(
    val config: IdentityConfig,
    val runtime: DeterministicIdentityRuntime,
    val user: User,
    val issued: IssuedIdentitySession,
    val store: InMemoryIdentityStore
) {
    fun resolver(
        identityStore: IdentityStore = store,
        organizationId: OrganizationId? = null
    ): StoreBackedIdentityContextResolver = StoreBackedIdentityContextResolver(
        identityStore,
        runtime.runtime,
        config,
        organizationSelector = { organizationId }
    )

    fun exchange(): Exchange = ResolverSessionExchange(
        Cookies.of(Cookie(config.cookie.name, issued.cookieValue()))
    )
}

private data class FederatedResolverFixture(
    val config: IdentityConfig,
    val runtime: DeterministicIdentityRuntime,
    val user: User,
    val organization: Organization,
    val otherOrganization: Organization,
    val membership: Membership,
    val providerControl: FederationProviderControl,
    val issued: IssuedIdentitySession,
    val store: InMemoryIdentityStore
) {
    fun resolver(
        identityStore: IdentityStore = store,
        organizationId: OrganizationId? = null
    ): StoreBackedIdentityContextResolver =
        StoreBackedIdentityContextResolver(
            identityStore,
            runtime.runtime,
            config,
            organizationSelector = { organizationId }
        )

    fun exchange(): Exchange = ResolverSessionExchange(
        Cookies.of(Cookie(config.cookie.name, issued.cookieValue()))
    )
}

private suspend fun resolverFixture(): ResolverFixture {
    val config = resolverIdentityConfig()
    val runtime = DeterministicIdentityRuntime()
    runtime.deterministicSecrets.register(config.keys.sessionPepper, ByteArray(32) { (it + 1).toByte() })
    val user = IdentityFixtures.user()
    val issued = IdentitySessionIssuer(runtime.runtime, config).issue(
        user = user,
        assurance = AuthenticationAssurance.PASSKEY,
        authenticationMethod = SessionAuthenticationMethod.PASSKEY,
        authenticatedAt = runtime.deterministicClock.now()
    )
    val store = InMemoryIdentityStore(
        InMemoryIdentityStoreSeed(users = listOf(user), sessions = listOf(issued.session))
    )
    return ResolverFixture(config, runtime, user, issued, store)
}

private suspend fun federatedResolverFixture(
    membershipState: MembershipState = MembershipState.ACTIVE
): FederatedResolverFixture {
    val config = resolverIdentityConfig()
    val runtime = DeterministicIdentityRuntime()
    runtime.deterministicSecrets.register(config.keys.sessionPepper, ByteArray(32) { (it + 1).toByte() })
    val user = IdentityFixtures.user(IdentityFixtures.userId("resolver-federated-user"))
    val organization = IdentityFixtures.organization(
        id = IdentityFixtures.organizationId("resolver-federated-organization"),
        slug = "resolver-federated"
    )
    val otherOrganization = IdentityFixtures.organization(
        id = IdentityFixtures.organizationId("resolver-other-organization"),
        slug = "resolver-other"
    )
    val membership = IdentityFixtures.membership(
        id = IdentityFixtures.membershipId("resolver-federated-membership"),
        organizationId = organization.id,
        userId = user.id,
        role = OrganizationRole.VIEWER,
        state = membershipState
    )
    val otherMembership = IdentityFixtures.membership(
        id = IdentityFixtures.membershipId("resolver-other-membership"),
        organizationId = otherOrganization.id,
        userId = user.id,
        role = OrganizationRole.OWNER
    )
    val providerControl = IdentityFixtures.federationProviderControl(
        organizationId = organization.id,
        providerId = "workforce"
    )
    val issued = IdentitySessionIssuer(runtime.runtime, config).issueFederated(
        user = user,
        authenticationMethod = SessionAuthenticationMethod.OIDC,
        providerLease = IdentityFixtures.federationProviderLease(providerControl),
        externalIdentityId = IdentityFixtures.externalIdentityId("resolver-federated-external"),
        authenticatedAt = runtime.deterministicClock.now()
    )
    val store = InMemoryIdentityStore(
        InMemoryIdentityStoreSeed(
            users = listOf(user),
            sessions = listOf(issued.session),
            organizations = listOf(organization, otherOrganization),
            memberships = listOf(membership, otherMembership),
            federationProviderControls = listOf(providerControl)
        )
    )
    return FederatedResolverFixture(
        config,
        runtime,
        user,
        organization,
        otherOrganization,
        membership,
        providerControl,
        issued,
        store
    )
}

private fun resolverIdentityConfig(): IdentityConfig {
    fun secret(name: String) = SecretReference("test", name, "v1", IdentityEnvironment.TEST)
    return IdentityConfig(
        environment = IdentityEnvironment.TEST,
        publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig(
            id = "localhost",
            name = "Resolver Test",
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

private class ResolverSessionExchange(cookies: Cookies) : Exchange {
    override val request: Request = object : Request {
        override val method: HttpMethod = HttpMethod.GET
        override val uri: String = "/identity/v1/me"
        override val path: String = uri
        override val query: String? = null
        override val headers: Headers = Headers.Empty
        override val cookies: Cookies = cookies
        override suspend fun bodyBytes(): ByteArray = ByteArray(0)
    }
    override val response: Response = object : Response {
        override var statusCode: Int = 200
        override var statusMessage: String? = null
        override val headers: Headers.HeadersBuilder = Headers.HeadersBuilder()
        override val cookies: MutableList<Cookie> = mutableListOf()
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun end() = Unit
    }
    override val attributes: Attributes = Attributes()
}
