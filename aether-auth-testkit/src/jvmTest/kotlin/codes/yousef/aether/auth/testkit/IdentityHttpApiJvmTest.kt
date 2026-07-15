package codes.yousef.aether.auth.testkit

import codes.yousef.aether.auth.*
import codes.yousef.aether.auth.webauthn.WebAuthnService
import codes.yousef.aether.core.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IdentityHttpApiJvmTest {
    @Test
    fun `registration HTTP boundary enforces every policy and trusted session provenance`() = runTest {
        suspend fun registrationCall(
            policy: RegistrationPolicy,
            method: SessionAuthenticationMethod,
            path: String = IdentityHttpApi.REGISTRATION_START,
            body: ByteArray = ByteArray(0)
        ): Pair<HttpApiFixture, TestHttpExchange> {
            val fixture = HttpApiFixture.create(registrationPolicy = policy)
            val assurance = when (method) {
                SessionAuthenticationMethod.PASSKEY -> AuthenticationAssurance.PASSKEY
                SessionAuthenticationMethod.OIDC,
                SessionAuthenticationMethod.SAML -> AuthenticationAssurance.SESSION
                else -> AuthenticationAssurance.RECOVERY
            }
            val ids = IdentityIdFactory(fixture.runtime.runtime.clock, fixture.runtime.runtime.secureRandom)
            val issuer = IdentitySessionIssuer(fixture.runtime.runtime, fixture.config)
            val issued = if (method == SessionAuthenticationMethod.OIDC || method == SessionAuthenticationMethod.SAML) {
                val kind = if (method == SessionAuthenticationMethod.OIDC) {
                    FederationProviderKind.OIDC
                } else {
                    FederationProviderKind.SAML
                }
                val control = IdentityFixtures.federationProviderControl(
                    organizationId = ids.newOrganizationId(),
                    kind = kind,
                    providerId = "workforce"
                )
                issuer.issueFederated(
                    user = fixture.user,
                    authenticationMethod = method,
                    providerLease = IdentityFixtures.federationProviderLease(control),
                    externalIdentityId = ids.newExternalIdentityId(),
                    authenticatedAt = fixture.runtime.deterministicClock.now()
                )
            } else {
                issuer.issue(
                    user = fixture.user,
                    assurance = assurance,
                    authenticationMethod = method,
                    authenticatedAt = fixture.runtime.deterministicClock.now()
                )
            }
            val context = IdentityContext(
                principal = IdentityPrincipal(
                    kind = IdentityPrincipalKind.USER,
                    userId = fixture.user.id,
                    displayName = fixture.user.displayName,
                    assurance = assurance,
                    authenticatedAt = issued.session.authenticatedAt,
                    sessionId = issued.session.id
                ),
                session = issued.session
            )
            val exchange = TestHttpExchange.call(
                method = HttpMethod.POST,
                path = path,
                body = body,
                headers = Headers.of(
                    "Content-Type" to "application/json",
                    "Origin" to fixture.config.publicBaseUrl,
                    "X-CSRF-Token" to issued.csrfToken()
                ),
                cookies = Cookies.of(Cookie(fixture.config.cookie.name, issued.cookieValue())),
                context = context
            )
            fixture.api.asMiddleware()(exchange) { error("Known route must not fall through") }
            return fixture to exchange
        }

        RegistrationPolicy.entries.forEach { policy ->
            val (fixture, exchange) = registrationCall(policy, SessionAuthenticationMethod.INVITATION)
            if (policy == RegistrationPolicy.DISABLED) {
                assertSafeError(exchange, "registration_not_allowed")
                assertTrue(fixture.store.snapshot().challenges.isEmpty())
            } else {
                assertEquals(200, exchange.response.statusCode, "Invitation enrollment should work under $policy")
                assertEquals(1, fixture.store.snapshot().challenges.size)
            }
        }

        for (method in listOf(
            SessionAuthenticationMethod.PASSKEY,
            SessionAuthenticationMethod.RECOVERY_CODE,
            SessionAuthenticationMethod.ADMINISTRATIVE_RECOVERY,
            SessionAuthenticationMethod.BOOTSTRAP
        )) {
            val (_, exchange) = registrationCall(RegistrationPolicy.DISABLED, method)
            assertEquals(200, exchange.response.statusCode, "$method must remain eligible under DISABLED")
        }

        for (method in listOf(SessionAuthenticationMethod.OIDC, SessionAuthenticationMethod.SAML)) {
            val (fixture, exchange) = registrationCall(RegistrationPolicy.INVITATION_ONLY, method)
            assertSafeError(exchange, "step_up_required")
            assertTrue(fixture.store.snapshot().challenges.isEmpty())
        }

        val openFixture = HttpApiFixture.create(registrationPolicy = RegistrationPolicy.OPEN)
        val anonymous = TestHttpExchange.post(IdentityHttpApi.REGISTRATION_START)
        openFixture.api.asMiddleware()(anonymous) { error("Known route must not fall through") }
        assertSafeError(anonymous, "authentication_required")

        val directFinishBody =
            """{"ceremonyId":"01900000-0000-7000-8000-000000000777","credentialName":"Bypass","credential":{"id":"AQ","rawId":"AQ","type":"public-key","response":{"clientDataJSON":"e30","attestationObject":"oA","transports":[]},"clientExtensionResults":{}}}"""
                .encodeToByteArray()
        val (_, directFinish) = registrationCall(
            RegistrationPolicy.DISABLED,
            SessionAuthenticationMethod.INVITATION,
            IdentityHttpApi.REGISTRATION_FINISH,
            directFinishBody
        )
        assertSafeError(directFinish, "challenge_invalid")
    }

    @Test
    fun `federated and stale passkey sessions cannot mutate durable account credentials`() = runTest {
        val fixture = HttpApiFixture.create()
        val ids = IdentityIdFactory(fixture.runtime.runtime.clock, fixture.runtime.runtime.secureRandom)
        val control = IdentityFixtures.federationProviderControl(
            organizationId = ids.newOrganizationId(),
            providerId = "workforce"
        )
        val issued = IdentitySessionIssuer(fixture.runtime.runtime, fixture.config).issueFederated(
            user = fixture.user,
            authenticationMethod = SessionAuthenticationMethod.OIDC,
            providerLease = IdentityFixtures.federationProviderLease(control),
            externalIdentityId = ids.newExternalIdentityId(),
            authenticatedAt = fixture.runtime.deterministicClock.now()
        )
        val federatedContext = IdentityContext(
            principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.USER,
                userId = fixture.user.id,
                displayName = fixture.user.displayName,
                assurance = AuthenticationAssurance.SESSION,
                authenticatedAt = issued.session.authenticatedAt,
                sessionId = issued.session.id
            ),
            session = issued.session
        )

        suspend fun federatedCall(method: HttpMethod, path: String, body: String? = null): TestHttpExchange {
            val exchange = TestHttpExchange.call(
                method = method,
                path = path,
                body = body?.encodeToByteArray() ?: ByteArray(0),
                headers = Headers.of(
                    *buildList {
                        add("Origin" to fixture.config.publicBaseUrl)
                        add("X-CSRF-Token" to issued.csrfToken())
                        if (body != null) add("Content-Type" to "application/json")
                    }.toTypedArray()
                ),
                cookies = Cookies.of(Cookie(fixture.config.cookie.name, issued.cookieValue())),
                context = federatedContext
            )
            fixture.api.asMiddleware()(exchange) { error("Known route must not fall through") }
            return exchange
        }

        assertSafeError(
            federatedCall(HttpMethod.POST, IdentityHttpApi.REGISTRATION_START),
            "step_up_required"
        )
        assertSafeError(
            federatedCall(
                HttpMethod.DELETE,
                "${IdentityHttpApi.PASSKEYS}/${ids.newCredentialId().value}"
            ),
            "step_up_required"
        )
        assertSafeError(
            federatedCall(HttpMethod.POST, IdentityHttpApi.RECOVERY_CODES_REPLACE, "{}"),
            "step_up_required"
        )
        assertTrue(fixture.store.snapshot().challenges.isEmpty())
        assertTrue(fixture.store.snapshot().recoveryCodes.isEmpty())

        val staleFixture = HttpApiFixture.create()
        val staleIssued = IdentitySessionIssuer(staleFixture.runtime.runtime, staleFixture.config).issue(
            user = staleFixture.user,
            assurance = AuthenticationAssurance.PASSKEY,
            authenticationMethod = SessionAuthenticationMethod.PASSKEY,
            authenticatedAt = staleFixture.runtime.deterministicClock.now()
        )
        staleFixture.runtime.deterministicClock.advanceMilliseconds(
            staleFixture.config.lifetimes.recentPasskey.seconds * 1_000 + 1
        )
        val staleContext = IdentityContext(
            principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.USER,
                userId = staleFixture.user.id,
                displayName = staleFixture.user.displayName,
                assurance = AuthenticationAssurance.PASSKEY,
                authenticatedAt = staleIssued.session.authenticatedAt,
                sessionId = staleIssued.session.id
            ),
            session = staleIssued.session
        )
        val staleStart = TestHttpExchange.call(
            method = HttpMethod.POST,
            path = IdentityHttpApi.REGISTRATION_START,
            headers = Headers.of(
                "Origin" to staleFixture.config.publicBaseUrl,
                "X-CSRF-Token" to staleIssued.csrfToken()
            ),
            cookies = Cookies.of(Cookie(staleFixture.config.cookie.name, staleIssued.cookieValue())),
            context = staleContext
        )
        staleFixture.api.asMiddleware()(staleStart) { error("Known route must not fall through") }
        assertSafeError(staleStart, "step_up_required")
        assertTrue(staleFixture.store.snapshot().challenges.isEmpty())
    }

    @Test
    fun `discoverable authentication start returns fixed wire shape and hardened ceremony cookie`() = runTest {
        val fixture = HttpApiFixture.create()
        val exchange = TestHttpExchange.post(IdentityHttpApi.AUTHENTICATION_START)

        fixture.api.asMiddleware()(exchange) { error("Known route must not fall through") }

        assertEquals(200, exchange.response.statusCode)
        val payload = Json.parseToJsonElement(exchange.response.bodyText()).jsonObject
        assertEquals(setOf("ceremonyId", "publicKey"), payload.keys)
        assertTrue(payload.getValue("publicKey").jsonObject.getValue("challenge").jsonPrimitive.content.isNotBlank())
        val cookie = exchange.response.cookies.single { it.name == "__Host-aether_ceremony" }
        assertTrue(cookie.secure)
        assertTrue(cookie.httpOnly)
        assertEquals("/", cookie.path)
        assertEquals(Cookie.SameSite.STRICT, cookie.sameSite)
        assertEquals(300, cookie.maxAge)
        assertEquals("no-store", exchange.response.headers.build()["Cache-Control"])
        assertTrue(exchange.response.headers.build()["X-Request-ID"]!!.startsWith("req_"))
        assertEquals(1, fixture.store.snapshot().challenges.size)
    }

    @Test
    fun `malformed finish with a bounded ceremony envelope consumes the ceremony exactly once`() = runTest {
        val fixture = HttpApiFixture.create()
        val start = TestHttpExchange.post(IdentityHttpApi.AUTHENTICATION_START)
        fixture.api.asMiddleware()(start) { error("Known route must not fall through") }
        val ceremonyId = Json.parseToJsonElement(start.response.bodyText()).jsonObject
            .getValue("ceremonyId").jsonPrimitive.content
        val malformedBody =
            """{"ceremonyId":"$ceremonyId","credential":"not-a-browser-credential"}"""

        suspend fun malformedFinish(): TestHttpExchange {
            val exchange = TestHttpExchange.json(IdentityHttpApi.AUTHENTICATION_FINISH, malformedBody)
            fixture.api.asMiddleware()(exchange) { error("Known route must not fall through") }
            return exchange
        }

        assertSafeError(malformedFinish(), "request_invalid")
        val firstSnapshot = fixture.store.snapshot()
        val failed = firstSnapshot.challenges.single()
        assertEquals(ChallengeState.FAILED, failed.state)
        assertNotNull(failed.consumedAt)
        assertEquals(1, firstSnapshot.auditEvents.count {
            it.action == AuditAction.WEBAUTHN_CEREMONY_REJECTED &&
                it.target?.id == ceremonyId && it.reasonCode == "request_invalid"
        })

        assertSafeError(malformedFinish(), "request_invalid")
        val replaySnapshot = fixture.store.snapshot()
        assertEquals(failed, replaySnapshot.challenges.single())
        assertEquals(1, replaySnapshot.auditEvents.count {
            it.action == AuditAction.WEBAUTHN_CEREMONY_REJECTED && it.target?.id == ceremonyId
        })
    }

    @Test
    fun `malformed registration finish consumes its known ceremony`() = runTest {
        val fixture = HttpApiFixture.create()
        val issued = IdentitySessionIssuer(fixture.runtime.runtime, fixture.config).issue(
            user = fixture.user,
            assurance = AuthenticationAssurance.PASSKEY,
            authenticationMethod = SessionAuthenticationMethod.PASSKEY,
            authenticatedAt = fixture.runtime.deterministicClock.now()
        )
        val context = IdentityContext(
            principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.USER,
                userId = fixture.user.id,
                displayName = fixture.user.displayName,
                assurance = AuthenticationAssurance.PASSKEY,
                authenticatedAt = issued.session.authenticatedAt,
                sessionId = issued.session.id
            ),
            session = issued.session
        )
        val headers = Headers.of(
            "Origin" to fixture.config.publicBaseUrl,
            "X-CSRF-Token" to issued.csrfToken()
        )
        val sessionCookie = Cookie(fixture.config.cookie.name, issued.cookieValue())
        val start = TestHttpExchange.call(
            method = HttpMethod.POST,
            path = IdentityHttpApi.REGISTRATION_START,
            headers = headers,
            cookies = Cookies.of(sessionCookie),
            context = context
        )
        fixture.api.asMiddleware()(start) { error("Known route must not fall through") }
        assertEquals(200, start.response.statusCode)
        val ceremonyId = Json.parseToJsonElement(start.response.bodyText()).jsonObject
            .getValue("ceremonyId").jsonPrimitive.content
        val ceremonyCookie = start.response.cookies.single { it.name == "__Host-aether_ceremony" }
        val finish = TestHttpExchange.call(
            method = HttpMethod.POST,
            path = IdentityHttpApi.REGISTRATION_FINISH,
            body =
                """{"ceremonyId":"$ceremonyId","credentialName":"Security key","credential":false}"""
                    .encodeToByteArray(),
            headers = Headers.of(
                "Content-Type" to "application/json",
                "Origin" to fixture.config.publicBaseUrl,
                "X-CSRF-Token" to issued.csrfToken()
            ),
            cookies = Cookies.of(sessionCookie, ceremonyCookie),
            context = context
        )
        fixture.api.asMiddleware()(finish) { error("Known route must not fall through") }

        assertSafeError(finish, "request_invalid")
        val challenge = fixture.store.snapshot().challenges.single()
        assertEquals(ChallengeState.FAILED, challenge.state)
        assertNotNull(challenge.consumedAt)
    }

    @Test
    fun `oversized JSON and query CSRF fail with generic request-correlated envelopes`() = runTest {
        val fixture = HttpApiFixture.create(http = IdentityHttpApiConfig(maximumJsonBodyBytes = 4_096))
        val oversized = TestHttpExchange.post(
            path = IdentityHttpApi.AUTHENTICATION_FINISH,
            body = ByteArray(4_097) { 'x'.code.toByte() },
            headers = Headers.of(
                "Content-Type" to "application/json",
                "Content-Length" to "4097",
                "X-Request-ID" to "request.safe-123"
            )
        )

        fixture.api.asMiddleware()(oversized) { error("Known route must not fall through") }

        assertSafeError(oversized, "request_invalid", "request.safe-123")
        assertFalse(oversized.response.bodyText().contains("4097"))

        val queryCsrf = TestHttpExchange.post(
            path = IdentityHttpApi.AUTHENTICATION_START,
            query = "csrf_token=must-not-be-accepted"
        )
        fixture.api.asMiddleware()(queryCsrf) { error("Known route must not fall through") }
        assertSafeError(queryCsrf, "csrf_invalid")
        assertFalse(queryCsrf.response.bodyText().contains("must-not-be-accepted"))

        val issued = IdentitySessionIssuer(fixture.runtime.runtime, fixture.config).issue(
            user = fixture.user,
            assurance = AuthenticationAssurance.PASSKEY,
            authenticationMethod = SessionAuthenticationMethod.PASSKEY,
            authenticatedAt = fixture.runtime.deterministicClock.now()
        )
        val missingCookie = TestHttpExchange.post(
            path = IdentityHttpApi.REGISTRATION_START,
            headers = Headers.of(
                "Origin" to fixture.config.publicBaseUrl,
                "X-CSRF-Token" to issued.csrfToken()
            ),
            context = IdentityContext(
                principal = IdentityPrincipal(
                    kind = IdentityPrincipalKind.USER,
                    userId = fixture.user.id,
                    displayName = fixture.user.displayName,
                    assurance = AuthenticationAssurance.PASSKEY,
                    authenticatedAt = issued.session.authenticatedAt,
                    sessionId = issued.session.id
                ),
                session = issued.session
            )
        )
        fixture.api.asMiddleware()(missingCookie) { error("Known route must not fall through") }
        assertSafeError(missingCookie, "csrf_invalid")
    }

    @Test
    fun `RFC 8628 form endpoints expose snake case and pending polling errors`() = runTest {
        val fixture = HttpApiFixture.create()
        val start = TestHttpExchange.form(
            IdentityHttpApi.DEVICE_AUTHORIZATION,
            "client_id=aether-cli&client_name=Aether+CLI&scope=organization.read"
        )
        fixture.api.asMiddleware()(start) { error("Known route must not fall through") }

        assertEquals(200, start.response.statusCode)
        val startPayload = Json.parseToJsonElement(start.response.bodyText()).jsonObject
        assertEquals(
            setOf("device_code", "user_code", "verification_uri", "expires_in", "interval"),
            startPayload.keys
        )
        val deviceCode = startPayload.getValue("device_code").jsonPrimitive.content
        assertEquals(32, Base64Url.decode(deviceCode).size)

        val poll = TestHttpExchange.form(
            IdentityHttpApi.DEVICE_TOKEN,
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code&device_code=$deviceCode&client_id=aether-cli"
        )
        fixture.api.asMiddleware()(poll) { error("Known route must not fall through") }

        assertEquals(400, poll.response.statusCode)
        val pollPayload = Json.parseToJsonElement(poll.response.bodyText()).jsonObject
        assertEquals(setOf("error", "message", "requestId", "retryable"), pollPayload.keys)
        assertEquals("authorization_pending", pollPayload.getValue("error").jsonPrimitive.content)
        assertFalse(pollPayload.getValue("message").jsonPrimitive.content.isBlank())
        assertEquals(
            poll.response.headers.build()[DEFAULT_IDENTITY_REQUEST_ID_HEADER],
            pollPayload.getValue("requestId").jsonPrimitive.content
        )
        assertTrue(pollPayload.getValue("retryable").jsonPrimitive.content.toBoolean())
        assertFalse(poll.response.bodyText().contains(deviceCode))

        val wrongClient = TestHttpExchange.form(
            IdentityHttpApi.DEVICE_TOKEN,
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code&device_code=$deviceCode&client_id=other-client"
        )
        fixture.api.asMiddleware()(wrongClient) { error("Known route must not fall through") }
        assertEquals(400, wrongClient.response.statusCode)
        assertEquals(
            "invalid_grant",
            Json.parseToJsonElement(wrongClient.response.bodyText()).jsonObject.getValue("error").jsonPrimitive.content
        )
        assertEquals(1, fixture.store.snapshot().deviceGrants.single().pollCount)

        val missingClient = TestHttpExchange.form(
            IdentityHttpApi.DEVICE_TOKEN,
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code&device_code=$deviceCode"
        )
        fixture.api.asMiddleware()(missingClient) { error("Known route must not fall through") }
        assertOAuthError(missingClient, "invalid_request")
    }

    @Test
    fun `OAuth device authorization boundary distinguishes invalid request and invalid scope`() = runTest {
        val fixture = HttpApiFixture.create()
        val invalidRequests = listOf(
            "",
            "scope=organization.read",
            "client_id=&scope=organization.read",
            "client_id=aether-cli&client_id=duplicate&scope=organization.read",
            "client_id=aether-cli&client_name=CLI&client_name=duplicate&scope=organization.read",
            "client_id=%ZZ&scope=organization.read"
        )
        invalidRequests.forEach { body ->
            val exchange = TestHttpExchange.form(IdentityHttpApi.DEVICE_AUTHORIZATION, body)
            fixture.api.asMiddleware()(exchange) { error("Known route must not fall through") }
            assertOAuthError(exchange, "invalid_request")
        }

        val invalidScopes = listOf(
            "client_id=aether-cli",
            "client_id=aether-cli&scope=ORGANIZATION.READ",
            "client_id=aether-cli&scope=organization.delete"
        )
        invalidScopes.forEach { body ->
            val exchange = TestHttpExchange.form(IdentityHttpApi.DEVICE_AUTHORIZATION, body)
            fixture.api.asMiddleware()(exchange) { error("Known route must not fall through") }
            assertOAuthError(exchange, "invalid_scope")
        }
        assertTrue(fixture.store.snapshot().deviceGrants.isEmpty())
    }

    @Test
    fun `OAuth token boundary always uses top level protocol errors for invalid requests`() = runTest {
        val fixture = HttpApiFixture.create()
        val deviceGrantType = "urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"
        val invalidBodies = listOf(
            "",
            "grant_type=$deviceGrantType&device_code=opaque-device-code",
            "client_id=aether-cli",
            "grant_type=$deviceGrantType&client_id=aether-cli",
            "grant_type=refresh_token&client_id=aether-cli",
            "grant_type=refresh_token&client_id=aether-cli&client_id=duplicate&refresh_token=opaque-refresh-token",
            "grant_type=refresh_token&grant_type=refresh_token&client_id=aether-cli&refresh_token=opaque-refresh-token",
            "grant_type=%ZZ&client_id=aether-cli"
        )

        invalidBodies.forEach { body ->
            val exchange = TestHttpExchange.form(IdentityHttpApi.DEVICE_TOKEN, body)
            fixture.api.asMiddleware()(exchange) { error("Known route must not fall through") }
            assertOAuthError(exchange, "invalid_request")
            assertFalse(exchange.response.bodyText().contains("opaque-"))
        }

        val wrongContentType = TestHttpExchange.form(
            IdentityHttpApi.DEVICE_TOKEN,
            "grant_type=refresh_token&client_id=aether-cli&refresh_token=opaque-refresh-token",
            headers = Headers.of("Content-Type" to "application/json")
        )
        fixture.api.asMiddleware()(wrongContentType) { error("Known route must not fall through") }
        assertOAuthError(wrongContentType, "invalid_request")
        assertFalse(wrongContentType.response.bodyText().contains("opaque-refresh-token"))

        val unsupported = TestHttpExchange.form(
            IdentityHttpApi.DEVICE_TOKEN,
            "grant_type=authorization_code&client_id=aether-cli&code=opaque-authorization-code"
        )
        fixture.api.asMiddleware()(unsupported) { error("Known route must not fall through") }
        assertOAuthError(unsupported, "unsupported_grant_type")
        assertFalse(unsupported.response.bodyText().contains("opaque-authorization-code"))
    }

    @Test
    fun `audit metadata honors only allowlisted proxy peers and never stores a raw address`() = runTest {
        val fixture = HttpApiFixture.create(
            trustedProxy = TrustedProxyConfig(
                mode = TrustedProxyMode.TRUSTED_CIDRS,
                trustedCidrs = setOf("10.0.0.0/8")
            )
        )
        val forwardedClient = "198.51.100.24"
        val trusted = TestHttpExchange.form(
            path = IdentityHttpApi.DEVICE_AUTHORIZATION,
            body = "client_id=aether-cli&scope=organization.read",
            headers = Headers.of(
                "Content-Type" to "application/x-www-form-urlencoded",
                "X-Forwarded-For" to forwardedClient,
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "identity.example.test"
            ),
            connection = RequestConnection(
                scheme = "http",
                host = "127.0.0.1:8080",
                peerAddress = "10.2.3.4"
            )
        )

        fixture.api.asMiddleware()(trusted) { error("Known route must not fall through") }

        assertEquals(200, trusted.response.statusCode)
        val trustedRequest = assertNotNull(fixture.store.snapshot().auditEvents.single().request)
        assertTrue(trustedRequest.trustedProxy)
        assertNotNull(trustedRequest.clientIpDigest)
        assertFalse(trustedRequest.toString().contains(forwardedClient))

        val untrusted = TestHttpExchange.form(
            path = IdentityHttpApi.DEVICE_AUTHORIZATION,
            body = "client_id=aether-cli&scope=organization.read",
            headers = Headers.of(
                "Content-Type" to "application/x-www-form-urlencoded",
                "X-Forwarded-For" to "203.0.113.99",
                "X-Forwarded-Proto" to "https"
            ),
            connection = RequestConnection(peerAddress = "192.0.2.44")
        )
        fixture.api.asMiddleware()(untrusted) { error("Known route must not fall through") }

        assertEquals(200, untrusted.response.statusCode)
        val untrustedRequest = assertNotNull(fixture.store.snapshot().auditEvents.last().request)
        assertFalse(untrustedRequest.trustedProxy)
        assertTrue(untrustedRequest.clientIpDigest != trustedRequest.clientIpDigest)
        assertFalse(untrustedRequest.toString().contains("203.0.113.99"))
        assertFalse(untrustedRequest.toString().contains("192.0.2.44"))
    }

    @Test
    fun `audit user agent is omitted by default or stored only as a keyed pseudonym`() = runTest {
        val rawUserAgent = "Sensitive Browser/123 exact-device-marker"
        suspend fun authorize(fixture: HttpApiFixture): AuditRequestMetadata {
            val request = TestHttpExchange.form(
                path = IdentityHttpApi.DEVICE_AUTHORIZATION,
                body = "client_id=aether-cli&scope=organization.read",
                headers = Headers.of(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "User-Agent" to rawUserAgent
                )
            )
            fixture.api.asMiddleware()(request) { error("Known route must not fall through") }
            assertEquals(200, request.response.statusCode)
            return assertNotNull(fixture.store.snapshot().auditEvents.single().request)
        }

        assertNull(authorize(HttpApiFixture.create()).userAgent)

        val pseudonymized = authorize(
            HttpApiFixture.create(
                audit = IdentityAuditConfig(userAgentPolicy = AuditUserAgentPolicy.PSEUDONYMIZE)
            )
        ).userAgent
        assertNotNull(pseudonymized)
        assertTrue(pseudonymized.value.startsWith("v1."))
        assertFalse(pseudonymized.value.contains(rawUserAgent))
    }

    @Test
    fun `recovery code creates a restricted cookie session and logout requires its bound CSRF token`() = runTest {
        val fixture = HttpApiFixture.create(recoveryAttemptLimiter = IdentityRecoveryAttemptLimiter { true })
        val generation = fixture.recovery.replaceCodes(fixture.user.id, null).success()
        val rawCode = generation.codes.first().reveal()
        val recovery = TestHttpExchange.json(
            IdentityHttpApi.RECOVERY_CODE_USE,
            """{"code":"$rawCode","deviceLabel":"Recovery browser"}"""
        )

        fixture.api.asMiddleware()(recovery) { error("Known route must not fall through") }

        assertEquals(200, recovery.response.statusCode)
        assertFalse(recovery.response.bodyText().contains(rawCode))
        val created = Json.parseToJsonElement(recovery.response.bodyText()).jsonObject
        assertEquals("recovery", created.getValue("assurance").jsonPrimitive.content)
        val csrf = created.getValue("csrfToken").jsonPrimitive.content
        val sessionCookie = recovery.response.cookies.single { it.name == fixture.config.cookie.name }
        assertTrue(sessionCookie.httpOnly)
        assertTrue(sessionCookie.value.contains('.'))
        val storedSession = fixture.store.snapshot().sessions.single()
        val context = IdentityContext(
            principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.USER,
                userId = fixture.user.id,
                displayName = fixture.user.displayName,
                assurance = AuthenticationAssurance.RECOVERY,
                authenticatedAt = storedSession.authenticatedAt,
                sessionId = storedSession.id
            ),
            session = storedSession
        )
        val logout = TestHttpExchange.post(
            path = IdentityHttpApi.LOGOUT,
            headers = Headers.of(
                "Origin" to fixture.config.publicBaseUrl,
                "X-CSRF-Token" to csrf
            ),
            cookies = Cookies.of(Cookie(fixture.config.cookie.name, sessionCookie.value)),
            context = context
        )

        fixture.api.asMiddleware()(logout) { error("Known route must not fall through") }

        assertEquals(200, logout.response.statusCode)
        assertEquals(0, logout.response.cookies.single { it.name == fixture.config.cookie.name }.maxAge)
        assertEquals(SessionState.REVOKED, fixture.store.snapshot().sessions.single().state)
    }

    @Test
    fun `recovery attempt limiter denies before code verification with a generic redacted error`() = runTest {
        var attempts = 0
        var observedKey = ""
        val fixture = HttpApiFixture.create(
            recoveryAttemptLimiter = IdentityRecoveryAttemptLimiter { attempt ->
                attempts++
                observedKey = attempt.key.toString()
                false
            }
        )
        val rawCode = "not-a-real-recovery-code-secret"
        val recovery = TestHttpExchange.json(
            IdentityHttpApi.RECOVERY_CODE_USE,
            """{"code":"$rawCode"}"""
        )

        fixture.api.asMiddleware()(recovery) { error("Known route must not fall through") }

        assertEquals(1, attempts)
        assertEquals("IdentityRecoveryAttemptKey(<redacted>)", observedKey)
        assertSafeError(recovery, "rate_limited")
        assertFalse(recovery.response.bodyText().contains(rawCode))
        assertTrue(fixture.store.snapshot().sessions.isEmpty())
    }

    @Test
    fun `recovery limiter separates forwarded clients behind a trusted ingress`() = runTest {
        val observedKeys = mutableListOf<IdentityRecoveryAttemptKey>()
        val fixture = HttpApiFixture.create(
            recoveryAttemptLimiter = IdentityRecoveryAttemptLimiter { attempt ->
                observedKeys += attempt.key
                false
            },
            trustedProxy = TrustedProxyConfig(
                mode = TrustedProxyMode.TRUSTED_CIDRS,
                trustedCidrs = setOf("10.0.0.0/8")
            )
        )

        suspend fun attempt(clientAddress: String): TestHttpExchange {
            val exchange = TestHttpExchange.post(
                path = IdentityHttpApi.RECOVERY_CODE_USE,
                body = """{"code":"not-a-real-recovery-code-secret"}""".encodeToByteArray(),
                headers = Headers.of(
                    "Content-Type" to "application/json",
                    "X-Forwarded-For" to clientAddress
                ),
                connection = RequestConnection(peerAddress = "10.2.3.4")
            )
            fixture.api.asMiddleware()(exchange) { error("Known route must not fall through") }
            assertSafeError(exchange, "rate_limited")
            return exchange
        }

        attempt("198.51.100.24")
        attempt("198.51.100.25")
        attempt("198.51.100.24")

        assertEquals(3, observedKeys.size)
        assertTrue(observedKeys[0] != observedKeys[1])
        assertEquals(observedKeys[0], observedKeys[2])
        assertEquals("IdentityRecoveryAttemptKey(<redacted>)", observedKeys[0].toString())
    }

    @Test
    fun `recovery limiter ignores spoofed forwarded clients from an untrusted peer`() = runTest {
        val observedKeys = mutableListOf<IdentityRecoveryAttemptKey>()
        val fixture = HttpApiFixture.create(
            recoveryAttemptLimiter = IdentityRecoveryAttemptLimiter { attempt ->
                observedKeys += attempt.key
                false
            },
            trustedProxy = TrustedProxyConfig(
                mode = TrustedProxyMode.TRUSTED_CIDRS,
                trustedCidrs = setOf("10.0.0.0/8")
            )
        )

        suspend fun attempt(forwardedClient: String, peerAddress: String) {
            val exchange = TestHttpExchange.post(
                path = IdentityHttpApi.RECOVERY_CODE_USE,
                body = """{"code":"not-a-real-recovery-code-secret"}""".encodeToByteArray(),
                headers = Headers.of(
                    "Content-Type" to "application/json",
                    "X-Forwarded-For" to forwardedClient
                ),
                connection = RequestConnection(peerAddress = peerAddress)
            )
            fixture.api.asMiddleware()(exchange) { error("Known route must not fall through") }
            assertSafeError(exchange, "rate_limited")
        }

        attempt("198.51.100.24", "192.0.2.44")
        attempt("203.0.113.99", "192.0.2.44")
        attempt("203.0.113.99", "192.0.2.45")

        assertEquals(3, observedKeys.size)
        assertEquals(observedKeys[0], observedKeys[1])
        assertTrue(observedKeys[0] != observedKeys[2])
    }

    @Test
    fun `production HTTP authority requires an explicit recovery attempt limiter`() = runTest {
        val config = productionHttpIdentityConfig()
        val runtime = DeterministicIdentityRuntime()
        config.secretReferences().forEachIndexed { index, reference ->
            runtime.deterministicSecrets.register(reference, ByteArray(32) { (index + it + 1).toByte() })
        }
        val store = InMemoryIdentityStore()
        val recovery = IdentityRecoveryService(store, runtime.runtime, config)
        val accounts = IdentityAccountManagementService(store, runtime.runtime)
        val device = IdentityDeviceAuthorizationService(
            store,
            runtime.runtime,
            config,
            allowedCapabilities = setOf(Capability.ORGANIZATION_READ)
        )

        assertFailsWith<IllegalArgumentException> {
            IdentityHttpApi(
                runtime.runtime,
                config,
                WebAuthnService(store, runtime.runtime, config),
                recovery,
                accounts,
                device
            )
        }
        assertNotNull(
            IdentityHttpApi(
                runtime.runtime,
                config,
                WebAuthnService(store, runtime.runtime, config),
                recovery,
                accounts,
                device,
                recoveryAttemptLimiter = IdentityRecoveryAttemptLimiter { true }
            )
        )
    }

    @Test
    fun `production authority accepts only the configured direct or trusted-proxy origin`() = runTest {
        val config = productionHttpIdentityConfig(
            trustedProxy = TrustedProxyConfig(
                mode = TrustedProxyMode.TRUSTED_CIDRS,
                trustedCidrs = setOf("10.0.0.0/8")
            )
        )
        val api = productionHttpApi(config)

        val direct = TestHttpExchange.post(
            IdentityHttpApi.AUTHENTICATION_START,
            connection = RequestConnection(
                scheme = "https",
                host = "identity.example.test",
                peerAddress = "192.0.2.10"
            )
        )
        api.asMiddleware()(direct) { error("Known route must not fall through") }
        assertEquals(200, direct.response.statusCode)

        val spoofed = TestHttpExchange.post(
            IdentityHttpApi.AUTHENTICATION_START,
            headers = Headers.of(
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "identity.example.test"
            ),
            connection = RequestConnection(
                scheme = "http",
                host = "internal.service",
                peerAddress = "192.0.2.11"
            )
        )
        api.asMiddleware()(spoofed) { error("Known route must not fall through") }
        assertEquals(400, spoofed.response.statusCode)
        assertSafeError(spoofed, "request_invalid")
        assertFalse(spoofed.response.bodyText().contains("internal.service"))

        val trusted = TestHttpExchange.post(
            IdentityHttpApi.AUTHENTICATION_START,
            headers = Headers.of(
                "X-Forwarded-Proto" to "https",
                "X-Forwarded-Host" to "identity.example.test"
            ),
            connection = RequestConnection(
                scheme = "http",
                host = "internal.service",
                peerAddress = "10.0.0.12"
            )
        )
        api.asMiddleware()(trusted) { error("Known route must not fall through") }
        assertEquals(200, trusted.response.statusCode)
    }

    @Test
    fun `unknown routes remain composable and fall through unchanged`() = runTest {
        val fixture = HttpApiFixture.create()
        val exchange = TestHttpExchange.post("/application/route")
        var continued = false

        fixture.api.asMiddleware()(exchange) { continued = true }

        assertTrue(continued)
        assertEquals(200, exchange.response.statusCode)
        assertTrue(exchange.response.bodyText().isEmpty())
    }

    @Test
    fun `passkey organization invitation and service identity management use scoped safe projections`() = runTest {
        val fixture = ManagementHttpFixture.create()

        val passkeys = fixture.call(HttpMethod.GET, IdentityHttpApi.PASSKEYS)
        fixture.execute(passkeys)
        assertEquals(200, passkeys.response.statusCode)
        assertFalse(passkeys.response.bodyText().contains("publicKey"))

        val rename = fixture.json(
            HttpMethod.PATCH,
            "${IdentityHttpApi.PASSKEYS}/${fixture.credential.id.value}",
            """{"name":"Travel key"}"""
        )
        fixture.execute(rename)
        assertEquals("Travel key", Json.parseToJsonElement(rename.response.bodyText()).jsonObject["name"]?.jsonPrimitive?.content)

        val revokePasskey = fixture.call(
            HttpMethod.DELETE,
            "${IdentityHttpApi.PASSKEYS}/${fixture.credential.id.value}"
        )
        fixture.execute(revokePasskey)
        assertEquals("revoked", Json.parseToJsonElement(revokePasskey.response.bodyText()).jsonObject["state"]?.jsonPrimitive?.content)

        val organizations = fixture.call(HttpMethod.GET, IdentityHttpApi.ORGANIZATIONS)
        fixture.execute(organizations)
        assertTrue(organizations.response.bodyText().contains(fixture.organization.id.value))
        val organizationPayload = Json.parseToJsonElement(organizations.response.bodyText()).jsonArray.single().jsonObject
        assertEquals("owner", organizationPayload.getValue("role").jsonPrimitive.content)

        val selected = fixture.call(
            HttpMethod.GET,
            "${IdentityHttpApi.ORGANIZATIONS}/${fixture.organization.id.value}"
        )
        fixture.execute(selected)
        assertEquals("owner", Json.parseToJsonElement(selected.response.bodyText()).jsonObject["role"]?.jsonPrimitive?.content)

        val me = fixture.call(HttpMethod.GET, IdentityHttpApi.ME)
        fixture.execute(me)
        val mePayload = Json.parseToJsonElement(me.response.bodyText()).jsonObject
        assertEquals(fixture.user.id.value, mePayload.getValue("userId").jsonPrimitive.content)
        assertEquals("passkey", mePayload.getValue("assuranceLevel").jsonPrimitive.content)

        val recoveryCodes = fixture.json(
            HttpMethod.POST,
            IdentityHttpApi.RECOVERY_CODES_REPLACE,
            "{}"
        )
        fixture.execute(recoveryCodes)
        assertEquals(10, Json.parseToJsonElement(recoveryCodes.response.bodyText()).jsonObject
            .getValue("codes").jsonArray.size)

        val invitation = fixture.json(
            HttpMethod.POST,
            "/identity/v1/organizations/${fixture.organization.id.value}/invitations",
            """{"email":"invitee@example.test","role":"viewer"}"""
        )
        fixture.execute(invitation)
        assertEquals(201, invitation.response.statusCode)
        val invitationPayload = Json.parseToJsonElement(invitation.response.bodyText()).jsonObject
        val invitationToken = invitationPayload.getValue("token").jsonPrimitive.content
        assertEquals(32, Base64Url.decode(invitationToken).size)

        val enrollment = TestHttpExchange.json(
            IdentityHttpApi.INVITATION_ENROLL,
            """{"token":"$invitationToken","displayName":"Invited User"}"""
        )
        fixture.execute(enrollment)
        assertEquals(201, enrollment.response.statusCode)
        val enrollmentPayload = Json.parseToJsonElement(enrollment.response.bodyText()).jsonObject
        val enrollmentCookie = enrollment.response.cookies.single { it.name == fixture.config.cookie.name }
        assertTrue(enrollmentCookie.httpOnly)
        assertFalse(enrollment.response.bodyText().contains(invitationToken))
        assertFalse(enrollment.response.bodyText().contains(enrollmentCookie.value))
        val enrolledSession = fixture.store.snapshot().sessions.single {
            it.id.value == enrollmentPayload.getValue("sessionId").jsonPrimitive.content
        }
        assertEquals(SessionAuthenticationMethod.INVITATION, enrolledSession.authenticationMethod)
        assertEquals(AuthenticationAssurance.RECOVERY, enrolledSession.assurance)

        val enrollmentContext = IdentityContext(
            principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.USER,
                userId = enrolledSession.userId,
                displayName = "Invited User",
                assurance = AuthenticationAssurance.RECOVERY,
                authenticatedAt = enrolledSession.authenticatedAt,
                sessionId = enrolledSession.id
            ),
            session = enrolledSession
        )
        val registrationStart = TestHttpExchange.call(
            method = HttpMethod.POST,
            path = IdentityHttpApi.REGISTRATION_START,
            headers = Headers.of(
                "Origin" to fixture.config.publicBaseUrl,
                "X-CSRF-Token" to enrollmentPayload.getValue("csrfToken").jsonPrimitive.content
            ),
            cookies = Cookies.of(Cookie(fixture.config.cookie.name, enrollmentCookie.value)),
            context = enrollmentContext
        )
        fixture.execute(registrationStart)
        assertEquals(200, registrationStart.response.statusCode)
        assertTrue(registrationStart.response.cookies.any { it.name == "__Host-aether_ceremony" })

        val service = fixture.json(
            HttpMethod.POST,
            "/identity/v1/organizations/${fixture.organization.id.value}/service-identities",
            """{"name":"Publisher bot","capabilities":["content.publish"]}"""
        )
        fixture.execute(service)
        assertEquals(201, service.response.statusCode)
        val servicePayload = Json.parseToJsonElement(service.response.bodyText()).jsonObject
        val token = servicePayload.getValue("token").jsonPrimitive.content
        assertTrue(token.contains('.'))
        assertFalse(fixture.store.snapshot().toString().contains(token))

        val deleteOrganization = fixture.call(
            HttpMethod.DELETE,
            "${IdentityHttpApi.ORGANIZATIONS}/${fixture.organization.id.value}"
        )
        fixture.execute(deleteOrganization)
        assertEquals("deleted", Json.parseToJsonElement(deleteOrganization.response.bodyText())
            .jsonObject["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun `organization audit route is isolated stable strictly parsed and redacted`() = runTest {
        val fixture = ManagementHttpFixture.create()
        val path = "${IdentityHttpApi.ORGANIZATIONS}/${fixture.organization.id.value}/audit-events"
        val sensitiveMarker = "audit-user-agent-and-client-digest-must-not-leak"
        val event3000 = IdentityFixtures.auditEventId(10)
        val event2000b = IdentityFixtures.auditEventId(12)
        val event2000a = IdentityFixtures.auditEventId(11)
        val event1000 = IdentityFixtures.auditEventId(9)
        val event4000 = IdentityFixtures.auditEventId(13)
        val otherOrganization = IdentityFixtures.organizationId(20)
        suspend fun append(id: AuditEventId, organizationId: OrganizationId, offset: Long) {
            assertIs<StoreResult.Success<AuditEvent>>(
                fixture.store.appendAuditEvent(
                    IdentityFixtures.auditEvent(
                        id,
                        AuditAction.ORGANIZATION_CHANGED,
                        organizationId.value
                    ).copy(
                        organizationId = organizationId,
                        target = AuditTarget(AuditTargetType.ORGANIZATION, organizationId.value),
                        request = AuditRequestMetadata(
                            requestId = "request-${id.value}",
                            method = "PATCH",
                            path = "/identity/v1/organizations/${organizationId.value}",
                            userAgent = PseudonymousValue(sensitiveMarker),
                            clientIpDigest = PseudonymousValue(sensitiveMarker)
                        ),
                        occurredAt = IdentityFixtures.instant(offset)
                    )
                )
            )
        }
        append(event3000, fixture.organization.id, 3_000)
        append(event2000b, fixture.organization.id, 2_000)
        append(event2000a, fixture.organization.id, 2_000)
        append(event1000, fixture.organization.id, 1_000)
        append(IdentityFixtures.auditEventId(21), otherOrganization, 4_000)

        val first = fixture.call(HttpMethod.GET, path, query = "limit=2")
        fixture.execute(first)
        assertEquals(200, first.response.statusCode)
        val firstPayload = Json.parseToJsonElement(first.response.bodyText()).jsonObject
        assertEquals(setOf("events", "nextCursor"), firstPayload.keys)
        assertEquals(
            listOf(event3000.value, event2000b.value),
            firstPayload.getValue("events").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        )
        assertFalse(first.response.bodyText().contains(sensitiveMarker))
        assertFalse(first.response.bodyText().contains("clientIpDigest"))
        assertFalse(first.response.bodyText().contains("userAgent"))
        val cursor = firstPayload.getValue("nextCursor").jsonPrimitive.content
        assertNotNull(OrganizationAuditEventCursor.fromOpaqueToken(cursor))

        append(event4000, fixture.organization.id, 4_000)
        val second = fixture.call(HttpMethod.GET, path, query = "cursor=$cursor&limit=2")
        fixture.execute(second)
        assertEquals(
            listOf(event2000a.value, event1000.value),
            Json.parseToJsonElement(second.response.bodyText()).jsonObject.getValue("events").jsonArray
                .map { it.jsonObject.getValue("id").jsonPrimitive.content }
        )

        listOf(
            "cursor=%",
            "limit=0",
            "limit=101",
            "limit=01",
            "limit=1&limit=2",
            "unknown=1",
            "limit"
        ).forEach { malformed ->
            val exchange = fixture.call(HttpMethod.GET, path, query = malformed)
            fixture.execute(exchange)
            assertSafeError(exchange, "request_invalid")
        }

        val viewer = TestHttpExchange.call(
            method = HttpMethod.GET,
            path = path,
            context = fixture.context.copy(
                membership = IdentityFixtures.membership(
                    organizationId = fixture.organization.id,
                    userId = fixture.user.id,
                    role = OrganizationRole.VIEWER
                )
            )
        )
        fixture.execute(viewer)
        assertSafeError(viewer, "not_found")

        val wrongTenant = fixture.call(
            HttpMethod.GET,
            "${IdentityHttpApi.ORGANIZATIONS}/${otherOrganization.value}/audit-events"
        )
        fixture.execute(wrongTenant)
        assertSafeError(wrongTenant, "not_found")
    }

    @Test
    fun `device verification approval and administrative recovery are wired without leaking tickets`() = runTest {
        val fixture = ManagementHttpFixture.create()
        val start = TestHttpExchange.form(
            IdentityHttpApi.DEVICE_AUTHORIZATION,
            "client_id=aether-cli&client_name=Aether+CLI&scope=organization.read"
        )
        fixture.execute(start)
        val started = Json.parseToJsonElement(start.response.bodyText()).jsonObject
        val userCode = started.getValue("user_code").jsonPrimitive.content

        val inspect = fixture.json(
            HttpMethod.POST,
            IdentityHttpApi.DEVICE_VERIFICATION,
            """{"userCode":"$userCode"}"""
        )
        fixture.execute(inspect)
        assertEquals(200, inspect.response.statusCode)
        assertEquals("Aether CLI", Json.parseToJsonElement(inspect.response.bodyText()).jsonObject["clientName"]?.jsonPrimitive?.content)

        var legacyQueryFellThrough = false
        val legacyQuery = fixture.call(
            HttpMethod.GET,
            IdentityHttpApi.DEVICE_VERIFICATION,
            query = "user_code=$userCode"
        )
        fixture.api.asMiddleware()(legacyQuery) { legacyQueryFellThrough = true }
        assertTrue(legacyQueryFellThrough)

        val oversizedInspection = fixture.json(
            HttpMethod.POST,
            IdentityHttpApi.DEVICE_VERIFICATION,
            """{"userCode":"${"A".repeat(300)}"}"""
        )
        fixture.execute(oversizedInspection)
        assertSafeError(oversizedInspection, "request_invalid")

        val approve = fixture.json(
            HttpMethod.POST,
            IdentityHttpApi.DEVICE_APPROVE,
            """{"userCode":"$userCode","organizationId":"${fixture.organization.id.value}","capabilities":["organization.read"]}"""
        )
        fixture.execute(approve)
        assertEquals(200, approve.response.statusCode)
        assertEquals(DeviceGrantState.AUTHORIZED, fixture.store.snapshot().deviceGrants.single().state)

        val deviceCode = started.getValue("device_code").jsonPrimitive.content
        val exchange = TestHttpExchange.form(
            IdentityHttpApi.DEVICE_TOKEN,
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code&device_code=$deviceCode&client_id=aether-cli"
        )
        fixture.execute(exchange)
        val refreshToken = Json.parseToJsonElement(exchange.response.bodyText()).jsonObject
            .getValue("refresh_token").jsonPrimitive.content
        val revoke = TestHttpExchange.form(
            IdentityHttpApi.DEVICE_TOKEN_REVOKE,
            "token=$refreshToken&token_type_hint=refresh_token&client_id=aether-cli"
        )
        fixture.execute(revoke)
        assertEquals(200, revoke.response.statusCode)
        assertTrue(revoke.response.bodyText().isEmpty())
        assertEquals(DeviceTokenFamilyState.REVOKED, fixture.store.snapshot().deviceTokenFamilies.single().state)
        val unknownRevoke = TestHttpExchange.form(
            IdentityHttpApi.DEVICE_TOKEN_REVOKE,
            "token=unknown-token&token_type_hint=refresh_token&client_id=aether-cli"
        )
        fixture.execute(unknownRevoke)
        assertEquals(200, unknownRevoke.response.statusCode)

        val issue = fixture.json(
            HttpMethod.POST,
            IdentityHttpApi.ADMIN_RECOVERY_ISSUE,
            """{"userId":"${fixture.user.id.value}"}"""
        )
        fixture.execute(issue)
        assertEquals(201, issue.response.statusCode)
        val deliveredToken = assertNotNull(fixture.ticketCapture.token)
        assertFalse(issue.response.bodyText().contains(deliveredToken))

        val redeem = TestHttpExchange.json(
            IdentityHttpApi.ADMIN_RECOVERY_REDEEM,
            """{"token":"$deliveredToken"}"""
        )
        fixture.execute(redeem)
        assertEquals(200, redeem.response.statusCode)
        assertEquals("recovery", Json.parseToJsonElement(redeem.response.bodyText()).jsonObject["assurance"]?.jsonPrimitive?.content)
        assertTrue(redeem.response.cookies.any { it.name == fixture.config.cookie.name && it.httpOnly })
    }

    @Test
    fun `recovery sessions are restricted to passkey enrollment routes`() = runTest {
        val fixture = HttpApiFixture.create()
        val generation = fixture.recovery.replaceCodes(fixture.user.id, null).success()
        val recovery = TestHttpExchange.json(
            IdentityHttpApi.RECOVERY_CODE_USE,
            """{"code":"${generation.codes.first().reveal()}"}"""
        )
        fixture.api.asMiddleware()(recovery) { error("Known route must not fall through") }
        val stored = fixture.store.snapshot().sessions.single()
        val context = IdentityContext(
            IdentityPrincipal(
                IdentityPrincipalKind.USER,
                userId = fixture.user.id,
                displayName = fixture.user.displayName,
                assurance = AuthenticationAssurance.RECOVERY,
                authenticatedAt = stored.authenticatedAt,
                sessionId = stored.id
            ),
            stored
        )
        val blocked = TestHttpExchange.call(
            method = HttpMethod.GET,
            path = IdentityHttpApi.PASSKEYS,
            context = context
        )
        fixture.api.asMiddleware()(blocked) { error("Known route must not fall through") }
        assertSafeError(blocked, "authentication_required")
    }

    @Test
    fun `bootstrap consumes its secret without returning it`() = runTest {
        val fixture = BootstrapHttpFixture.create()
        val request = TestHttpExchange.json(
            IdentityHttpApi.BOOTSTRAP,
            """{"secret":"${fixture.secret}","displayName":"First Owner","primaryEmail":"owner@example.test","organizationName":"Aether","organizationSlug":"aether"}"""
        )

        fixture.api.asMiddleware()(request) { error("Known route must not fall through") }

        assertEquals(201, request.response.statusCode)
        assertFalse(request.response.bodyText().contains(fixture.secret))
        val snapshot = fixture.store.snapshot()
        assertTrue(snapshot.bootstrapCompleted)
        assertEquals(OrganizationRole.OWNER, snapshot.memberships.single().role)
        val payload = Json.parseToJsonElement(request.response.bodyText()).jsonObject
        val sessionCookie = request.response.cookies.single { it.name == fixture.config.cookie.name }
        assertFalse(request.response.bodyText().contains(sessionCookie.value))
        val session = snapshot.sessions.single()
        val context = IdentityContext(
            IdentityPrincipal(
                kind = IdentityPrincipalKind.USER,
                userId = snapshot.users.single().id,
                displayName = snapshot.users.single().displayName,
                assurance = AuthenticationAssurance.RECOVERY,
                authenticatedAt = session.authenticatedAt,
                sessionId = session.id
            ),
            session
        )
        val enroll = TestHttpExchange.call(
            method = HttpMethod.POST,
            path = IdentityHttpApi.REGISTRATION_START,
            headers = Headers.of(
                "Origin" to fixture.config.publicBaseUrl,
                "X-CSRF-Token" to payload.getValue("csrfToken").jsonPrimitive.content
            ),
            cookies = Cookies.of(Cookie(fixture.config.cookie.name, sessionCookie.value)),
            context = context
        )
        fixture.api.asMiddleware()(enroll) { error("Known route must not fall through") }
        assertEquals(200, enroll.response.statusCode)
        assertTrue(enroll.response.cookies.any { it.name == "__Host-aether_ceremony" })
    }
}

