package codes.yousef.aether.browser

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserHttpClientTest {
    @Test
    fun `get decodes response and merges request headers deterministically`() = runTest {
        val transport = RecordingTransport {
            BrowserHttpResponse(200, "OK", mapOf("content-type" to "application/json"), "{\"value\":42}")
        }
        val client = client(
            transport = transport,
            config = BrowserHttpClientConfig(
                defaultHeaders = mapOf("X-Default" to "default", "accept" to "text/plain"),
                headersProvider = BrowserHeadersProvider { mapOf("X-Provider" to it.path) }
            )
        )

        assertEquals(Reply(42), client.get("/api/value", headers = mapOf("X-Request" to "request")))

        val request = transport.singleRequest()
        assertEquals(BrowserHttpMethod.GET, request.method)
        assertEquals("text/plain", request.headers.valueIgnoringCase("Accept"))
        assertEquals("default", request.headers["X-Default"])
        assertEquals("/api/value", request.headers["X-Provider"])
        assertEquals("request", request.headers["X-Request"])
        assertNull(request.body)
    }

    @Test
    fun `post encodes JSON and authoritative CSRF header overrides caller value`() = runTest {
        val transport = RecordingTransport {
            BrowserHttpResponse(200, "OK", emptyMap(), "{\"value\":7}")
        }
        val client = client(
            transport = transport,
            config = BrowserHttpClientConfig(
                csrfProvider = BrowserCsrfProvider.fixed("trusted-token")
            )
        )

        assertEquals(
            Reply(7),
            client.post("/api/value", Command("run"), mapOf("x-csrf-token" to "caller-token"))
        )

        val request = transport.singleRequest()
        assertEquals("{\"action\":\"run\"}", request.body)
        assertEquals("application/json", request.headers.valueIgnoringCase("Content-Type"))
        assertEquals("trusted-token", request.headers.valueIgnoringCase("X-CSRF-Token"))
        assertEquals(1, request.headers.keys.count { it.equals("X-CSRF-Token", ignoreCase = true) })
    }

    @Test
    fun `only root-relative same-origin request paths are accepted`() = runTest {
        val transport = RecordingTransport { okResponse() }
        val client = client(transport)

        listOf(
            "https://example.test/api",
            "//example.test/api",
            "api/value",
            "/api/value#fragment",
            "/api\\value"
        ).forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) {
                client.execute(BrowserHttpMethod.GET, path)
            }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `request limits are measured as UTF-8 bytes before transport`() = runTest {
        val transport = RecordingTransport { okResponse() }
        val client = client(
            transport,
            BrowserHttpClientConfig(maximumRequestBytes = 3)
        )

        val failure = assertFailsWith<BrowserHttpRequestTooLargeException> {
            client.execute(BrowserHttpMethod.POST, "/api/value", body = "éé")
        }

        assertEquals(4, failure.actualBytes)
        assertEquals(3, failure.maximumBytes)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `response limit is enforced even when a transport violates its contract`() = runTest {
        val transport = RecordingTransport {
            BrowserHttpResponse(200, "OK", emptyMap(), "éé")
        }
        val client = client(
            transport,
            BrowserHttpClientConfig(maximumResponseBytes = 3)
        )

        val failure = assertFailsWith<BrowserHttpResponseTooLargeException> {
            client.execute(BrowserHttpMethod.GET, "/api/value")
        }

        assertEquals(4, failure.actualBytes)
        assertEquals(3, failure.maximumBytes)
    }

    @Test
    fun `non-success nested error is normalized into typed exception`() = runTest {
        val transport = RecordingTransport {
            BrowserHttpResponse(
                422,
                "Unprocessable Content",
                mapOf("x-request-id" to "header-request"),
                """
                    {
                      "error": {
                        "code": "invalid_source",
                        "message": "Source is invalid",
                        "details": {"field": "source"}
                      },
                      "requestId": "request-1"
                    }
                """.trimIndent()
            )
        }
        val client = client(transport)

        val failure = assertFailsWith<BrowserHttpResponseException> {
            client.get<Reply>("/api/value")
        }

        assertEquals(422, failure.statusCode)
        assertEquals("invalid_source", failure.error.code)
        assertEquals("Source is invalid", failure.error.message)
        assertEquals("request-1", failure.error.requestId)
        val details = assertIs<JsonObject>(failure.error.details)
        assertEquals("source", details.getValue("field").jsonPrimitive.content)
    }

    @Test
    fun `request ID falls back to a case-insensitive response header`() = runTest {
        val transport = RecordingTransport {
            BrowserHttpResponse(
                500,
                "Internal Server Error",
                mapOf("X-Request-ID" to "request-from-header"),
                "{}"
            )
        }

        val failure = assertFailsWith<BrowserHttpResponseException> {
            client(transport).get<Reply>("/api/value")
        }

        assertEquals("request-from-header", failure.error.requestId)
    }

    @Test
    fun `simple string error envelope is retained`() = runTest {
        val transport = RecordingTransport {
            BrowserHttpResponse(400, "Bad Request", emptyMap(), "{\"error\":\"No code provided\"}")
        }
        val failure = assertFailsWith<BrowserHttpResponseException> {
            client(transport).get<Reply>("/api/value")
        }

        assertEquals("No code provided", failure.error.message)
        assertNull(failure.error.code)
    }

    @Test
    fun `invalid success payload raises decode exception without exposing body in message`() = runTest {
        val transport = RecordingTransport {
            BrowserHttpResponse(200, "OK", emptyMap(), "not-json-sensitive-value")
        }

        val failure = assertFailsWith<BrowserHttpDecodeException> {
            client(transport).get<Reply>("/api/value")
        }

        assertEquals(200, failure.statusCode)
        assertTrue("not-json-sensitive-value" !in failure.message.orEmpty())
    }

    @Test
    fun `redirect timeout and response bounds reach the platform transport`() = runTest {
        val transport = RecordingTransport { okResponse() }
        val client = client(
            transport,
            BrowserHttpClientConfig(
                redirectPolicy = BrowserRedirectPolicy.MANUAL,
                timeoutMillis = 1_234,
                maximumResponseBytes = 5_678
            )
        )

        client.execute(BrowserHttpMethod.GET, "/api/value")

        val request = transport.singleRequest()
        assertEquals(BrowserRedirectPolicy.MANUAL, request.redirectPolicy)
        assertEquals(1_234, request.timeoutMillis)
        assertEquals(5_678, request.maximumResponseBytes)
    }

    @Test
    fun `provider header delimiters are rejected before fetch`() = runTest {
        val transport = RecordingTransport { okResponse() }
        val client = client(
            transport,
            BrowserHttpClientConfig(
                headersProvider = BrowserHeadersProvider { mapOf("X-Test" to "value\r\ninjected") }
            )
        )

        assertFailsWith<IllegalArgumentException> {
            client.execute(BrowserHttpMethod.GET, "/api/value")
        }
        assertTrue(transport.requests.isEmpty())
    }

    private fun client(
        transport: BrowserHttpTransport,
        config: BrowserHttpClientConfig = BrowserHttpClientConfig()
    ): BrowserHttpClient = BrowserHttpClient(config, kotlinx.serialization.json.Json, transport)

    private fun okResponse(): BrowserHttpResponse = BrowserHttpResponse(200, "OK", emptyMap(), "{}")

    @Serializable
    private data class Reply(val value: Int)

    @Serializable
    private data class Command(val action: String)

    private class RecordingTransport(
        private val response: suspend (BrowserTransportRequest) -> BrowserHttpResponse
    ) : BrowserHttpTransport {
        val requests = mutableListOf<BrowserTransportRequest>()

        override suspend fun execute(request: BrowserTransportRequest): BrowserHttpResponse {
            requests += request
            return response(request)
        }

        fun singleRequest(): BrowserTransportRequest {
            assertEquals(1, requests.size)
            return requests.single()
        }
    }
}

private fun Map<String, String>.valueIgnoringCase(name: String): String? =
    entries.singleOrNull { it.key.equals(name, ignoreCase = true) }?.value
