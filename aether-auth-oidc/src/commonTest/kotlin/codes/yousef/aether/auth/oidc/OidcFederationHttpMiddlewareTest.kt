package codes.yousef.aether.auth.oidc

import codes.yousef.aether.auth.AuditAction
import codes.yousef.aether.auth.AuthenticationAssurance
import codes.yousef.aether.auth.ChallengeId
import codes.yousef.aether.auth.EmailAddress
import codes.yousef.aether.auth.ExternalIdentityId
import codes.yousef.aether.auth.ExternalSubject
import codes.yousef.aether.auth.FederationCallbackStateConsumeResult
import codes.yousef.aether.auth.FederationCallbackStateStore
import codes.yousef.aether.auth.FederationCallbackStateWriteResult
import codes.yousef.aether.auth.FederationProviderKind
import codes.yousef.aether.auth.FederationProviderLease
import codes.yousef.aether.auth.FederatedIdentitySessionService
import codes.yousef.aether.auth.IdentityConfig
import codes.yousef.aether.auth.IdentityContext
import codes.yousef.aether.auth.IdentityContextAttributeKey
import codes.yousef.aether.auth.IdentityEnvironment
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class OidcFederationHttpMiddlewareTest {
    @Test
    fun `start and callback keep PKCE state server-side and create provenance session`() = runTest {
        val fixture = Fixture()
        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)

        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }

        assertEquals(302, start.response.statusCode)
        assertTrue(start.response.headers.build()["Location"]!!.startsWith("$AUTHORIZATION_ENDPOINT?"))
        val stateCookie = start.response.cookies.single { it.name == STATE_COOKIE }
        assertTrue(stateCookie.secure)
        assertTrue(stateCookie.httpOnly)
        assertEquals(Cookie.SameSite.LAX, stateCookie.sameSite)
        assertEquals(43, stateCookie.value.length)
        assertFalse(start.response.headers.build()["Location"]!!.contains("verifier", ignoreCase = true))
        assertFalse(start.response.bodyText().contains("secret", ignoreCase = true))
        assertEquals(1, fixture.states.size)

        val callback = fixture.callback(stateCookie.value)
        fixture.middleware.asMiddleware()(callback) { error("Federation route must not fall through") }

        assertEquals(303, callback.response.statusCode)
        assertEquals(SUCCESS_REDIRECT, callback.response.headers.build()["Location"])
        assertFalse(callback.response.headers.build()["Location"]!!.contains("csrf", ignoreCase = true))
        val sessionCookie = callback.response.cookies.single { it.name == fixture.config.cookie.name }
        val csrfCookie = callback.response.cookies.single { it.name == CSRF_COOKIE }
        assertTrue(sessionCookie.secure)
        assertTrue(sessionCookie.httpOnly)
        assertEquals(Cookie.SameSite.LAX, sessionCookie.sameSite)
        assertTrue(csrfCookie.secure)
        assertFalse(csrfCookie.httpOnly)
        assertEquals(300, csrfCookie.maxAge)
        assertEquals(0, fixture.states.size)

        val snapshot = fixture.store.snapshot()
        val session = snapshot.sessions.single()
        assertEquals(AuthenticationAssurance.SESSION, session.assurance)
        assertEquals(SessionAuthenticationMethod.OIDC, session.authenticationMethod)
        assertEquals(TENANT_ID, session.federationOrganizationId)
        assertEquals(PROVIDER_STORAGE_KEY, session.federationProviderKey)
        assertEquals(EXTERNAL_IDENTITY_ID, session.externalIdentityId)
        assertEquals(USER_ID, session.userId)
        assertEquals(AuditAction.SESSION_CREATED, snapshot.auditEvents.single().action)
        assertFalse(session.tokenDigest.encoded in sessionCookie.value)
        fixture.provider.callbackSecretSeen!!.useSeedForProtection { seed ->
            assertTrue(seed.all { it == 0.toByte() })
        }
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
        assertEquals(SessionAuthenticationMethod.OIDC, replacement.authenticationMethod)
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
    fun `dynamic kill switch is checked again on callback and consumes correlation`() = runTest {
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
    fun `strict route query method and body bounds reject malformed callbacks`() = runTest {
        val fixture = Fixture()
        suspend fun execute(exchange: TestExchange): TestExchange {
            fixture.middleware.asMiddleware()(exchange) { error("Federation route must not fall through") }
            return exchange
        }

        assertEquals(404, execute(fixture.exchange(HttpMethod.GET, "/identity/v1/federation/$TENANT_ID/bad!/start")).response.statusCode)
        assertEquals(405, execute(fixture.exchange(HttpMethod.POST, fixture.startPath)).response.statusCode)
        assertEquals(
            400,
            execute(fixture.exchange(HttpMethod.GET, fixture.startPath, query = "returnTo=https://evil.test")).response.statusCode
        )

        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)
        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }
        val selector = start.response.cookies.single { it.name == STATE_COOKIE }.value
        val duplicate = fixture.callback(
            selector,
            query = "state=$PROVIDER_STATE&state=attacker-state-value&code=$AUTHORIZATION_CODE"
        )
        assertEquals(400, execute(duplicate).response.statusCode)
        assertEquals(0, fixture.provider.callbackCount)

        val oversized = fixture.callback(selector, query = "state=$PROVIDER_STATE&code=${"a".repeat(12_289)}")
        assertEquals(400, execute(oversized).response.statusCode)
        assertEquals(0, fixture.provider.callbackCount)

        val declaredBody = fixture.callback(
            selector,
            headers = Headers.of("Content-Length" to "1", "Cookie" to "$STATE_COOKIE=$selector"),
            body = byteArrayOf(1)
        )
        assertEquals(400, execute(declaredBody).response.statusCode)
        assertEquals(0, declaredBody.requestValue.bodyReads)
    }

    @Test
    fun `provider exceptions and credential material are redacted from generic errors`() = runTest {
        val fixture = Fixture()
        val start = fixture.exchange(HttpMethod.GET, fixture.startPath)
        fixture.middleware.asMiddleware()(start) { error("Federation route must not fall through") }
        val selector = start.response.cookies.single { it.name == STATE_COOKIE }.value
        fixture.provider.callbackFailure = IllegalStateException(
            "leaked $AUTHORIZATION_CODE with PKCE verifier super-secret-verifier"
        )

        val failed = fixture.callback(selector)
        fixture.middleware.asMiddleware()(failed) { error("Federation route must not fall through") }

        assertEquals(503, failed.response.statusCode)
        assertGenericError(failed)
        val body = failed.response.bodyText()
        assertFalse(body.contains(AUTHORIZATION_CODE))
        assertFalse(body.contains("verifier", ignoreCase = true))
        assertFalse(body.contains("super-secret", ignoreCase = true))
        assertEquals(0, fixture.store.snapshot().sessions.size)
    }

    @Test
    fun `registry configuration mismatch fails closed while not-owned routes compose`() = runTest {
        val mismatched = Fixture(mismatchedRegistration = true)
        val denied = mismatched.exchange(HttpMethod.GET, mismatched.startPath)
        var deniedFallthrough = false
        mismatched.middleware.asMiddleware()(denied) { deniedFallthrough = true }
        assertEquals(404, denied.response.statusCode)
        assertFalse(deniedFallthrough)

        val notOwned = Fixture(notOwned = true)
        val delegated = notOwned.exchange(HttpMethod.GET, notOwned.startPath)
        var continued = false
        notOwned.middleware.asMiddleware()(delegated) { continued = true }
        assertTrue(continued)
        assertEquals(200, delegated.response.statusCode)
        assertEquals(0, delegated.requestValue.bodyReads)
    }

    @Test
    fun `provider redirect outside the configured endpoint allowlist is never emitted`() = runTest {
        val fixture = Fixture()
        fixture.provider.authorizationUrl = "https://attacker.example.test/authorize?state=stolen"
        val exchange = fixture.exchange(HttpMethod.GET, fixture.startPath)

        fixture.middleware.asMiddleware()(exchange) { error("Federation route must not fall through") }

        assertEquals(503, exchange.response.statusCode)
        assertEquals(null, exchange.response.headers.build()["Location"])
        assertEquals(0, fixture.states.size)
        assertGenericError(exchange)
    }

    private class Fixture(
        includeSecondProvider: Boolean = false,
        mismatchedRegistration: Boolean = false,
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
        private val providerControl = IdentityFixtures.federationProviderControl(
            organizationId = TENANT_ID,
            kind = FederationProviderKind.OIDC,
            providerId = PROVIDER_ID,
            storageKey = PROVIDER_STORAGE_KEY
        )
        private val otherProviderControl = IdentityFixtures.federationProviderControl(
            organizationId = TENANT_ID,
            kind = FederationProviderKind.OIDC,
            providerId = "other",
            storageKey = IdentityFixtures.federationProviderStorageKey(
                FederationProviderKind.OIDC,
                "other"
            )
        )
        val predecessor: IdentitySession? = if (withPredecessor) {
            IdentityFixtures.session(
                id = IdentityFixtures.sessionId("oidc-callback-predecessor"),
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
                federationProviderControls = listOf(providerControl) +
                    listOfNotNull(otherProviderControl.takeIf { includeSecondProvider })
            )
        )
        val states = InMemoryCallbackStates()
        val provider = FakeProvider(
            PROVIDER_ID,
            deterministic,
            IdentityFixtures.federationProviderLease(providerControl)
        )
        val otherProvider = FakeProvider(
            "other",
            deterministic,
            IdentityFixtures.federationProviderLease(otherProviderControl)
        )
        private val registrations = buildMap {
            put(PROVIDER_ID, registration(if (mismatchedRegistration) otherProvider else provider))
            if (includeSecondProvider) put("other", registration(otherProvider))
        }
        val middleware = OidcFederationHttpMiddleware(
            runtime = deterministic.runtime,
            identityConfig = config,
            providers = OidcFederationProviderRegistry { tenantId, providerId ->
                if (notOwned) {
                    OidcFederationProviderResolution.NotOwned
                } else {
                    registrations[providerId]?.takeIf { tenantId == TENANT_ID }
                        ?.let(OidcFederationProviderResolution::Found)
                        ?: OidcFederationProviderResolution.Missing
                }
            },
            callbackStates = states,
            sessions = FederatedIdentitySessionService(store, deterministic.runtime, config)
        )
        val startPath = "/identity/v1/federation/${TENANT_ID.value}/$PROVIDER_ID/start"

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
            query: String = "state=$PROVIDER_STATE&code=$AUTHORIZATION_CODE",
            headers: Headers = Headers.of("Cookie" to "$STATE_COOKIE=$selector", "User-Agent" to "Test Browser/1.0"),
            body: ByteArray = ByteArray(0)
        ): TestExchange = exchange(
            HttpMethod.GET,
            "/identity/v1/federation/${TENANT_ID.value}/$providerId/callback",
            query,
            headers,
            Cookies.of(Cookie(STATE_COOKIE, selector)),
            body
        )

        private fun registration(provider: FakeProvider) = OidcFederationProviderRegistration(
            provider = provider,
            allowedAuthorizationEndpoints = setOf(AUTHORIZATION_ENDPOINT),
            successRedirectUrl = SUCCESS_REDIRECT
        )
    }

    private class FakeProvider(
        override val configuredProviderId: String,
        private val deterministic: DeterministicIdentityRuntime,
        private val providerLease: FederationProviderLease
    ) : OidcFederationProvider {
        override val configuredTenantId: OrganizationId = TENANT_ID
        var enabled: Boolean = true
        var callbackCount: Int = 0
        var callbackFailure: Throwable? = null
        var authorizationUrl: String = "$AUTHORIZATION_ENDPOINT?client_id=test&state=$PROVIDER_STATE"
        var callbackSecretSeen: OidcCallbackSecret? = null

        override suspend fun beginAuthorization(request: OidcAuthorizationRequest): OidcResult<OidcAuthorizationStart> {
            if (!enabled) return OidcResult.Failure(OidcError(OidcErrorCode.PROVIDER_DISABLED))
            return OidcResult.Success(
                OidcAuthorizationStart(
                    authorizationUrl = authorizationUrl,
                    callbackSecret = OidcCallbackSecret.restore(ChallengeId("challenge-$configuredProviderId"), ByteArray(32) { 0x5a }),
                    providerLease = providerLease,
                    expiresAt = deterministic.deterministicClock.now() + 5.minutes
                )
            )
        }

        override suspend fun completeAuthorization(request: OidcCallbackRequest): OidcResult<OidcAuthenticationResult> {
            callbackCount += 1
            callbackSecretSeen = request.callbackSecret
            callbackFailure?.let { throw it }
            if (!enabled) return OidcResult.Failure(OidcError(OidcErrorCode.PROVIDER_DISABLED))
            if (request.state != PROVIDER_STATE || request.authorizationCode != AUTHORIZATION_CODE ||
                request.providerLease != providerLease
            ) {
                return OidcResult.Failure(OidcError(OidcErrorCode.INVALID_CALLBACK))
            }
            val now = deterministic.deterministicClock.now()
            return OidcResult.Success(
                OidcAuthenticationResult(
                    userId = USER_ID,
                    externalIdentityId = EXTERNAL_IDENTITY_ID,
                    providerLease = providerLease,
                    claims = OidcVerifiedClaims(
                        issuer = "https://issuer.example.test",
                        subject = ExternalSubject("external-subject"),
                        audiences = setOf("client"),
                        authorizedParty = null,
                        issuedAt = now,
                        expiresAt = now + 5.minutes,
                        email = EmailAddress("user@example.test"),
                        displayName = "Test User"
                    )
                )
            )
        }
    }

    private class InMemoryCallbackStates : FederationCallbackStateStore<OidcServerCallbackState> {
        private val values = mutableMapOf<String, OidcServerCallbackState>()
        val size: Int get() = values.size

        override suspend fun store(
            selector: String,
            state: OidcServerCallbackState
        ): FederationCallbackStateWriteResult = if (values.containsKey(selector)) {
            FederationCallbackStateWriteResult.Conflict
        } else {
            values[selector] = state
            FederationCallbackStateWriteResult.Stored
        }

        override suspend fun consume(
            selector: String
        ): FederationCallbackStateConsumeResult<OidcServerCallbackState> =
            values.remove(selector)?.let { FederationCallbackStateConsumeResult.Consumed(it) }
                ?: FederationCallbackStateConsumeResult.Missing
    }

    private companion object {
        val TENANT_ID = OrganizationId("01900000-0000-7000-8000-000000000100")
        val USER_ID = UserId("01900000-0000-7000-8000-000000000101")
        val EXTERNAL_IDENTITY_ID = ExternalIdentityId("01900000-0000-7000-8000-000000000102")
        const val PROVIDER_ID = "workforce"
        val PROVIDER_STORAGE_KEY = IdentityFixtures.federationProviderStorageKey(
            FederationProviderKind.OIDC,
            PROVIDER_ID
        )
        const val PROVIDER_STATE = "provider-state-value-12345"
        const val AUTHORIZATION_CODE = "authorization-code-value"
        const val AUTHORIZATION_ENDPOINT = "https://login.example.test/oauth2/authorize"
        const val SUCCESS_REDIRECT = "https://identity.example.test/account/security"
        const val STATE_COOKIE = "__Host-aether_oidc_state"
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
            name = "OIDC middleware test",
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