private class HttpApiFixture private constructor(
    val config: IdentityConfig,
    val runtime: DeterministicIdentityRuntime,
    val store: InMemoryIdentityStore,
    val user: User,
    val recovery: IdentityRecoveryService,
    val api: IdentityHttpApi
) {
    companion object {
        suspend fun create(
            http: IdentityHttpApiConfig = IdentityHttpApiConfig(),
            recoveryAttemptLimiter: IdentityRecoveryAttemptLimiter? = null,
            trustedProxy: TrustedProxyConfig = TrustedProxyConfig(),
            registrationPolicy: RegistrationPolicy = RegistrationPolicy.INVITATION_ONLY,
            audit: IdentityAuditConfig = IdentityAuditConfig()
        ): HttpApiFixture {
            val config = httpIdentityConfig(
                trustedProxy = trustedProxy,
                registrationPolicy = registrationPolicy,
                audit = audit
            )
            val runtime = DeterministicIdentityRuntime()
            config.secretReferences().forEachIndexed { index, reference ->
                runtime.deterministicSecrets.register(reference, ByteArray(32) { (index + it + 1).toByte() })
            }
            val user = IdentityFixtures.user(IdentityIdFactory(runtime.runtime.clock, runtime.runtime.secureRandom).newUserId())
            val store = InMemoryIdentityStore(InMemoryIdentityStoreSeed(users = listOf(user)))
            val webAuthn = WebAuthnService(store, runtime.runtime, config)
            val recovery = IdentityRecoveryService(store, runtime.runtime, config)
            val accounts = IdentityAccountManagementService(store, runtime.runtime)
            val device = IdentityDeviceAuthorizationService(
                store,
                runtime.runtime,
                config,
                allowedCapabilities = setOf(Capability.ORGANIZATION_READ, Capability.AUDIT_READ)
            )
            return HttpApiFixture(
                config,
                runtime,
                store,
                user,
                recovery,
                IdentityHttpApi(
                    runtime.runtime,
                    config,
                    webAuthn,
                    recovery,
                    accounts,
                    device,
                    http,
                    recoveryAttemptLimiter = recoveryAttemptLimiter
                )
            )
        }
    }
}

