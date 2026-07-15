package codes.yousef.aether.core.security

import codes.yousef.aether.core.Attributes
import codes.yousef.aether.core.Cookie
import codes.yousef.aether.core.Cookies
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.Headers
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.Request
import codes.yousef.aether.core.Response
import codes.yousef.aether.core.session.DefaultSession
import codes.yousef.aether.core.session.SessionAttributeKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CsrfMiddlewareTest {
    private val allowedOrigin = "https://identity.example.com"
    private val sessionCookie = "__Host-aether_session"
    private val token = "session-bound-csrf-token"

    @Test
    fun `unsafe cookie request requires exact allowed origin and header token`() = runTest {
        val valid = exchange(
            method = HttpMethod.POST,
            headers = Headers.of(
                "Origin" to allowedOrigin,
                "X-CSRF-Token" to token
            )
        )

        assertTrue(invoke(valid))
        assertEquals(200, valid.response.statusCode)

        val differentCase = exchange(
            method = HttpMethod.POST,
            headers = Headers.of(
                "Origin" to "https://IDENTITY.example.com",
                "X-CSRF-Token" to token
            )
        )
        assertFalse(invoke(differentCase))
        assertEquals(403, differentCase.response.statusCode)

        val trailingSlash = exchange(
            method = HttpMethod.POST,
            headers = Headers.of(
                "Origin" to "$allowedOrigin/",
                "X-CSRF-Token" to token
            )
        )
        assertFalse(invoke(trailingSlash))
    }

    @Test
    fun `query and form tokens are never accepted`() = runTest {
        val queryToken = exchange(
            method = HttpMethod.POST,
            query = "_csrf=$token",
            headers = Headers.of("Origin" to allowedOrigin)
        )
        assertFalse(invoke(queryToken))

        val formToken = exchange(
            method = HttpMethod.POST,
            headers = Headers.of(
                "Origin" to allowedOrigin,
                "Content-Type" to "application/x-www-form-urlencoded"
            ),
            body = "_csrf=$token".encodeToByteArray()
        )
        assertFalse(invoke(formToken))
    }

    @Test
    fun `all unsafe methods are protected and safe methods pass`() = runTest {
        val unsafeMethods = listOf(
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.PATCH,
            HttpMethod.DELETE,
            HttpMethod.CONNECT
        )
        unsafeMethods.forEach { method ->
            val request = exchange(method = method, headers = Headers.of("Origin" to allowedOrigin))
            assertFalse(invoke(request), "$method must require the header token")
            assertEquals(403, request.response.statusCode)
        }

        listOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS).forEach { method ->
            assertTrue(invoke(exchange(method = method)), "$method must not require CSRF validation")
        }
    }

    @Test
    fun `unsafe request without ambient session cookie is outside csrf scope`() = runTest {
        val request = exchange(
            method = HttpMethod.POST,
            cookies = Cookies.Empty,
            headers = Headers.of("Authorization" to "Bearer explicit-token")
        )

        assertTrue(invoke(request))
    }

    @Test
    fun `duplicate origin or token headers are rejected`() = runTest {
        val duplicateOrigin = exchange(
            method = HttpMethod.POST,
            headers = Headers.of(
                "Origin" to allowedOrigin,
                "Origin" to allowedOrigin,
                "X-CSRF-Token" to token
            )
        )
        assertFalse(invoke(duplicateOrigin))

        val duplicateToken = exchange(
            method = HttpMethod.POST,
            headers = Headers.of(
                "Origin" to allowedOrigin,
                "X-CSRF-Token" to token,
                "X-CSRF-Token" to token
            )
        )
        assertFalse(invoke(duplicateToken))
    }

    private suspend fun invoke(exchange: TestExchange): Boolean {
        var nextCalled = false
        val middleware = CsrfMiddleware(
            CsrfConfig(
                allowedOrigins = setOf(allowedOrigin),
                sessionCookieNames = setOf(sessionCookie)
            )
        ).asMiddleware()
        middleware(exchange) { nextCalled = true }
        return nextCalled
    }

    private fun exchange(
        method: HttpMethod,
        query: String? = null,
        headers: Headers = Headers.Empty,
        cookies: Cookies = Cookies.of(Cookie(sessionCookie, "opaque-session")),
        body: ByteArray = ByteArray(0)
    ): TestExchange {
        val attributes = Attributes()
        val session = DefaultSession(id = "session-id", createdAt = 1L)
        session.set("_csrf_token", token)
        attributes.put(SessionAttributeKey, session)

        return TestExchange(
            request = TestRequest(method, query, headers, cookies, body),
            attributes = attributes
        )
    }
}

private class TestRequest(
    override val method: HttpMethod,
    override val query: String?,
    override val headers: Headers,
    override val cookies: Cookies,
    private val body: ByteArray
) : Request {
    override val uri: String = if (query == null) "/identity/v1/test" else "/identity/v1/test?$query"
    override val path: String = "/identity/v1/test"
    override suspend fun bodyBytes(): ByteArray = body
}

private class TestResponse : Response {
    override var statusCode: Int = 200
    override var statusMessage: String? = null
    override val headers = Headers.HeadersBuilder()
    override val cookies = mutableListOf<Cookie>()
    val body = mutableListOf<Byte>()

    override suspend fun write(data: ByteArray) {
        body += data.toList()
    }

    override suspend fun end() = Unit
}

private class TestExchange(
    override val request: Request,
    override val response: TestResponse = TestResponse(),
    override val attributes: Attributes
) : Exchange
