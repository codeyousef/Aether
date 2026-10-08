package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.Attributes
import codes.yousef.aether.core.Cookie
import codes.yousef.aether.core.Cookies
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.Headers
import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.Request
import codes.yousef.aether.core.Response
import codes.yousef.aether.core.upload.UploadErrorCode
import codes.yousef.aether.core.upload.UploadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RequestDiagnosticsTest {
    @Test
    fun `typed failures emit canonical correlated JSON for every public mapping`() = runTest {
        ApiErrorKind.entries.forEach { kind ->
            val exchange = TestExchange(request = TestRequest(requestId = "valid-request-123"))
            Recovery().middleware()(exchange) { throw ApiException(kind) }

            assertEquals(kind.statusCode, exchange.response.statusCode, kind.name)
            assertEquals("valid-request-123", exchange.response.headers.build()["X-Request-Id"], kind.name)
            assertEquals(encodeApiError(kind, "valid-request-123"), exchange.response.text(), kind.name)
            assertFalse(exchange.response.text().contains("Exception"), kind.name)
        }
    }

    @Test
    fun `arbitrary and deeply wrapped exceptions remain opaque internal failures`() = runTest {
        val marker = "PRIVATE_DIAGNOSTIC_MARKER"
        var failure: Throwable = ApiException(ApiErrorKind.PERMISSION_DENIED)
        repeat(10) { failure = IllegalStateException(marker, failure) }
        val exchange = TestExchange(request = TestRequest(path = "/$marker", body = marker.encodeToByteArray()))

        Recovery(errorPolicy = ApiErrorPolicy(maximumCauseDepth = 8)).middleware()(exchange) { throw failure }

        assertEquals(500, exchange.response.statusCode)
        assertEquals(ApiErrorKind.INTERNAL.code, exchange.attributes.get(RequestDiagnostics.ErrorCategoryKey))
        assertFalse(exchange.response.text().contains(marker))
        assertFalse(exchange.response.text().contains("IllegalStateException"))
    }

    @Test
    fun `malformed supplied request ID is replaced before downstream processing`() = runTest {
        val exchange = TestExchange(request = TestRequest(requestId = "bad id with spaces and / separators"))
        var observedId: String? = null

        CallLogging(messageSink = { observedId = it.substringAfter("request_id=").substringBefore(' ') })
            .middleware()(exchange) {}

        val responseId = exchange.response.headers.build()["X-Request-Id"]
        assertEquals(responseId, observedId)
        assertNotEquals(exchange.request.headers["X-Request-Id"], responseId)
        assertTrue(responseId!!.length in 8..64)
        assertTrue(responseId.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' })
    }

    @Test
    fun `call logging excludes request-controlled values and uses route templates`() = runTest {
        val marker = "PRIVATE_LOG_MARKER"
        val messages = mutableListOf<String>()
        val exchange = TestExchange(
            request = TestRequest(
                path = "/accounts/$marker",
                query = "token=$marker",
                requestId = "safe-request-123",
                body = marker.encodeToByteArray(),
                extraHeaders = Headers.of("Authorization" to marker, "Cookie" to "session=$marker"),
                cookies = Cookies.of(Cookie("session", marker))
            )
        )
        exchange.attributes.put(RequestDiagnostics.RouteTemplateKey, "/accounts/:accountId")

        CallLogging(messageSink = messages::add).middleware()(exchange) {}

        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("request_id=safe-request-123"))
        assertTrue(messages.single().contains("route=/accounts/:accountId"))
        assertFalse(messages.single().contains(marker))
        assertFalse(messages.single().contains("token="))
    }

    @Test
    fun `cancellation and fatal errors bypass recovery`() = runTest {
        val cancellation = CancellationException("PRIVATE_CANCEL_MARKER")
        assertFailsWith<CancellationException> {
            Recovery().middleware()(TestExchange()) { throw cancellation }
        }
        assertFailsWith<AssertionError> {
            Recovery().middleware()(TestExchange()) { throw AssertionError("PRIVATE_FATAL_MARKER") }
        }
    }

    @Test
    fun `route log tokens are bounded and cannot inject lines`() = runTest {
        val messages = mutableListOf<String>()
        val exchange = TestExchange(request = TestRequest(requestId = "safe-request-123"))
        exchange.attributes.put(RequestDiagnostics.RouteTemplateKey, "/safe\nINJECTED")

        CallLogging(
            diagnostics = RequestDiagnosticsPolicy(
                maximumRouteTemplateLength = 8,
                unmatchedRouteLabel = "<none>"
            ),
            messageSink = messages::add
        ).middleware()(exchange) {}

        assertFalse(messages.single().contains('\n'))
        assertTrue(messages.single().contains("route=/safe_IN"))
    }

    @Test
    fun `recovery never appends JSON after response commitment`() = runTest {
        val exchange = TestExchange()

        Recovery().middleware()(exchange) {
            exchange.response.write("stream-prefix")
            throw ApiException(ApiErrorKind.CONFLICT)
        }

        assertEquals("stream-prefix", exchange.response.text())
        assertTrue(exchange.response.isCommitted)
    }

    @Test
    fun `every canonical error status carries the same request id`() = runTest {
        ApiErrorKind.entries.forEach { kind ->
            val exchange = TestExchange(request = TestRequest(requestId = "correlation-id-123"))

            respondWithApiError(exchange, kind)

            assertEquals(kind.statusCode, exchange.response.statusCode, kind.code)
            assertEquals("correlation-id-123", exchange.response.headers.build()["X-Request-Id"], kind.code)
            assertTrue(exchange.response.text().contains("\"request_id\":\"correlation-id-123\""), kind.code)
            assertFalse(exchange.response.text().contains("Exception"), kind.code)
        }
    }

    @Test
    fun `security finalization runs before recovery writes its error`() = runTest {
        val exchange = TestExchange()
        val pipeline = Pipeline().apply {
            installRecovery()
            use { securedExchange, next ->
                try {
                    next()
                } finally {
                    securedExchange.response.setHeader("X-Security-Finalized", "true")
                }
            }
        }

        pipeline.execute(exchange) { throw ApiException(ApiErrorKind.PERMISSION_DENIED) }

        assertEquals("true", exchange.response.headers.build()["X-Security-Finalized"])
        assertEquals(403, exchange.response.statusCode)
    }

    @Test
    fun `multipart failures map to bounded public categories`() {
        val policy = ApiErrorPolicy()
        assertEquals(
            ApiErrorKind.PAYLOAD_TOO_LARGE,
            policy.classify(UploadException("private size", UploadErrorCode.FILE_TOO_LARGE))
        )
        assertEquals(
            ApiErrorKind.UNSUPPORTED_FORMAT,
            policy.classify(UploadException("private type", UploadErrorCode.INVALID_CONTENT_TYPE))
        )
        assertEquals(
            ApiErrorKind.BAD_REQUEST,
            policy.classify(UploadException("private parser", UploadErrorCode.PARSE_ERROR))
        )
    }

    @Test
    fun `private production disables toolbar and query capture`() = runTest {
        val exchange = TestExchange()
        var nextCalled = false
        DebugToolbar(
            DebugToolbarConfig(enabled = true, profile = DiagnosticsProfile.PRIVATE_PRODUCTION)
        ).invoke(exchange) { nextCalled = true }
        val queryLog = QueryLogContext(DiagnosticsProfile.PRIVATE_PRODUCTION)
        queryLog.record(QueryLogEntry("SELECT 'PRIVATE_SQL_MARKER'", 1))

        assertTrue(nextCalled)
        assertFalse(exchange.attributes.contains(Exchange.HtmlResponseHooksKey))
        assertTrue(queryLog.logs.isEmpty())
    }
}

