package codes.yousef.aether.auth

import codes.yousef.aether.core.Attributes
import codes.yousef.aether.core.Cookie
import codes.yousef.aether.core.Cookies
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.Headers
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.Request
import codes.yousef.aether.core.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class IdentityCsrfJvmTest {
    private val environment = IdentityEnvironment.DEVELOPMENT
    private val currentPepper = reference("session", "v2")
    private val previousPepper = reference("session", "v1")
    private val config = IdentityConfig(
        environment = environment,
        publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig("localhost", "Aether", setOf("http://localhost:8080")),
        keys = IdentityKeyConfig(
            sessionPepper = currentPepper,
            previousSessionPeppers = listOf(previousPepper),
            recoveryPepper = reference("recovery"),
            deviceTokenPepper = reference("device"),
            serviceCredentialPepper = reference("service"),
            auditPseudonymizationKey = reference("audit"),
            encryptionKey = reference("encryption"),
            signingKey = reference("signing")
        )
    )
    private val runtime = jvmIdentityRuntime(
        secrets = IdentitySecretResolver { reference ->
            IdentitySecret.fromBytes(ByteArray(32) { reference.name.length.toByte() })
        },
        http = IdentityHttpClient { error("unused") }
    )

    @Test
    fun `unsafe cookie request requires exact origin and session-bound header token`() = runTest {
        val token = issueIdentityCsrfToken(runtime, config)
        val valid = CsrfExchange(
            headers = Headers.of("Origin" to config.publicBaseUrl, "X-CSRF-Token" to token.encoded),
            context = context(token.digest)
        )
        var called = false

        IdentityCsrfMiddleware(runtime, config).asMiddleware()(valid) { called = true }

        assertTrue(called)
        assertEquals(200, valid.response.statusCode)

        val wrongOrigin = CsrfExchange(
            headers = Headers.of("Origin" to "http://localhost.evil", "X-CSRF-Token" to token.encoded),
            context = context(token.digest)
        )
        var wrongOriginCalled = false
        IdentityCsrfMiddleware(runtime, config).asMiddleware()(wrongOrigin) { wrongOriginCalled = true }
        assertFalse(wrongOriginCalled)
        assertEquals(403, wrongOrigin.response.statusCode)
    }

    @Test
    fun `query token and duplicate header values are rejected`() = runTest {
        val token = issueIdentityCsrfToken(runtime, config)
        val queryOnly = CsrfExchange(
            headers = Headers.of(
                "Origin" to config.publicBaseUrl,
                "X-CSRF-Token" to token.encoded
            ),
            context = context(token.digest),
            query = "_csrf=${token.encoded}"
        )
        IdentityCsrfMiddleware(runtime, config).asMiddleware()(queryOnly) { error("must not run") }
        assertEquals(403, queryOnly.response.statusCode)
        assertFalse(queryOnly.response.headers.build()[DEFAULT_IDENTITY_REQUEST_ID_HEADER].isNullOrBlank())

        val duplicate = CsrfExchange(
            headers = Headers.build {
                add("Origin", config.publicBaseUrl)
                add("X-CSRF-Token", token.encoded)
                add("X-CSRF-Token", token.encoded)
            },
            context = context(token.digest)
        )
        IdentityCsrfMiddleware(runtime, config).asMiddleware()(duplicate) { error("must not run") }
        assertEquals(403, duplicate.response.statusCode)
    }

    @Test
    fun `unsafe request cannot bypass CSRF when resolved session and cookie disagree`() = runTest {
        val token = issueIdentityCsrfToken(runtime, config)
        val missingCookie = CsrfExchange(
            headers = Headers.of(
                "Origin" to config.publicBaseUrl,
                "X-CSRF-Token" to token.encoded
            ),
            context = context(token.digest),
            cookies = Cookies.Empty
        )
        var called = false

        IdentityCsrfMiddleware(runtime, config).asMiddleware()(missingCookie) { called = true }

        assertFalse(called)
        assertEquals(403, missingCookie.response.statusCode)
    }

    private fun context(csrfDigest: SecretDigest): IdentityContext {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        val userId = UserId("01900000-0000-7000-8000-000000000001")
        val sessionId = SessionId("01900000-0000-7000-8000-000000000002")
        val session = IdentitySession(
            id = sessionId,
            familyId = sessionId,
            userId = userId,
            tokenDigest = SecretDigest(DigestAlgorithm.HMAC_SHA256, "unused", currentPepper.version),
            csrfDigest = csrfDigest,
            assurance = AuthenticationAssurance.PASSKEY,
            authenticationMethod = SessionAuthenticationMethod.PASSKEY,
            userSessionEpoch = 0,
            createdAt = now,
            authenticatedAt = now,
            lastUsedAt = now,
            idleExpiresAt = Instant.parse("2026-07-15T00:00:00Z"),
            absoluteExpiresAt = Instant.parse("2026-08-01T00:00:00Z")
        )
        return IdentityContext(
            principal = IdentityPrincipal(
                kind = IdentityPrincipalKind.USER,
                userId = userId,
                displayName = "CSRF test user",
                assurance = AuthenticationAssurance.PASSKEY,
                authenticatedAt = now,
                sessionId = sessionId
            ),
            session = session
        )
    }

    private fun reference(name: String, version: String = "v1") =
        SecretReference("test", name, version, environment)
}

private class CsrfRequest(
    override val headers: Headers,
    override val query: String?,
    override val cookies: Cookies
) : Request {
    override val method: HttpMethod = HttpMethod.POST
    override val uri: String = "/identity/v1/passkeys"
    override val path: String = "/identity/v1/passkeys"
    override suspend fun bodyBytes(): ByteArray = ByteArray(0)
}

private class CsrfResponse : Response {
    override var statusCode: Int = 200
    override var statusMessage: String? = null
    override val headers = Headers.HeadersBuilder()
    override val cookies = mutableListOf<Cookie>()
    private val body = mutableListOf<Byte>()
    override suspend fun write(data: ByteArray) { body.addAll(data.toList()) }
    override suspend fun end() = Unit
    fun bodyText(): String = body.toByteArray().decodeToString()
}

private class CsrfExchange(
    headers: Headers,
    context: IdentityContext,
    query: String? = null,
    cookies: Cookies = Cookies.of(Cookie("__Host-aether_session", "opaque"))
) : Exchange {
    override val request: Request = CsrfRequest(headers, query, cookies)
    override val response: CsrfResponse = CsrfResponse()
    override val attributes: Attributes = Attributes().also {
        it.put(IdentityContextAttributeKey, context)
    }
}