private class RecoveryTicketCapture {
    var token: String? = null
    val sink = AdministrativeRecoveryNotificationSink { delivery ->
        token = delivery.revealToken()
        RecoveryNotificationOutcome(delivered = true)
    }
}

private class ManagementHttpFixture private constructor(
    val config: IdentityConfig,
    val store: InMemoryIdentityStore,
    val user: User,
    val organization: Organization,
    val credential: Credential,
    val context: IdentityContext,
    val sessionCookie: String,
    val csrfToken: String,
    val ticketCapture: RecoveryTicketCapture,
    val api: IdentityHttpApi
) {
    fun call(method: HttpMethod, path: String, query: String? = null): TestHttpExchange =
        TestHttpExchange.call(
            method = method,
            path = path,
            query = query,
            headers = authenticatedHeaders(),
            cookies = Cookies.of(Cookie(config.cookie.name, sessionCookie)),
            context = context
        )

    fun json(method: HttpMethod, path: String, body: String): TestHttpExchange =
        TestHttpExchange.call(
            method = method,
            path = path,
            body = body.encodeToByteArray(),
            headers = Headers.of(
                "Content-Type" to "application/json",
                "Origin" to config.publicBaseUrl,
                "X-CSRF-Token" to csrfToken
            ),
            cookies = Cookies.of(Cookie(config.cookie.name, sessionCookie)),
            context = context
        )

    suspend fun execute(exchange: TestHttpExchange) {
        api.asMiddleware()(exchange) { error("Known route must not fall through") }
    }

    private fun authenticatedHeaders(): Headers = Headers.of(
        "Origin" to config.publicBaseUrl,
        "X-CSRF-Token" to csrfToken
    )

    companion object {
        suspend fun create(): ManagementHttpFixture {
            val config = httpIdentityConfig()
            val runtime = DeterministicIdentityRuntime()
            config.secretReferences().forEachIndexed { index, reference ->
                runtime.deterministicSecrets.register(reference, ByteArray(32) { (index + it + 1).toByte() })
            }
            val ids = IdentityIdFactory(runtime.runtime.clock, runtime.runtime.secureRandom)
            val user = IdentityFixtures.user(ids.newUserId())
            val organization = IdentityFixtures.organization(ids.newOrganizationId())
            val membership = IdentityFixtures.membership(
                id = ids.newMembershipId(),
                userId = user.id,
                organizationId = organization.id
            )
            val credential = IdentityFixtures.credential(id = ids.newCredentialId(), userId = user.id)
            val issued = IdentitySessionIssuer(runtime.runtime, config).issue(
                user = user,
                assurance = AuthenticationAssurance.PASSKEY,
                authenticationMethod = SessionAuthenticationMethod.PASSKEY,
                authenticatedAt = runtime.deterministicClock.now()
            )
            val store = InMemoryIdentityStore(
                InMemoryIdentityStoreSeed(
                    users = listOf(user),
                    credentials = listOf(credential),
                    sessions = listOf(issued.session),
                    organizations = listOf(organization),
                    memberships = listOf(membership)
                )
            )
            val principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.USER,
                userId = user.id,
                displayName = user.displayName,
                assurance = AuthenticationAssurance.PASSKEY,
                authenticatedAt = issued.session.authenticatedAt,
                sessionId = issued.session.id
            )
            val context = IdentityContext(principal, issued.session, organization, membership)
            val organizations = IdentityOrganizationService(store, runtime.runtime, config)
            val serviceIdentities = IdentityServiceIdentityService(
                store,
                runtime.runtime,
                config,
                allowedCapabilities = setOf(Capability.CONTENT_PUBLISH),
                capabilityResolver = CapabilityResolver { scoped ->
                    if (scoped.organization?.id == organization.id) setOf(Capability.CONTENT_PUBLISH) else emptySet()
                }
            )
            val ticketCapture = RecoveryTicketCapture()
            val administrativeRecovery = IdentityAdministrativeRecoveryService(
                store,
                runtime.runtime,
                config,
                AdministrativeRecoveryAuthorizer { _, _ -> true },
                ticketCapture.sink
            )
            val webAuthn = WebAuthnService(store, runtime.runtime, config)
            val recovery = IdentityRecoveryService(store, runtime.runtime, config)
            val accounts = IdentityAccountManagementService(store, runtime.runtime)
            val device = IdentityDeviceAuthorizationService(
                store,
                runtime.runtime,
                config,
                allowedCapabilities = setOf(Capability.ORGANIZATION_READ, Capability.AUDIT_READ)
            )
            val api = IdentityHttpApi(
                runtime.runtime,
                config,
                webAuthn,
                recovery,
                accounts,
                device,
                management = IdentityHttpManagementServices(
                    organizations = organizations,
                    serviceIdentities = serviceIdentities,
                    administrativeRecovery = administrativeRecovery
                )
            )
            return ManagementHttpFixture(
                config,
                store,
                user,
                organization,
                credential,
                context,
                issued.cookieValue(),
                issued.csrfToken(),
                ticketCapture,
                api
            )
        }
    }
}

