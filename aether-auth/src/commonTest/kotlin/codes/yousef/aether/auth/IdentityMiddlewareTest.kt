package codes.yousef.aether.auth

import codes.yousef.aether.core.Attributes
import codes.yousef.aether.core.Cookie
import codes.yousef.aether.core.Cookies
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.Headers
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.Request
import codes.yousef.aether.core.Response
import codes.yousef.aether.core.auth.PrincipalAttributeKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class IdentityMiddlewareTest {
    private val now = Instant.parse("2026-07-14T12:00:00Z")
    private val secureRandom = IdentitySecureRandom { size -> ByteArray(size) { (it + 1).toByte() } }

    @Test
    fun `required middleware rejects anonymous with stable redacted error`() = runTest {
        val exchange = TestIdentityExchange()
        val middleware = IdentityMiddleware(
            resolver = IdentityContextResolver { IdentityResolutionResult.Anonymous },
            clock = IdentityClock { now },
            secureRandom = secureRandom,
            required = true
        ).asMiddleware()
        var nextCalled = false

        middleware(exchange) { nextCalled = true }

        assertFalse(nextCalled)
        assertEquals(401, exchange.response.statusCode)
        assertSafeIdentityError(exchange, "authentication_required")
    }

    @Test
    fun `duplicate supplied request IDs are not reflected`() = runTest {
        val exchange = TestIdentityExchange(
            requestHeaders = Headers.build {
                add(DEFAULT_IDENTITY_REQUEST_ID_HEADER, "attacker-one")
                add(DEFAULT_IDENTITY_REQUEST_ID_HEADER, "attacker-two")
            }
        )
        val middleware = IdentityMiddleware(
            resolver = IdentityContextResolver { IdentityResolutionResult.Anonymous },
            clock = IdentityClock { now },
            secureRandom = secureRandom,
            required = true
        ).asMiddleware()

        middleware(exchange) { error("Required middleware must reject") }

        assertSafeIdentityError(exchange, "authentication_required")
        assertTrue(
            exchange.response.headers.build()[DEFAULT_IDENTITY_REQUEST_ID_HEADER]!!.startsWith("req_")
        )
    }

    @Test
    fun `revoked and expired sessions are rejected before route execution`() = runTest {
        val revoked = context(sessionState = SessionState.REVOKED, revokedAt = now)
        val revokedExchange = TestIdentityExchange()
        var revokedNext = false
        IdentityMiddleware(
            IdentityContextResolver { IdentityResolutionResult.Authenticated(revoked) },
            IdentityClock { now },
            secureRandom
        ).asMiddleware()(revokedExchange) { revokedNext = true }
        assertFalse(revokedNext)
        assertEquals(401, revokedExchange.response.statusCode)
        assertSafeIdentityError(revokedExchange, "session_revoked")

        val expired = context(idleExpiresAt = now - 1.minutes)
        val expiredExchange = TestIdentityExchange()
        var expiredNext = false
        IdentityMiddleware(
            IdentityContextResolver { IdentityResolutionResult.Authenticated(expired) },
            IdentityClock { now },
            secureRandom
        ).asMiddleware()(expiredExchange) { expiredNext = true }
        assertFalse(expiredNext)
        assertSafeIdentityError(expiredExchange, "session_expired")
    }

    @Test
    fun `restricted enrollment session cannot escape to application routes or global principal`() = runTest {
        val recovery = context(assurance = AuthenticationAssurance.RECOVERY)
        val applicationExchange = TestIdentityExchange(path = "/application/account")
        var applicationNext = false
        val middleware = IdentityMiddleware(
            IdentityContextResolver { IdentityResolutionResult.Authenticated(recovery) },
            IdentityClock { now },
            secureRandom
        ).asMiddleware()

        middleware(applicationExchange) { applicationNext = true }

        assertFalse(applicationNext)
        assertSafeIdentityError(applicationExchange, "authentication_required")
        assertEquals(null, applicationExchange.attributes.get(PrincipalAttributeKey))

        val enrollmentExchange = TestIdentityExchange(
            method = HttpMethod.POST,
            path = IdentityHttpApi.REGISTRATION_START
        )
        var enrollmentNext = false
        middleware(enrollmentExchange) { enrollmentNext = true }

        assertTrue(enrollmentNext)
        assertEquals(recovery, enrollmentExchange.identityContext)
        assertEquals(null, enrollmentExchange.attributes.get(PrincipalAttributeKey))
    }

    @Test
    fun `organization role guard rejects a recovery enrollment session`() = runTest {
        val exchange = TestIdentityExchange().also {
            it.attributes.put(
                IdentityContextAttributeKey,
                context(role = OrganizationRole.OWNER, assurance = AuthenticationAssurance.RECOVERY)
            )
        }
        var nextCalled = false

        requireOrganizationRole(OrganizationRole.OWNER)(exchange) { nextCalled = true }

        assertFalse(nextCalled)
        assertEquals(404, exchange.response.statusCode)
    }

    @Test
    fun `organization and capability denial are non-enumerating`() = runTest {
        val context = context(role = OrganizationRole.VIEWER)
        val exchange = TestIdentityExchange().also {
            it.attributes.put(IdentityContextAttributeKey, context)
        }
        var nextCalled = false

        requireOrganization(OrganizationId("01900000-0000-7000-8000-000000000099"))(exchange) {
            nextCalled = true
        }
        assertFalse(nextCalled)
        assertEquals(404, exchange.response.statusCode)

        val capabilityExchange = TestIdentityExchange().also {
            it.attributes.put(IdentityContextAttributeKey, context)
        }
        requireCapability(Capability.SERVICE_IDENTITY_MANAGE)(capabilityExchange) { nextCalled = true }
        assertFalse(nextCalled)
        assertEquals(404, capabilityExchange.response.statusCode)
    }

    @Test
    fun `recent passkey guard accepts fresh passkey and rejects stale authentication`() = runTest {
        val fresh = TestIdentityExchange().also {
            it.attributes.put(IdentityContextAttributeKey, context(authenticatedAt = now - 4.minutes))
            it.attributes.put(IdentityRequestTimeAttributeKey, now)
        }
        var freshNext = false
        requireRecentPasskey()(fresh) { freshNext = true }
        assertTrue(freshNext)

        val stale = TestIdentityExchange().also {
            it.attributes.put(IdentityContextAttributeKey, context(authenticatedAt = now - 6.minutes))
            it.attributes.put(IdentityRequestTimeAttributeKey, now)
        }
        var staleNext = false
        requireRecentPasskey()(stale) { staleNext = true }
        assertFalse(staleNext)
        assertEquals(403, stale.response.statusCode)
    }

    private fun context(
        role: OrganizationRole = OrganizationRole.OWNER,
        sessionState: SessionState = SessionState.ACTIVE,
        authenticatedAt: Instant = now - 1.minutes,
        idleExpiresAt: Instant = now + 1.hours,
        revokedAt: Instant? = null,
        assurance: AuthenticationAssurance = AuthenticationAssurance.PASSKEY
    ): IdentityContext {
        val userId = UserId("01900000-0000-7000-8000-000000000001")
        val sessionId = SessionId("01900000-0000-7000-8000-000000000002")
        val organization = Organization(
            id = OrganizationId("01900000-0000-7000-8000-000000000003"),
            name = "Example",
            slug = "example",
            createdAt = now - 1.hours,
            updatedAt = now
        )
        val membership = Membership(
            id = MembershipId("01900000-0000-7000-8000-000000000004"),
            organizationId = organization.id,
            userId = userId,
            role = role,
            createdAt = now - 1.hours,
            updatedAt = now
        )
        val session = IdentitySession(
            id = sessionId,
            familyId = sessionId,
            userId = userId,
            tokenDigest = SecretDigest(DigestAlgorithm.HMAC_SHA256, "digest", "v1"),
            csrfDigest = SecretDigest(DigestAlgorithm.HMAC_SHA256, "csrf", "v1"),
            assurance = assurance,
            authenticationMethod = if (assurance == AuthenticationAssurance.RECOVERY) {
                SessionAuthenticationMethod.RECOVERY_CODE
            } else {
                SessionAuthenticationMethod.PASSKEY
            },
            userSessionEpoch = 0,
            state = sessionState,
            createdAt = now - 1.hours,
            authenticatedAt = authenticatedAt,
            lastUsedAt = now - 2.minutes,
            idleExpiresAt = idleExpiresAt,
            absoluteExpiresAt = now + 12.hours,
            revokedAt = revokedAt,
            revocationReasonCode = if (sessionState == SessionState.REVOKED) "test" else null
        )
        val principal = IdentityPrincipal(
            kind = IdentityPrincipalKind.USER,
            userId = userId,
            displayName = "User",
            assurance = assurance,
            authenticatedAt = authenticatedAt,
            sessionId = sessionId
        )
        return IdentityContext(principal, session, organization, membership)
    }
}

