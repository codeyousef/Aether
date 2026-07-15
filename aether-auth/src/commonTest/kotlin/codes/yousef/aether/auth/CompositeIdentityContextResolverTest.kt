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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class CompositeIdentityContextResolverTest {
    private val config = resolverConfig()
    private val authenticated = serviceContext()
    private val validToken = "01900000-0000-7000-8000-000000000001.AAECAwQFBgcICQoLDA0ODw"

    @Test
    fun `anonymous requests stay anonymous and cookie requests use the session resolver`() = runTest {
        var sessionCalls = 0
        val resolver = resolver(
            session = {
                sessionCalls++
                IdentityResolutionResult.Authenticated(authenticated)
            }
        )

        assertIs<IdentityResolutionResult.Anonymous>(resolver.resolve(ResolverExchange()))
        val cookie = ResolverExchange(cookies = Cookies.of(Cookie(config.cookie.name, "opaque")))
        assertIs<IdentityResolutionResult.Authenticated>(resolver.resolve(cookie))
        assertEquals(1, sessionCalls)
    }

    @Test
    fun `one bearer tries device before optional service`() = runTest {
        val attempts = mutableListOf<String>()
        val resolver = resolver(
            device = {
                attempts += "device"
                IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            },
            service = {
                attempts += "service"
                IdentityOperationResult.Success(authenticated)
            }
        )

        val result = resolver.resolve(bearer(validToken))

        assertIs<IdentityResolutionResult.Authenticated>(result)
        assertEquals(listOf("device", "service"), attempts)
    }

    @Test
    fun `device success never probes the service credential authority`() = runTest {
        var serviceCalled = false
        val resolver = resolver(
            device = { IdentityOperationResult.Success(authenticated) },
            service = {
                serviceCalled = true
                IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
            }
        )

        assertIs<IdentityResolutionResult.Authenticated>(resolver.resolve(bearer(validToken)))
        assertFalse(serviceCalled)
    }

    @Test
    fun `cookie plus bearer and duplicate or malformed authorization are rejected before verification`() = runTest {
        var verifierCalled = false
        val resolver = resolver(device = {
            verifierCalled = true
            IdentityOperationResult.Success(authenticated)
        })
        val cookieAndBearer = ResolverExchange(
            headers = Headers.of("Authorization" to "Bearer $validToken"),
            cookies = Cookies.of(Cookie(config.cookie.name, "opaque"))
        )
        val duplicate = ResolverExchange(
            headers = Headers.of(
                "Authorization" to "Bearer $validToken",
                "authorization" to "Bearer $validToken"
            )
        )
        val malformed = bearer("not-a-selector-secret")

        listOf(cookieAndBearer, duplicate, malformed).forEach { exchange ->
            val rejected = assertIs<IdentityResolutionResult.Rejected>(resolver.resolve(exchange))
            assertEquals(IdentityErrorCode.INVALID_CREDENTIALS, rejected.code)
        }
        assertFalse(verifierCalled)
    }

    @Test
    fun `authority outages are fail closed without probing the next credential kind`() = runTest {
        var serviceCalled = false
        val resolver = resolver(
            device = { IdentityOperationResult.Failure(IdentityErrorCode.SERVICE_UNAVAILABLE) },
            service = {
                serviceCalled = true
                IdentityOperationResult.Success(authenticated)
            }
        )

        val rejected = assertIs<IdentityResolutionResult.Rejected>(resolver.resolve(bearer(validToken)))

        assertEquals(IdentityErrorCode.SERVICE_UNAVAILABLE, rejected.code)
        assertFalse(serviceCalled)
    }

    private fun resolver(
        session: suspend () -> IdentityResolutionResult = { IdentityResolutionResult.Anonymous },
        device: suspend (String) -> IdentityOperationResult<IdentityContext> = {
            IdentityOperationResult.Failure(IdentityErrorCode.INVALID_CREDENTIALS)
        },
        service: (suspend (String) -> IdentityOperationResult<IdentityContext>)? = null
    ) = CompositeIdentityContextResolver(
        sessionResolver = IdentityContextResolver { session() },
        config = config,
        deviceAuthenticator = IdentityBearerAuthenticator(device),
        serviceAuthenticator = service?.let(::IdentityBearerAuthenticator)
    )

    private fun bearer(token: String) = ResolverExchange(
        headers = Headers.of("Authorization" to "Bearer $token")
    )
}

private fun resolverConfig(): IdentityConfig {
    fun reference(name: String) = SecretReference("test", name, "v1", IdentityEnvironment.TEST)
    return IdentityConfig(
        environment = IdentityEnvironment.TEST,
        publicBaseUrl = "http://localhost:8080",
        relyingParty = RelyingPartyConfig("localhost", "Resolver test", setOf("http://localhost:8080")),
        keys = IdentityKeyConfig(
            sessionPepper = reference("session"),
            recoveryPepper = reference("recovery"),
            deviceTokenPepper = reference("device"),
            serviceCredentialPepper = reference("service"),
            auditPseudonymizationKey = reference("audit"),
            encryptionKey = reference("encryption"),
            signingKey = reference("signing")
        )
    )
}

private fun serviceContext(): IdentityContext = IdentityContext(
    principal = IdentityPrincipal(
        kind = IdentityPrincipalKind.SERVICE,
        serviceIdentityId = ServiceIdentityId("01900000-0000-7000-8000-000000000002"),
        displayName = "Resolver test service",
        assurance = AuthenticationAssurance.SERVICE_CREDENTIAL,
        authenticatedAt = Instant.parse("2026-07-14T12:00:00Z")
    )
)

private class ResolverExchange(
    headers: Headers = Headers.Empty,
    cookies: Cookies = Cookies.Empty
) : Exchange {
    override val request: Request = object : Request {
        override val method = HttpMethod.GET
        override val uri = "/identity/v1/me"
        override val path = uri
        override val query: String? = null
        override val headers = headers
        override val cookies = cookies
        override suspend fun bodyBytes(): ByteArray = ByteArray(0)
    }
    override val response: Response = object : Response {
        override var statusCode: Int = 200
        override var statusMessage: String? = null
        override val headers = Headers.HeadersBuilder()
        override val cookies = mutableListOf<Cookie>()
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun end() = Unit
    }
    override val attributes = Attributes()
}