private class BootstrapHttpFixture private constructor(
    val config: IdentityConfig,
    val secret: String,
    val store: InMemoryIdentityStore,
    val api: IdentityHttpApi
) {
    companion object {
        suspend fun create(): BootstrapHttpFixture {
            val reference = SecretReference("test", "bootstrap", "v1", IdentityEnvironment.TEST)
            val secret = "bootstrap-secret-material-123456"
            val config = httpIdentityConfig(reference)
            val runtime = DeterministicIdentityRuntime()
            config.secretReferences().forEachIndexed { index, item ->
                val value = if (item == reference) secret.encodeToByteArray() else ByteArray(32) { (index + it + 1).toByte() }
                runtime.deterministicSecrets.register(item, value)
            }
            val store = InMemoryIdentityStore()
            val webAuthn = WebAuthnService(store, runtime.runtime, config)
            val recovery = IdentityRecoveryService(store, runtime.runtime, config)
            val accounts = IdentityAccountManagementService(store, runtime.runtime)
            val device = IdentityDeviceAuthorizationService(
                store,
                runtime.runtime,
                config,
                allowedCapabilities = setOf(Capability.ORGANIZATION_READ)
            )
            val bootstrap = IdentityBootstrapService(store, runtime.runtime, config)
            return BootstrapHttpFixture(
                config,
                secret,
                store,
                IdentityHttpApi(
                    runtime.runtime,
                    config,
                    webAuthn,
                    recovery,
                    accounts,
                    device,
                    management = IdentityHttpManagementServices(bootstrap = bootstrap)
                )
            )
        }
    }
}

