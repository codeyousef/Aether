package codes.yousef.aether.auth.saml

import codes.yousef.aether.auth.AuditAction
import codes.yousef.aether.auth.AuthenticationAssurance
import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.ChallengeId
import codes.yousef.aether.auth.ExternalIdentityId
import codes.yousef.aether.auth.ExternalSubject
import codes.yousef.aether.auth.FederationCallbackStateConsumeResult
import codes.yousef.aether.auth.FederationCallbackStateStore
import codes.yousef.aether.auth.FederationCallbackStateWriteResult
import codes.yousef.aether.auth.FederationProviderControl
import codes.yousef.aether.auth.FederationProviderKind
import codes.yousef.aether.auth.FederationProviderLease
import codes.yousef.aether.auth.FederatedIdentitySessionService
import codes.yousef.aether.auth.IdentityConfig
import codes.yousef.aether.auth.IdentityContext
import codes.yousef.aether.auth.IdentityContextAttributeKey
import codes.yousef.aether.auth.IdentityEnvironment
import codes.yousef.aether.auth.IdentityFederationProviderManager
import codes.yousef.aether.auth.IdentityKeyConfig
import codes.yousef.aether.auth.IdentityPrincipal
import codes.yousef.aether.auth.IdentityPrincipalKind
import codes.yousef.aether.auth.IdentitySession
import codes.yousef.aether.auth.MembershipState
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.RelyingPartyConfig
import codes.yousef.aether.auth.SecretReference
import codes.yousef.aether.auth.SessionAuthenticationMethod
import codes.yousef.aether.auth.SessionState
import codes.yousef.aether.auth.UserId
import codes.yousef.aether.auth.testkit.DeterministicIdentityRuntime
import codes.yousef.aether.auth.testkit.DeterministicIdentitySecretResolver
import codes.yousef.aether.auth.testkit.IdentityFixtures
import codes.yousef.aether.auth.testkit.InMemoryIdentityStore
import codes.yousef.aether.auth.testkit.InMemoryIdentityStoreSeed
import codes.yousef.aether.core.Attributes
import codes.yousef.aether.core.Cookie
import codes.yousef.aether.core.Cookies
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.Headers
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.Request
import codes.yousef.aether.core.RequestConnection
import codes.yousef.aether.core.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class SamlFederationHttpMiddlewareTest {
    @Test
    fun `protected callback state round-trips the complete provider lease`() = runTest {
        val now = IdentityFixtures.baseInstant
        val state = SamlServerCallbackState(
            providerLease = PROVIDER_LEASE,
            authenticationState = SamlAuthenticationState(
                challengeId = ChallengeId("challenge-protection"),
                requestId = "_challenge-protection",
                relayState = RELAY_BYTES,
                linkToUserId = USER_ID,
                providerLease = PROVIDER_LEASE,
                expiresAt = now + 5.minutes
            ),
            predecessorSessionId = null,
            expectedPredecessorVersion = null,
            expiresAt = now + 5.minutes
        )

        val restored = state.useForProtection {
                lease, challengeId, requestId, relayState, linkToUserId,
                predecessorSessionId, expectedPredecessorVersion, expiresAt ->
            SamlServerCallbackState.restore(
                providerLease = lease,
                challengeId = challengeId,
                requestId = requestId,
                relayState = relayState,
                linkToUserId = linkToUserId,
                expiresAt = expiresAt,
                predecessorSessionId = predecessorSessionId,
                expectedPredecessorVersion = expectedPredecessorVersion
            )
        }

        assertEquals(PROVIDER_LEASE, restored.providerLease)
        assertEquals(PROVIDER_LEASE, restored.authenticationState.providerLease)
        assertFailsWith<IllegalArgumentException> {
            SamlServerCallbackState(
                providerLease = PROVIDER_LEASE.copy(version = 1),
                authenticationState = restored.authenticationState,
                predecessorSessionId = null,
                expectedPredecessorVersion = null,
                expiresAt = restored.expiresAt
            )
        }
        state.destroy()
        restored.destroy()
    }

    @Test
    fun `redirect start and POST callback keep SAML state server-side and create provenance session`() = runTest {
        val fixture = Fixture()
        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)

        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }

        assertEquals(302, start.response.statusCode)
        assertTrue(start.response.headers.build()["Location"]!!.startsWith("$SSO_ENDPOINT?"))
        val stateCookie = start.response.cookies.single { it.name == STATE_COOKIE }
        assertTrue(stateCookie.secure)
        assertTrue(stateCookie.httpOnly)
        assertEquals(Cookie.SameSite.NONE, stateCookie.sameSite)
        assertEquals(43, stateCookie.value.length)
        assertFalse(stateCookie.value.contains(USER_ID.value))
        assertFalse(stateCookie.value.contains(TENANT_ID.value))
        assertFalse(stateCookie.value.contains(PROVIDER_ID))
        assertFalse(start.response.headers.build()["Location"]!!.contains(stateCookie.value))
        assertFalse(start.response.bodyText().contains("RelayState", ignoreCase = true))
        assertEquals(1, fixture.states.size)

        val callback = fixture.callback(stateCookie.value)
        fixture.middleware.asMiddleware()(callback) { error("Federation route must not fall through") }

        assertEquals(303, callback.response.statusCode)
        assertEquals(SUCCESS_REDIRECT, callback.response.headers.build()["Location"])
        assertFalse(callback.response.headers.build()["Location"]!!.contains("csrf", ignoreCase = true))
        val sessionCookie = callback.response.cookies.single { it.name == fixture.config.cookie.name }
        val csrfCookie = callback.response.cookies.single { it.name == CSRF_COOKIE }
        assertTrue(sessionCookie.httpOnly)
        assertEquals(Cookie.SameSite.LAX, sessionCookie.sameSite)
        assertFalse(csrfCookie.httpOnly)
        assertTrue(csrfCookie.secure)
        assertEquals(300, csrfCookie.maxAge)
        assertEquals(0, fixture.states.size)

        val snapshot = fixture.store.snapshot()
        val session = snapshot.sessions.single()
        assertEquals(AuthenticationAssurance.SESSION, session.assurance)
        assertEquals(SessionAuthenticationMethod.SAML, session.authenticationMethod)
        assertEquals(TENANT_ID, session.federationOrganizationId)
        assertEquals(PROVIDER_STORAGE_KEY, session.federationProviderKey)
        assertEquals(EXTERNAL_IDENTITY_ID, session.externalIdentityId)
        assertEquals(AuditAction.SESSION_CREATED, snapshot.auditEvents.single().action)
        assertFalse(session.tokenDigest.encoded in sessionCookie.value)
        assertTrue(fixture.provider.authenticationStateSeen!!.relayStateBytes().all { it == 0.toByte() })
    }

    @Test
    fun `callback rotates the authenticated predecessor before setting the federated cookie`() = runTest {
        val fixture = Fixture(withPredecessor = true)
        val start = fixture.authenticate(fixture.exchange(HttpMethod.GET, fixture.startPath))
        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }
        val selector = start.response.cookies.single { it.name == STATE_COOKIE }.value

        val callback = fixture.callback(selector)
        fixture.middleware.asMiddleware()(callback) { error("Federation route must not fall through") }

        assertEquals(303, callback.response.statusCode)
        val predecessor = requireNotNull(fixture.predecessor)
        val snapshot = fixture.store.snapshot()
        val rotated = snapshot.sessions.single { it.id == predecessor.id }
        val replacement = snapshot.sessions.single { it.id != predecessor.id }
        assertEquals(SessionState.ROTATED, rotated.state)
        assertEquals(replacement.id, rotated.rotatedToId)
        assertEquals(predecessor.id, replacement.rotatedFromId)
        assertEquals(predecessor.familyId, replacement.familyId)
        assertEquals(SessionAuthenticationMethod.SAML, replacement.authenticationMethod)
        assertEquals(AuditAction.SESSION_ROTATED, snapshot.auditEvents.single().action)
    }

    @Test
    fun `callback rejects a federated user without an active tenant membership`() = runTest {
        val fixture = Fixture(membershipState = MembershipState.SUSPENDED)
        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)
        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }
        val selector = start.response.cookies.single { it.name == STATE_COOKIE }.value

        val callback = fixture.callback(selector)
        fixture.middleware.asMiddleware()(callback) { error("Federation route must not fall through") }

        assertEquals(400, callback.response.statusCode)
        assertEquals(emptyList(), fixture.store.snapshot().sessions)
        assertEquals(emptyList(), fixture.store.snapshot().auditEvents)
        assertGenericError(callback)
    }

    @Test
    fun `callback state is tenant-bound and atomically single use`() = runTest {
        val fixture = Fixture(includeSecondProvider = true)
        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)
        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }
        val selector = start.response.cookies.single { it.name == STATE_COOKIE }.value

        val mismatch = fixture.callback(selector, providerId = "other")
        fixture.middleware.asMiddleware()(mismatch) { error("Federation route must not fall through") }
        assertEquals(400, mismatch.response.statusCode)
        assertEquals(0, fixture.otherProvider.callbackCount)
        assertEquals(0, fixture.states.size)

        val replay = fixture.callback(selector)
        fixture.middleware.asMiddleware()(replay) { error("Federation route must not fall through") }
        assertEquals(400, replay.response.statusCode)
        assertEquals(0, fixture.provider.callbackCount)
        assertEquals(0, fixture.store.snapshot().sessions.size)
        assertGenericError(replay)
    }

    @Test
    fun `provider-disabled callback is mapped to indistinguishable not found`() = runTest {
        val fixture = Fixture()
        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)
        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }
        val selector = start.response.cookies.single { it.name == STATE_COOKIE }.value
        fixture.provider.enabled = false

        val disabled = fixture.callback(selector)
        fixture.middleware.asMiddleware()(disabled) { error("Federation route must not fall through") }

        assertEquals(404, disabled.response.statusCode)
        assertEquals(1, fixture.provider.callbackCount)
        assertEquals(0, fixture.states.size)
        assertEquals(0, fixture.store.snapshot().sessions.size)
        assertGenericError(disabled)
    }

    @Test
    fun `disable immediately before session creation rejects the stale lease`() = runTest {
        val fixture = Fixture()
        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)
        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }
        val selector = start.response.cookies.single { it.name == STATE_COOKIE }.value
        fixture.provider.beforeSuccess = { fixture.disableProvider() }

        val callback = fixture.callback(selector)
        fixture.middleware.asMiddleware()(callback) { error("Federation route must not fall through") }

        assertEquals(400, callback.response.statusCode)
        assertEquals(emptyList(), fixture.store.snapshot().sessions)
        assertGenericError(callback)
    }

    @Test
    fun `disable then re-enable before session creation still rejects the stale callback lease`() = runTest {
        val fixture = Fixture()
        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)
        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }
        val selector = start.response.cookies.single { it.name == STATE_COOKIE }.value
        fixture.provider.beforeSuccess = {
            fixture.disableProvider()
            fixture.enableProvider()
        }

        val callback = fixture.callback(selector)
        fixture.middleware.asMiddleware()(callback) { error("Federation route must not fall through") }

        assertEquals(400, callback.response.statusCode)
        assertEquals(emptyList(), fixture.store.snapshot().sessions)
        assertGenericError(callback)
    }

    @Test
    fun `callback result with a different provider lease is rejected before session creation`() = runTest {
        val fixture = Fixture()
        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)
        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }
        val selector = start.response.cookies.single { it.name == STATE_COOKIE }.value
        fixture.provider.resultLease = PROVIDER_LEASE.copy(version = PROVIDER_LEASE.version + 1)

        val callback = fixture.callback(selector)
        fixture.middleware.asMiddleware()(callback) { error("Federation route must not fall through") }

        assertEquals(400, callback.response.statusCode)
        assertEquals(emptyList(), fixture.store.snapshot().sessions)
        assertGenericError(callback)
    }

    @Test
    fun `cross-site POST without the correlation cookie fails before assertion handling`() = runTest {
        val fixture = Fixture()
        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)
        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }
        val body = "SAMLResponse=$SAML_RESPONSE&RelayState=$RELAY_STATE".encodeToByteArray()
        val missing = fixture.exchange(
            HttpMethod.POST,
            fixture.callbackPath,
            headers = Headers.of(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Content-Length" to body.size.toString()
            ),
            body = body
        )

        fixture.middleware.asMiddleware()(missing) { error("Federation route must not fall through") }

        assertEquals(400, missing.response.statusCode)
        assertEquals(0, fixture.provider.callbackCount)
        assertEquals(1, fixture.states.size)
        val missingClearCookie = missing.response.cookies.single { it.name == STATE_COOKIE }
        assertEquals(0, missingClearCookie.maxAge)
        assertEquals(Cookie.SameSite.NONE, missingClearCookie.sameSite)
        assertGenericError(missing)

        val wrongSelector = Base64Url.encode(ByteArray(32) { 0x6f })
        val mismatched = fixture.callback(wrongSelector)
        fixture.middleware.asMiddleware()(mismatched) { error("Federation route must not fall through") }
        assertEquals(400, mismatched.response.statusCode)
        assertEquals(0, fixture.provider.callbackCount)
        assertEquals(1, fixture.states.size)
        val mismatchClearCookie = mismatched.response.cookies.single { it.name == STATE_COOKIE }
        assertEquals(0, mismatchClearCookie.maxAge)
        assertEquals(Cookie.SameSite.NONE, mismatchClearCookie.sameSite)
        assertGenericError(mismatched)
    }

    @Test
    fun `strict method content type form shape and body bounds reject malformed callbacks`() = runTest {
        val fixture = Fixture()
        suspend fun execute(exchange: TestExchange): TestExchange {
            fixture.middleware.asMiddleware()(exchange) { error("Federation route must not fall through") }
            return exchange
        }

        assertEquals(405, execute(fixture.exchange(HttpMethod.POST, fixture.startPath)).response.statusCode)
        assertEquals(400, execute(fixture.exchange(HttpMethod.GET, fixture.startPath, query = "returnTo=evil")).response.statusCode)

        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)
        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }
        val selector = start.response.cookies.single { it.name == STATE_COOKIE }.value

        val wrongMethod = fixture.exchange(
            HttpMethod.GET,
            fixture.callbackPath,
            headers = Headers.of("Cookie" to "$STATE_COOKIE=$selector"),
            cookies = Cookies.of(Cookie(STATE_COOKIE, selector))
        )
        assertEquals(405, execute(wrongMethod).response.statusCode)

        val wrongType = fixture.callback(
            selector,
            headers = Headers.of(
                "Cookie" to "$STATE_COOKIE=$selector",
                "Content-Type" to "application/xml"
            )
        )
        assertEquals(400, execute(wrongType).response.statusCode)
        assertEquals(0, wrongType.requestValue.bodyReads)

        val duplicate = fixture.callback(
            selector,
            bodyText = "SAMLResponse=$SAML_RESPONSE&SAMLResponse=duplicate&RelayState=$RELAY_STATE"
        )
        assertEquals(400, execute(duplicate).response.statusCode)
        assertEquals(0, fixture.provider.callbackCount)

        val declaredOversize = fixture.callback(
            selector,
            headers = Headers.of(
                "Cookie" to "$STATE_COOKIE=$selector",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Content-Length" to "4097"
            ),
            bodyText = "x"
        )
        assertEquals(400, execute(declaredOversize).response.statusCode)
        assertEquals(0, declaredOversize.requestValue.bodyReads)
    }

    @Test
    fun `assertions provider exceptions and internal details are redacted`() = runTest {
        val fixture = Fixture()
        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)
        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }
        val selector = start.response.cookies.single { it.name == STATE_COOKIE }.value
        fixture.provider.callbackFailure = IllegalStateException(
            "assertion $SAML_RESPONSE contains signing-key super-secret"
        )

        val failed = fixture.callback(selector)
        fixture.middleware.asMiddleware()(failed) { error("Federation route must not fall through") }

        assertEquals(503, failed.response.statusCode)
        assertGenericError(failed)
        val body = failed.response.bodyText()
        assertFalse(body.contains(SAML_RESPONSE))
        assertFalse(body.contains("assertion", ignoreCase = true))
        assertFalse(body.contains("signing-key", ignoreCase = true))
        assertEquals(0, fixture.store.snapshot().sessions.size)
    }

    @Test
    fun `not-owned resolution composes with another federation adapter`() = runTest {
        val fixture = Fixture(notOwned = true)
        val exchange = fixture.exchange(HttpMethod.GET, fixture.startPath)
        var continued = false

        fixture.middleware.asMiddleware()(exchange) { continued = true }

        assertTrue(continued)
        assertEquals(200, exchange.response.statusCode)
        assertEquals(0, exchange.requestValue.bodyReads)
    }

    @Test
    fun `IdP redirect outside the configured endpoint allowlist is never emitted`() = runTest {
        val fixture = Fixture()
        fixture.provider.redirectUrl = "https://attacker.example.test/sso?SAMLRequest=stolen"
        val exchange = fixture.exchange(HttpMethod.GET, fixture.startPath)

        fixture.middleware.asMiddleware()(exchange) { error("Federation route must not fall through") }

        assertEquals(503, exchange.response.statusCode)
        assertEquals(null, exchange.response.headers.build()["Location"])
        assertEquals(0, fixture.states.size)
        assertGenericError(exchange)
    }

    private class Fixture(
        includeSecondProvider: Boolean = false,
        private val notOwned: Boolean = false,
        withPredecessor: Boolean = false,
        membershipState: MembershipState = MembershipState.ACTIVE
    ) {
        val config = identityConfig()
        private val secretResolver = DeterministicIdentitySecretResolver(
            mapOf(config.keys.sessionPepper to ByteArray(32) { 0x42 })
        )
        private val deterministic = DeterministicIdentityRuntime(deterministicSecrets = secretResolver)
        private val user = IdentityFixtures.user(USER_ID)
        private val organization = IdentityFixtures.organization(TENANT_ID)
        private val membership = IdentityFixtures.membership(
            organizationId = TENANT_ID,
            userId = USER_ID,
            state = membershipState
        )
        val predecessor: IdentitySession? = if (withPredecessor) {
            IdentityFixtures.session(
                id = IdentityFixtures.sessionId("saml-callback-predecessor"),
                userId = USER_ID
            )
        } else {
            null
        }
        val store = InMemoryIdentityStore(
            InMemoryIdentityStoreSeed(
                users = listOf(user),
                sessions = listOfNotNull(predecessor),
                organizations = listOf(organization),
                memberships = listOf(membership),
                federationProviderControls = listOf(
                    FederationProviderControl(
                        organizationId = TENANT_ID,
                        kind = FederationProviderKind.SAML,
                        providerId = PROVIDER_ID,
                        storageKey = PROVIDER_STORAGE_KEY,
                        createdAt = IdentityFixtures.baseInstant,
                        updatedAt = IdentityFixtures.baseInstant
                    )
                )
            )
        )
        val states = InMemoryCallbackStates()
        val provider = FakeProvider(PROVIDER_ID, deterministic)
        val otherProvider = FakeProvider("other", deterministic)
        private val registrations = buildMap {
            put(PROVIDER_ID, registration(provider))
            if (includeSecondProvider) put("other", registration(otherProvider))
        }
        val middleware = SamlFederationHttpMiddleware(
            runtime = deterministic.runtime,
            identityConfig = config,
            providers = SamlFederationProviderRegistry { tenantId, providerId ->
                when {
                    notOwned -> SamlFederationProviderResolution.NotOwned
                    tenantId == TENANT_ID && registrations[providerId] != null ->
                        SamlFederationProviderResolution.Found(registrations.getValue(providerId))
                    else -> SamlFederationProviderResolution.Missing
                }
            },
            callbackStates = states,
            sessions = FederatedIdentitySessionService(store, deterministic.runtime, config),
            config = SamlFederationHttpConfig(maximumBodyBytes = 4_096, maximumEncodedResponseCharacters = 4_096)
        )
        val startPath = "/identity/v1/federation/${TENANT_ID.value}/$PROVIDER_ID/start"
        val callbackPath = "/identity/v1/federation/${TENANT_ID.value}/$PROVIDER_ID/callback"

        suspend fun disableProvider() {
            assertIs<codes.yousef.aether.auth.IdentityOperationResult.Success<*>>(
                IdentityFederationProviderManager(store, deterministic.runtime).disableProvider(
                    TENANT_ID,
                    FederationProviderKind.SAML,
                    PROVIDER_ID,
                    PROVIDER_STORAGE_KEY,
                    reasonCode = "saml_test_disabled"
                )
            )
        }

        suspend fun enableProvider() {
            assertIs<codes.yousef.aether.auth.IdentityOperationResult.Success<*>>(
                IdentityFederationProviderManager(store, deterministic.runtime).enableProvider(
                    TENANT_ID,
                    FederationProviderKind.SAML,
                    PROVIDER_ID,
                    PROVIDER_STORAGE_KEY,
                    reasonCode = "saml_test_enabled"
                )
            )
        }

        fun exchange(
            method: HttpMethod,
            path: String,
            query: String? = null,
            headers: Headers = Headers.Empty,
            cookies: Cookies = Cookies.Empty,
            body: ByteArray = ByteArray(0)
        ): TestExchange = TestExchange(method, path, query, headers, cookies, body)

        fun authenticate(exchange: TestExchange): TestExchange = exchange.also { authenticated ->
            predecessor?.let { session ->
                authenticated.attributes.put(
                    IdentityContextAttributeKey,
                    IdentityContext(
                        principal = IdentityPrincipal(
                            kind = IdentityPrincipalKind.USER,
                            userId = user.id,
                            displayName = user.displayName,
                            assurance = session.assurance,
                            authenticatedAt = session.authenticatedAt,
                            sessionId = session.id
                        ),
                        session = session
                    )
                )
            }
        }

        fun callback(
            selector: String,
            providerId: String = PROVIDER_ID,
            headers: Headers? = null,
            bodyText: String = "SAMLResponse=$SAML_RESPONSE&RelayState=$RELAY_STATE"
        ): TestExchange {
            val body = bodyText.encodeToByteArray()
            return exchange(
                HttpMethod.POST,
                "/identity/v1/federation/${TENANT_ID.value}/$providerId/callback",
                headers = headers ?: Headers.of(
                    "Cookie" to "$STATE_COOKIE=$selector",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Content-Length" to body.size.toString(),
                    "User-Agent" to "Test Browser/1.0"
                ),
                cookies = Cookies.of(Cookie(STATE_COOKIE, selector)),
                body = body
            )
        }

        private fun registration(provider: FakeProvider) = SamlFederationProviderRegistration(
            provider = provider,
            allowedSsoRedirectEndpoints = setOf(SSO_ENDPOINT),
            successRedirectUrl = SUCCESS_REDIRECT
        )
    }

    private class FakeProvider(
        override val configuredProviderId: String,
        private val deterministic: DeterministicIdentityRuntime
    ) : SamlFederationProvider {
        override val configuredTenantId: OrganizationId = TENANT_ID
        var enabled: Boolean = true
        var callbackCount: Int = 0
        var callbackFailure: Throwable? = null
        var beforeSuccess: suspend () -> Unit = {}
        var resultLease: FederationProviderLease = PROVIDER_LEASE
        var redirectUrl: String = "$SSO_ENDPOINT?SAMLRequest=fake-request&RelayState=$RELAY_STATE"
        var authenticationStateSeen: SamlAuthenticationState? = null

        override suspend fun beginAuthentication(
            request: SamlAuthenticationRequest
        ): SamlResult<SamlAuthenticationStart> {
            if (!enabled) return SamlResult.Failure(SamlError(SamlErrorCode.PROVIDER_DISABLED))
            val now = deterministic.deterministicClock.now()
            val state = SamlAuthenticationState(
                challengeId = ChallengeId("challenge-$configuredProviderId"),
                requestId = "_challenge-$configuredProviderId",
                relayState = RELAY_BYTES,
                linkToUserId = null,
                providerLease = PROVIDER_LEASE,
                expiresAt = now + 5.minutes
            )
            return SamlResult.Success(
                SamlAuthenticationStart(
                    redirectUrl = redirectUrl,
                    state = state,
                    expiresAt = state.expiresAt
                )
            )
        }

        override suspend fun completeAuthentication(
            request: SamlPostResponseRequest
        ): SamlResult<SamlAuthenticationResult> {
            callbackCount += 1
            authenticationStateSeen = request.state
            callbackFailure?.let { throw it }
            if (!enabled) return SamlResult.Failure(SamlError(SamlErrorCode.PROVIDER_DISABLED))
            if (request.samlResponse != SAML_RESPONSE || request.relayState != RELAY_STATE) {
                return SamlResult.Failure(SamlError(SamlErrorCode.RESPONSE_INVALID))
            }
            beforeSuccess()
            val now = deterministic.deterministicClock.now()
            return SamlResult.Success(
                SamlAuthenticationResult(
                    userId = USER_ID,
                    externalIdentityId = EXTERNAL_IDENTITY_ID,
                    providerLease = resultLease,
                    claims = SamlVerifiedClaims(
                        issuer = "https://idp.example.test",
                        subject = ExternalSubject("external-subject"),
                        nameIdFormat = "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent",
                        issuedAt = now,
                        authenticatedAt = now,
                        expiresAt = now + 5.minutes,
                        sessionIndex = "session-index",
                        authenticationContext = "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport",
                        attributes = emptyMap()
                    )
                )
            )
        }
    }

    private class InMemoryCallbackStates : FederationCallbackStateStore<SamlServerCallbackState> {
        private val values = mutableMapOf<String, SamlServerCallbackState>()
        val size: Int get() = values.size

        override suspend fun store(
            selector: String,
            state: SamlServerCallbackState
        ): FederationCallbackStateWriteResult = if (values.containsKey(selector)) {
            FederationCallbackStateWriteResult.Conflict
        } else {
            values[selector] = state
            FederationCallbackStateWriteResult.Stored
        }

        override suspend fun consume(
            selector: String
        ): FederationCallbackStateConsumeResult<SamlServerCallbackState> =
            values.remove(selector)?.let { FederationCallbackStateConsumeResult.Consumed(it) }
                ?: FederationCallbackStateConsumeResult.Missing
    }

    private companion object {
        val TENANT_ID = OrganizationId("01900000-0000-7000-8000-000000000200")
        val USER_ID = UserId("01900000-0000-7000-8000-000000000201")
        val EXTERNAL_IDENTITY_ID = ExternalIdentityId("01900000-0000-7000-8000-000000000202")
        val RELAY_BYTES = ByteArray(32) { (it + 1).toByte() }
        val RELAY_STATE = Base64Url.encode(RELAY_BYTES)
        const val PROVIDER_ID = "enterprise"
        val PROVIDER_STORAGE_KEY = IdentityFixtures.federationProviderStorageKey(
            FederationProviderKind.SAML,
            "saml-middleware-enterprise"
        )
        val PROVIDER_LEASE = FederationProviderLease(
            organizationId = TENANT_ID,
            kind = FederationProviderKind.SAML,
            providerId = PROVIDER_ID,
            storageKey = PROVIDER_STORAGE_KEY,
            sessionEpoch = 0,
            version = 0
        )
        const val SAML_RESPONSE = "encoded-saml-assertion-value"
        const val SSO_ENDPOINT = "https://idp.example.test/sso"
        const val SUCCESS_REDIRECT = "https://identity.example.test/account/security"
        const val STATE_COOKIE = "__Host-aether_saml_state"
        const val CSRF_COOKIE = "__Host-aether_csrf"
    }
}