private class TestRequest(
    override val path: String = "/",
    override val query: String? = null,
    requestId: String? = null,
    private val body: ByteArray = ByteArray(0),
    extraHeaders: Headers = Headers.Empty,
    override val cookies: Cookies = Cookies.Empty
) : Request {
    override val method: HttpMethod = HttpMethod.POST
    override val uri: String = if (query == null) path else "$path?$query"
    override val headers: Headers = Headers.build {
        extraHeaders.entries().forEach { (name, values) -> values.forEach { add(name, it) } }
        if (requestId != null) add("X-Request-Id", requestId)
    }

    override suspend fun bodyBytes(): ByteArray = body
}

private class TestResponse : Response {
    override var statusCode: Int = 200
    override var statusMessage: String? = null
    override val headers: Headers.HeadersBuilder = Headers.HeadersBuilder()
    override val cookies: MutableList<Cookie> = mutableListOf()
    private val body = mutableListOf<Byte>()
    private var committed = false
    override val isCommitted: Boolean
        get() = committed

    override suspend fun write(data: ByteArray) {
        committed = true
        body.addAll(data.toList())
    }

    override suspend fun end() {
        committed = true
    }

    fun text(): String = body.toByteArray().decodeToString()
}

private class TestExchange(
    override val request: Request = TestRequest(),
    override val response: TestResponse = TestResponse(),
    override val attributes: Attributes = Attributes()
) : Exchange