private class TestHttpRequest(
    override val method: HttpMethod,
    override val path: String,
    override val query: String?,
    override val headers: Headers,
    override val cookies: Cookies,
    private val body: ByteArray,
    override val connection: RequestConnection = RequestConnection()
) : Request {
    override val uri: String = if (query == null) path else "$path?$query"
    override suspend fun bodyBytes(): ByteArray = body.copyOf()
}

private class TestHttpResponse : Response {
    override var statusCode: Int = 200
    override var statusMessage: String? = null
    override val headers = Headers.HeadersBuilder()
    override val cookies = mutableListOf<Cookie>()
    private val body = mutableListOf<Byte>()
    override suspend fun write(data: ByteArray) { body.addAll(data.toList()) }
    override suspend fun end() = Unit
    fun bodyText(): String = body.toByteArray().decodeToString()
}

private class TestHttpExchange private constructor(
    override val request: Request,
    context: IdentityContext
) : Exchange {
    override val response = TestHttpResponse()
    override val attributes = Attributes().also { it.put(IdentityContextAttributeKey, context) }

    companion object {
        fun call(
            method: HttpMethod,
            path: String,
            query: String? = null,
            body: ByteArray = ByteArray(0),
            headers: Headers = Headers.Empty,
            cookies: Cookies = Cookies.Empty,
            context: IdentityContext = IdentityContext.Anonymous,
            connection: RequestConnection = RequestConnection()
        ) = TestHttpExchange(TestHttpRequest(method, path, query, headers, cookies, body, connection), context)

        fun post(
            path: String,
            query: String? = null,
            body: ByteArray = ByteArray(0),
            headers: Headers = Headers.Empty,
            cookies: Cookies = Cookies.Empty,
            context: IdentityContext = IdentityContext.Anonymous,
            connection: RequestConnection = RequestConnection()
        ) = call(HttpMethod.POST, path, query, body, headers, cookies, context, connection)

        fun json(path: String, body: String) = post(
            path,
            body = body.encodeToByteArray(),
            headers = Headers.of("Content-Type" to "application/json")
        )

        fun form(
            path: String,
            body: String,
            headers: Headers = Headers.of("Content-Type" to "application/x-www-form-urlencoded"),
            connection: RequestConnection = RequestConnection()
        ) = post(
            path,
            body = body.encodeToByteArray(),
            headers = headers,
            connection = connection
        )
    }
}