private class TestRequest(
    override val method: HttpMethod,
    override val path: String,
    override val query: String?,
    override val headers: Headers,
    override val cookies: Cookies,
    private val body: ByteArray
) : Request {
    override val uri: String = if (query == null) path else "$path?$query"
    override val connection: RequestConnection =
        RequestConnection("https", "identity.example.test", "127.0.0.1")
    var bodyReads: Int = 0
        private set

    override suspend fun bodyBytes(): ByteArray {
        bodyReads += 1
        return body.copyOf()
    }
}

private class TestResponse : Response {
    override var statusCode: Int = 200
    override var statusMessage: String? = null
    override val headers = Headers.HeadersBuilder()
    override val cookies = mutableListOf<Cookie>()
    private val body = mutableListOf<Byte>()

    override suspend fun write(data: ByteArray) { body += data.toList() }
    override suspend fun end() = Unit
    fun bodyText(): String = body.toByteArray().decodeToString()
}

private class TestExchange(
    method: HttpMethod,
    path: String,
    query: String?,
    headers: Headers,
    cookies: Cookies,
    body: ByteArray
) : Exchange {
    val requestValue = TestRequest(method, path, query, headers, cookies, body)
    override val request: Request = requestValue
    override val response = TestResponse()
    override val attributes = Attributes()
}

private fun identityConfig(): IdentityConfig {
    fun secret(name: String) = SecretReference("test", name, "v1", IdentityEnvironment.TEST)
    return IdentityConfig(
        environment = IdentityEnvironment.TEST,
        publicBaseUrl = "https://identity.example.test",
        relyingParty = RelyingPartyConfig(
            id = "identity.example.test",
            name = "SAML middleware test",
            allowedOrigins = setOf("https://identity.example.test")
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

private fun assertGenericError(exchange: TestExchange) {
    val payload = Json.parseToJsonElement(exchange.response.bodyText()).jsonObject
    assertEquals(setOf("code", "message", "requestId", "retryable"), payload.keys)
    assertFalse(payload.getValue("message").toString().contains("secret", ignoreCase = true))
    assertEquals("no-store", exchange.response.headers.build()["Cache-Control"])
    assertNotNull(exchange.response.headers.build()["X-Content-Type-Options"])
}
