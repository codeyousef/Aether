package codes.yousef.aether.auth.scim

import codes.yousef.aether.auth.OrganizationId
import codes.yousef.aether.auth.testkit.IdentityFixtures
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ScimHttpMiddlewareTest {
    @Test
    fun `non SCIM routes fall through without authenticating or reading a body`() = runTest {
        var authentications = 0
        var handled = false
        val middleware = middleware(
            handler = { handled = true; ScimResponse(200) },
            authenticator = {
                authentications += 1
                ScimAuthenticationResult.Authenticated(PRINCIPAL)
            }
        )
        val exchange = TestExchange(HttpMethod.POST, "/application/route", body = "secret".encodeToByteArray())
        var continued = false

        middleware.asMiddleware()(exchange) { continued = true }

        assertTrue(continued)
        assertEquals(0, authentications)
        assertFalse(handled)
        assertEquals(0, exchange.requestValue.bodyReads)
    }

    @Test
    fun `authentication and tenant authorization fail closed before body IO`() = runTest {
        suspend fun execute(
            authentication: ScimAuthenticationResult,
            authorization: ScimAuthorizationDecision = ScimAuthorizationDecision.ALLOW
        ): TestExchange {
            val exchange = TestExchange(
                HttpMethod.POST,
                "/scim/v2/Users",
                body = "credential-material".encodeToByteArray()
            )
            middleware(
                handler = { error("Denied requests must not reach the engine") },
                authenticator = { authentication },
                authorizer = { _, organizationId ->
                    assertEquals(ORGANIZATION_ID, organizationId)
                    authorization
                }
            ).asMiddleware()(exchange) { error("SCIM routes must not fall through") }
            assertEquals(0, exchange.requestValue.bodyReads)
            return exchange
        }

        assertEquals(401, execute(ScimAuthenticationResult.Rejected).response.statusCode)
        assertEquals(
            403,
            execute(
                ScimAuthenticationResult.Authenticated(PRINCIPAL),
                ScimAuthorizationDecision.DENY
            ).response.statusCode
        )
        assertEquals(503, execute(ScimAuthenticationResult.Unavailable).response.statusCode)
        assertEquals(
            503,
            execute(
                ScimAuthenticationResult.Authenticated(PRINCIPAL),
                ScimAuthorizationDecision.UNAVAILABLE
            ).response.statusCode
        )
    }

    @Test
    fun `strict mapping forwards stable operation conditional metadata and preserves engine response`() = runTest {
        val operationId = IdentityFixtures.scimOperationId("provider-delivery-123").value
        val body = """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"userName":"alice"}"""
            .encodeToByteArray()
        var received: ScimRequest? = null
        var authenticatedMetadata: ScimAuthenticationRequest? = null
        val expectedBody = byteArrayOf(0x01, 0x23, 0x45)
        val middleware = middleware(
            handler = { request ->
                received = request
                ScimResponse(
                    status = 201,
                    headers = mapOf(
                        "Content-Type" to "application/scim+json",
                        "Location" to "https://identity.example.test/scim/v2/Users/user-1",
                        "ETag" to "W/\"1\""
                    ),
                    body = expectedBody
                )
            },
            authenticator = { metadata ->
                authenticatedMetadata = metadata
                ScimAuthenticationResult.Authenticated(PRINCIPAL)
            }
        )
        val exchange = TestExchange(
            method = HttpMethod.PATCH,
            path = "/scim/v2/Users/user-1",
            headers = Headers.of(
                "Content-Type" to "application/scim+json; charset=UTF-8",
                "Content-Length" to body.size.toString(),
                "Idempotency-Key" to operationId,
                "X-Request-ID" to "request-123",
                "If-Match" to "W/\"7\"",
                "User-Agent" to "SCIM Client/1.0",
                "Authorization" to "Bearer must-not-be-forwarded"
            ),
            body = body
        )

        middleware.asMiddleware()(exchange) { error("SCIM routes must not fall through") }

        assertEquals(HttpMethod.PATCH, authenticatedMetadata?.method)
        assertEquals("/scim/v2/Users/user-1", authenticatedMetadata?.path)
        val request = assertNotNull(received)
        assertEquals(ScimHttpMethod.PATCH, request.method)
        assertEquals(operationId, request.operationId?.value)
        assertEquals("request-123", request.requestId)
        assertEquals("W/\"7\"", request.header("if-match"))
        assertEquals("SCIM Client/1.0", request.header("user-agent"))
        assertEquals(null, request.header("authorization"))
        assertContentEquals(body, request.bodyBytes())
        assertEquals(201, exchange.response.statusCode)
        assertEquals("application/scim+json", exchange.response.headers.build()["Content-Type"])
        assertEquals("W/\"1\"", exchange.response.headers.build()["ETag"])
        assertContentEquals(expectedBody, exchange.response.bodyBytes())
    }

    @Test
    fun `query decoding is strict and duplicate parameters never reach the engine`() = runTest {
        var received: ScimRequest? = null
        val middleware = middleware(handler = { request ->
            received = request
            ScimResponse(200, mapOf("Content-Type" to "application/scim+json"), "{}".encodeToByteArray())
        })
        val valid = TestExchange(
            method = HttpMethod.GET,
            path = "/scim/v2/Users",
            query = "filter=userName%20eq%20%22alice%40example.test%22&startIndex=2&count=10",
            headers = Headers.of("If-None-Match" to "W/\"4\"")
        )
        middleware.asMiddleware()(valid) { error("SCIM routes must not fall through") }

        val mapped = assertNotNull(received)
        assertEquals("userName eq \"alice@example.test\"", mapped.query["filter"])
        assertEquals("2", mapped.query["startIndex"])
        assertEquals("10", mapped.query["count"])
        assertEquals("W/\"4\"", mapped.header("If-None-Match"))

        received = null
        val duplicate = TestExchange(
            method = HttpMethod.GET,
            path = "/scim/v2/Users",
            query = "count=1&count=2"
        )
        middleware.asMiddleware()(duplicate) { error("SCIM routes must not fall through") }
        assertEquals(400, duplicate.response.statusCode)
        assertEquals(null, received)
        assertSafeScimError(duplicate, "400")
    }

    @Test
    fun `body limits content type method and operation ID are enforced before engine dispatch`() = runTest {
        var handled = 0
        val middleware = middleware(
            handler = {
                handled += 1
                ScimResponse(200)
            },
            config = ScimHttpMiddlewareConfig(ORGANIZATION_ID, maximumBodyBytes = 1_024)
        )
        val declaredOversize = TestExchange(
            HttpMethod.POST,
            "/scim/v2/Users",
            headers = Headers.of(
                "Content-Type" to "application/scim+json",
                "Content-Length" to "1025",
                "Idempotency-Key" to IdentityFixtures.scimOperationId("oversize-op").value
            ),
            body = ByteArray(1_025)
        )
        middleware.asMiddleware()(declaredOversize) { error("SCIM routes must not fall through") }
        assertEquals(413, declaredOversize.response.statusCode)
        assertEquals(0, declaredOversize.requestValue.bodyReads)

        val observedOversize = TestExchange(
            HttpMethod.POST,
            "/scim/v2/Users",
            headers = Headers.of(
                "Content-Type" to "application/scim+json",
                "Idempotency-Key" to IdentityFixtures.scimOperationId("observed-op").value
            ),
            body = ByteArray(1_025)
        )
        middleware.asMiddleware()(observedOversize) { error("SCIM routes must not fall through") }
        assertEquals(413, observedOversize.response.statusCode)
        assertEquals(1, observedOversize.requestValue.bodyReads)

        val missingOperation = TestExchange(
            HttpMethod.POST,
            "/scim/v2/Users",
            headers = Headers.of("Content-Type" to "application/scim+json"),
            body = "{}".encodeToByteArray()
        )
        middleware.asMiddleware()(missingOperation) { error("SCIM routes must not fall through") }
        assertEquals(400, missingOperation.response.statusCode)
        assertEquals(0, missingOperation.requestValue.bodyReads)

        val wrongMedia = TestExchange(
            HttpMethod.POST,
            "/scim/v2/Users",
            headers = Headers.of(
                "Content-Type" to "application/json",
                "Idempotency-Key" to IdentityFixtures.scimOperationId("wrong-media-op").value
            ),
            body = "{}".encodeToByteArray()
        )
        middleware.asMiddleware()(wrongMedia) { error("SCIM routes must not fall through") }
        assertEquals(415, wrongMedia.response.statusCode)
        assertEquals(0, wrongMedia.requestValue.bodyReads)

        val unsupported = TestExchange(HttpMethod.HEAD, "/scim/v2/Users")
        middleware.asMiddleware()(unsupported) { error("SCIM routes must not fall through") }
        assertEquals(405, unsupported.response.statusCode)
        assertEquals(0, unsupported.requestValue.bodyReads)
        assertEquals(0, handled)
    }

    @Test
    fun `provider failures become generic SCIM unavailable errors`() = runTest {
        val exchange = TestExchange(HttpMethod.GET, "/scim/v2/Users")
        middleware(handler = { throw IllegalStateException("database password and internal details") })
            .asMiddleware()(exchange) { error("SCIM routes must not fall through") }

        assertEquals(503, exchange.response.statusCode)
        assertSafeScimError(exchange, "503")
        assertFalse(exchange.response.bodyText().contains("database", ignoreCase = true))
        assertFalse(exchange.response.bodyText().contains("password", ignoreCase = true))
    }

    private fun middleware(
        handler: suspend (ScimRequest) -> ScimResponse,
        authenticator: suspend (ScimAuthenticationRequest) -> ScimAuthenticationResult = {
            ScimAuthenticationResult.Authenticated(PRINCIPAL)
        },
        authorizer: suspend (ScimClientPrincipal, OrganizationId) -> ScimAuthorizationDecision = { _, _ ->
            ScimAuthorizationDecision.ALLOW
        },
        config: ScimHttpMiddlewareConfig = ScimHttpMiddlewareConfig(ORGANIZATION_ID)
    ) = ScimHttpMiddleware(
        handler = ScimRequestHandler(handler),
        authenticator = ScimAuthenticator(authenticator),
        authorizer = ScimTenantAuthorizer(authorizer),
        config = config
    )

    private companion object {
        val ORGANIZATION_ID = IdentityFixtures.organizationId("organization-1")
        val PRINCIPAL = ScimClientPrincipal("scim-client-1")
    }
}