private fun assertSafeError(exchange: TestHttpExchange, code: String, requestId: String? = null) {
    val payload = Json.parseToJsonElement(exchange.response.bodyText()).jsonObject
    assertEquals(setOf("error"), payload.keys)
    val error = payload.getValue("error").jsonObject
    assertEquals(setOf("code", "message", "requestId", "retryable"), error.keys)
    assertEquals(code, error.getValue("code").jsonPrimitive.content)
    assertFalse(error.getValue("message").jsonPrimitive.content.isBlank())
    assertEquals(requestId ?: exchange.response.headers.build()["X-Request-ID"], error.getValue("requestId").jsonPrimitive.content)
    assertNotNull(error["retryable"])
}

private fun assertOAuthError(exchange: TestHttpExchange, code: String) {
    assertEquals(400, exchange.response.statusCode)
    val payload = Json.parseToJsonElement(exchange.response.bodyText()).jsonObject
    assertEquals(setOf("error", "message", "requestId", "retryable"), payload.keys)
    assertEquals(code, payload.getValue("error").jsonPrimitive.content)
    assertFalse(payload.getValue("message").jsonPrimitive.content.isBlank())
    assertEquals(
        exchange.response.headers.build()[DEFAULT_IDENTITY_REQUEST_ID_HEADER],
        payload.getValue("requestId").jsonPrimitive.content
    )
    assertNotNull(payload["retryable"])
    assertEquals("no-store", exchange.response.headers.build()["Cache-Control"])
    assertEquals("no-cache", exchange.response.headers.build()["Pragma"])
}