private fun assertSafeIdentityError(exchange: TestIdentityExchange, expectedCode: String) {
    val payload = Json.parseToJsonElement(exchange.response.bodyText()).jsonObject
    assertEquals(setOf("error"), payload.keys)
    val error = payload.getValue("error").jsonObject
    assertEquals(setOf("code", "message", "requestId", "retryable"), error.keys)
    assertEquals(expectedCode, error.getValue("code").jsonPrimitive.content)
    assertFalse(error.getValue("message").jsonPrimitive.content.isBlank())
    val responseRequestId = exchange.response.headers.build()[DEFAULT_IDENTITY_REQUEST_ID_HEADER]
    assertFalse(responseRequestId.isNullOrBlank())
    assertEquals(responseRequestId, error.getValue("requestId").jsonPrimitive.content)
    assertFalse(exchange.response.bodyText().contains("Exception"))
}

private class TestIdentityRequest(
    override val headers: Headers = Headers.Empty,
    override val method: HttpMethod = HttpMethod.GET,
    override val path: String = "/identity/v1/test"
) : Request {
    override val uri: String = path
    override val query: String? = null
    override val cookies: Cookies = Cookies.of(Cookie("__Host-aether_session", "opaque"))
    override suspend fun bodyBytes(): ByteArray = ByteArray(0)
}

private class TestIdentityResponse : Response {
    override var statusCode: Int = 200
    override var statusMessage: String? = null
    override val headers = Headers.HeadersBuilder()
    override val cookies = mutableListOf<Cookie>()
    private val body = mutableListOf<Byte>()

    override suspend fun write(data: ByteArray) { body.addAll(data.toList()) }
    override suspend fun end() = Unit
    fun bodyText(): String = body.toByteArray().decodeToString()
}

private class TestIdentityExchange(
    override val attributes: Attributes = Attributes(),
    requestHeaders: Headers = Headers.Empty,
    method: HttpMethod = HttpMethod.GET,
    path: String = "/identity/v1/test"
) : Exchange {
    override val request: Request = TestIdentityRequest(requestHeaders, method, path)
    override val response: TestIdentityResponse = TestIdentityResponse()
}