private class TestRequest(
    override val method: HttpMethod,
    override val path: String,
    override val query: String?,
    override val headers: Headers,
    private val body: ByteArray
) : Request {
    override val uri: String = if (query == null) path else "$path?$query"
    override val cookies: Cookies = Cookies.Empty
    override val connection: RequestConnection = RequestConnection("https", "identity.example.test", "127.0.0.1")
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
    fun bodyBytes(): ByteArray = body.toByteArray()
    fun bodyText(): String = bodyBytes().decodeToString()
}

private class TestExchange(
    method: HttpMethod,
    path: String,
    query: String? = null,
    headers: Headers = Headers.Empty,
    body: ByteArray = ByteArray(0)
) : Exchange {
    val requestValue = TestRequest(method, path, query, headers, body)
    override val request: Request = requestValue
    override val response = TestResponse()
    override val attributes = Attributes()
}

private fun assertSafeScimError(exchange: TestExchange, expectedStatus: String) {
    assertEquals("application/scim+json", exchange.response.headers.build()["Content-Type"])
    val payload = Json.parseToJsonElement(exchange.response.bodyText()).jsonObject
    assertEquals(
        listOf(ScimSchemas.ERROR),
        payload.getValue("schemas").jsonArray.map { it.jsonPrimitive.content }
    )
    assertEquals(expectedStatus, payload.getValue("status").jsonPrimitive.content)
    assertFalse(payload.getValue("detail").jsonPrimitive.content.isBlank())
}