private fun httpIdentityConfig(
    bootstrapSecret: SecretReference? = null,
    trustedProxy: TrustedProxyConfig = TrustedProxyConfig(),
    registrationPolicy: RegistrationPolicy = RegistrationPolicy.INVITATION_ONLY,
    audit: IdentityAuditConfig = IdentityAuditConfig()
): IdentityConfig {
    val environment = if (registrationPolicy == RegistrationPolicy.OPEN) {
        IdentityEnvironment.DEVELOPMENT
    } else {
        IdentityEnvironment.TEST
    }
    fun secret(name: String) = SecretReference("test", name, "v1", environment)
    return IdentityConfig(
        environment = environment,
        publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig("localhost", "Aether HTTP Test", setOf("http://localhost:8080")),
        keys = IdentityKeyConfig(
            sessionPepper = secret("session"),
            recoveryPepper = secret("recovery"),
            deviceTokenPepper = secret("device"),
            serviceCredentialPepper = secret("service"),
            auditPseudonymizationKey = secret("audit"),
            encryptionKey = secret("encryption"),
            signingKey = secret("signing")
        ),
        trustedProxy = trustedProxy,
        registrationPolicy = registrationPolicy,
        audit = audit,
        bootstrapSecret = bootstrapSecret
    )
}

