package codes.yousef.aether.example

import codes.yousef.aether.auth.BootstrapIdentityRequest
import codes.yousef.aether.auth.EmailAddress
import codes.yousef.aether.auth.IdentityHttpApi
import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.core.Attributes
import codes.yousef.aether.core.Cookie
import codes.yousef.aether.core.Cookies
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.Headers
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.Request
import codes.yousef.aether.core.RequestConnection
import codes.yousef.aether.core.Response
import codes.yousef.aether.core.pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExampleIdentityAuthorityTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `development authority starts bootstraps and resolves its constrained enrollment cookie`() = runBlocking {
        val secret = "example-integration-bootstrap-secret"
        val authority = ExampleIdentityAuthority.create(port = 8080, bootstrapSecret = secret)
        authority.start()
        val pipeline = Pipeline().apply {
            use(authority.identityMiddleware())
            use(authority.httpApi.asMiddleware())
        }
        val bootstrap = ExampleExchange(
            method = HttpMethod.POST,
            path = IdentityHttpApi.BOOTSTRAP,
            headers = Headers.of(
                "Content-Type" to "application/json",
                "Origin" to authority.config.publicBaseUrl
            ),
            body = json.encodeToString(
                BootstrapIdentityRequest(
                    secret = secret,
                    displayName = "Example Owner",
                    primaryEmail = EmailAddress("owner@example.test"),
                    organizationName = "Example Organization",
                    organizationSlug = "example-org"
                )
            ).encodeToByteArray()
        )

        pipeline.execute(bootstrap) { error("Bootstrap route must be handled") }

        assertEquals(201, bootstrap.response.statusCode)
        val payload = Json.parseToJsonElement(bootstrap.response.bodyText()).jsonObject
        val csrf = payload.getValue("csrfToken").jsonPrimitive.content
        val sessionCookie = bootstrap.response.cookies.single { it.name == authority.config.cookie.name }
        assertTrue(sessionCookie.secure)
        assertTrue(sessionCookie.httpOnly)
        assertEquals("/", sessionCookie.path)
        assertFalse(bootstrap.response.bodyText().contains(secret))
        assertFalse(bootstrap.response.bodyText().contains(sessionCookie.value))

        val registration = ExampleExchange(
            method = HttpMethod.POST,
            path = IdentityHttpApi.REGISTRATION_START,
            headers = Headers.of(
                "Origin" to authority.config.publicBaseUrl,
                "X-CSRF-Token" to csrf
            ),
            cookies = Cookies.of(Cookie(authority.config.cookie.name, sessionCookie.value))
        )
        pipeline.execute(registration) { error("Registration route must be handled") }

        assertEquals(200, registration.response.statusCode)
        assertTrue(registration.response.cookies.any { it.name == "__Host-aether_ceremony" })
        val snapshot = authority.store.snapshot()
        assertTrue(snapshot.bootstrapCompleted)
        assertEquals(1, snapshot.users.size)
        assertEquals(1, snapshot.organizations.size)
        assertEquals(1, snapshot.sessions.size)
    }

    @Test
    fun `organization selector accepts only canonical IDs in explicit organization routes`() {
        val organizationId = OrganizationId("01900000-0000-7000-8000-000000000099")
        val explicit = ExampleExchange(
            method = HttpMethod.GET,
            path = "/identity/v1/organizations/${organizationId.value}/memberships"
        )
        val deviceApproval = ExampleExchange(HttpMethod.POST, "/identity/v1/device/approve")
        val malformed = ExampleExchange(HttpMethod.GET, "/identity/v1/organizations/not-a-uuid")

        assertEquals(organizationId, organizationFromExplicitRoute(explicit))
        assertEquals(null, organizationFromExplicitRoute(deviceApproval))
        assertEquals(null, organizationFromExplicitRoute(malformed))
    }

    @Test
    fun `development recovery boundary throttles repeated attempts by pseudonymous direct peer`() = runBlocking {
        val authority = ExampleIdentityAuthority.create(
            port = 8080,
            bootstrapSecret = "example-integration-bootstrap-secret"
        )
        authority.start()
        val pipeline = Pipeline().apply { use(authority.httpApi.asMiddleware()) }

        val codes = (1..6).map {
            val exchange = ExampleExchange(
                method = HttpMethod.POST,
                path = IdentityHttpApi.RECOVERY_CODE_USE,
                headers = Headers.of("Content-Type" to "application/json"),
                body = """{"code":"AAAA-BBBB-CCCC-DDDD-EEEE-FFFF"}""".encodeToByteArray(),
                connection = RequestConnection(peerAddress = "203.0.113.9")
            )
            pipeline.execute(exchange) { error("Recovery route must be handled") }
            Json.parseToJsonElement(exchange.response.bodyText()).jsonObject
                .getValue("error").jsonObject.getValue("code").jsonPrimitive.content
        }

        assertFalse(codes.take(5).contains("rate_limited"))
        assertEquals("rate_limited", codes.last())
    }
}

private class ExampleExchange(
    method: HttpMethod,
    path: String,
    headers: Headers = Headers.Empty,
    cookies: Cookies = Cookies.Empty,
    body: ByteArray = ByteArray(0),
    connection: RequestConnection = RequestConnection()
) : Exchange {
    override val request: Request = object : Request {
        override val method = method
        override val uri = path
        override val path = path
        override val query: String? = null
        override val headers = headers
        override val cookies = cookies
        override val connection = connection
        override suspend fun bodyBytes(): ByteArray = body.copyOf()
    }
    override val response = ExampleResponse()
    override val attributes = Attributes()
}

private class ExampleResponse : Response {
    override var statusCode: Int = 200
    override var statusMessage: String? = null
    override val headers = Headers.HeadersBuilder()
    override val cookies = mutableListOf<Cookie>()
    private val body = mutableListOf<Byte>()

    override suspend fun write(data: ByteArray) {
        body.addAll(data.toList())
    }

    override suspend fun end() = Unit

    fun bodyText(): String = body.toByteArray().decodeToString()
}