private fun productionHttpIdentityConfig(
    trustedProxy: TrustedProxyConfig = TrustedProxyConfig()
): IdentityConfig {
    fun secret(name: String) = SecretReference("test", name, "v1", IdentityEnvironment.PRODUCTION)
    return IdentityConfig(
        environment = IdentityEnvironment.PRODUCTION,
        publicBaseUrl = "https://identity.example.test",
        relyingParty = RelyingPartyConfig(
            "identity.example.test",
            "Aether production HTTP test",
            setOf("https://identity.example.test")
        ),
        keys = IdentityKeyConfig(
            sessionPepper = secret("session"),
            recoveryPepper = secret("recovery"),
            deviceTokenPepper = secret("device"),
            serviceCredentialPepper = secret("service"),
            auditPseudonymizationKey = secret("audit"),
            encryptionKey = secret("encryption"),
            signingKey = secret("signing")
        ),
        trustedProxy = trustedProxy,
        bootstrapSecret = secret("bootstrap")
    )
}

private suspend fun productionHttpApi(config: IdentityConfig): IdentityHttpApi {
    val runtime = DeterministicIdentityRuntime()
    config.secretReferences().forEachIndexed { index, reference ->
        runtime.deterministicSecrets.register(reference, ByteArray(32) { (index + it + 1).toByte() })
    }
    val store = InMemoryIdentityStore()
    return IdentityHttpApi(
        runtime.runtime,
        config,
        WebAuthnService(store, runtime.runtime, config),
        IdentityRecoveryService(store, runtime.runtime, config),
        IdentityAccountManagementService(store, runtime.runtime),
        IdentityDeviceAuthorizationService(
            store,
            runtime.runtime,
            config,
            allowedCapabilities = setOf(Capability.ORGANIZATION_READ)
        ),
        recoveryAttemptLimiter = IdentityRecoveryAttemptLimiter { true }
    )
}

private fun IdentityConfig.secretReferences(): List<SecretReference> = listOf(
    keys.sessionPepper,
    keys.recoveryPepper,
    keys.deviceTokenPepper,
    keys.serviceCredentialPepper,
    keys.auditPseudonymizationKey,
    keys.encryptionKey,
    keys.signingKey
) + listOfNotNull(bootstrapSecret)

private fun <T> IdentityOperationResult<T>.success(): T = when (this) {
    is IdentityOperationResult.Success -> value
    is IdentityOperationResult.Failure -> error("Expected success, got $code")
}
