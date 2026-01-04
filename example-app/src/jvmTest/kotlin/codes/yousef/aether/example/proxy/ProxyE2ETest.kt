package codes.yousef.aether.example.proxy

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.core.pipeline.installRecovery
import codes.yousef.aether.core.proxy.*
import codes.yousef.aether.web.router
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end tests for the HTTP Proxy Middleware.
 * Tests all requirements:
 * - Request forwarding (method, headers, body, query params)
 * - Response handling (status, headers, streaming)
 * - Error handling (timeout, connection failure, circuit breaker)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProxyE2ETest {

    private lateinit var proxyServer: VertxServer
    private lateinit var upstreamServer: HttpServer
    private lateinit var vertx: Vertx
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    
    private val proxyPort = 8082
    private val upstreamPort = 8083
    private val proxyUrl = "http://localhost:$proxyPort"
    private val upstreamUrl = "http://localhost:$upstreamPort"
    
    // Request tracking for verification
    private val receivedMethod = AtomicInteger(0)
    private val receivedHeaders = mutableMapOf<String, String>()
    private val receivedBody = StringBuilder()
    private val receivedQuery = StringBuilder()

    @BeforeAll
    fun setup() = runBlocking(AetherDispatcher.dispatcher) {
        vertx = Vertx.vertx()
        
        // Start the mock upstream server
        startUpstreamServer()
        
        // Start the proxy server
        startProxyServer()
        
        // Wait for servers to be ready
        delay(500)
    }

    @AfterAll
    fun teardown() = runBlocking(AetherDispatcher.dispatcher) {
        if (::proxyServer.isInitialized) {
            proxyServer.stop()
        }
        if (::upstreamServer.isInitialized) {
            upstreamServer.close().coAwait()
        }
        if (::vertx.isInitialized) {
            vertx.close().coAwait()
        }
    }
    
    @BeforeEach
    fun resetTracking() {
        receivedHeaders.clear()
        receivedBody.clear()
        receivedQuery.clear()
        CircuitBreakerRegistry.clear()
    }

    private suspend fun startUpstreamServer() {
        upstreamServer = vertx.createHttpServer(HttpServerOptions().setPort(upstreamPort))
            .requestHandler { request ->
                // Track received request
                request.headers().forEach { entry ->
                    receivedHeaders[entry.key] = entry.value
                }
                receivedQuery.append(request.query() ?: "")
                
                val path = request.path()
                val response = request.response()
                
                // Handle body for all requests that might have one
                request.body().onComplete { bodyResult ->
                    val body = if (bodyResult.succeeded()) bodyResult.result()?.toString() ?: "" else ""
                    receivedBody.append(body)
                    
                    when {
                        // Echo endpoint - returns request info
                        path == "/echo" -> {
                            response.statusCode = 200
                            response.putHeader("Content-Type", "application/json")
                            response.putHeader("X-Upstream-Response", "true")
                            response.end("""
                                {
                                    "method": "${request.method().name()}",
                                    "path": "${request.path()}",
                                    "query": "${request.query() ?: ""}",
                                    "body": "${body.replace("\"", "\\\"")}"
                                }
                            """.trimIndent())
                        }
                        
                        // SSE streaming endpoint
                        path == "/chat/completions" || path.contains("stream") -> {
                            response.statusCode = 200
                            response.putHeader("Content-Type", "text/event-stream")
                            response.putHeader("Cache-Control", "no-cache")
                            response.isChunked = true
                            
                            // Send SSE events
                            val events = listOf(
                                "data: {\"id\":\"1\",\"content\":\"Hello\"}\n\n",
                                "data: {\"id\":\"2\",\"content\":\" World\"}\n\n",
                                "data: {\"id\":\"3\",\"content\":\"!\"}\n\n",
                                "data: [DONE]\n\n"
                            )
                            
                            var index = 0
                            vertx.setPeriodic(50) { timerId ->
                                if (index < events.size) {
                                    response.write(events[index])
                                    index++
                                } else {
                                    vertx.cancelTimer(timerId)
                                    response.end()
                                }
                            }
                        }
                        
                        // Slow endpoint for timeout testing
                        path == "/slow" -> {
                            // Delay response by 5 seconds
                            vertx.setTimer(5000) {
                                response.statusCode = 200
                                response.end("Slow response")
                            }
                        }
                        
                        // Error endpoint
                        path == "/error" -> {
                            response.statusCode = 500
                            response.end("Internal Server Error")
                        }
                        
                        // Not found
                        else -> {
                            response.statusCode = 404
                            response.end("Not Found")
                        }
                    }
                }
            }
            .listen()
            .coAwait()
    }

    private suspend fun startProxyServer() {
        val router = router {
            // Direct proxy endpoint using exchange.proxyTo()
            post("/api/proxy/echo") { exchange ->
                exchange.proxyTo("$upstreamUrl/echo") {
                    // Add custom header
                    header("X-Proxy-Request", "true")
                    timeout(30.seconds)
                }
            }
            
            // SSE streaming proxy
            post("/api/proxy/chat") { exchange ->
                exchange.proxyTo("$upstreamUrl/chat/completions") {
                    header("X-Proxy-Request", "true")
                }
            }
            
            // Proxy with header modification
            post("/api/proxy/auth") { exchange ->
                exchange.proxyTo("$upstreamUrl/echo") {
                    // Replace user's auth with service key
                    removeHeader("Authorization")
                    bearerToken("service-api-key-12345")
                }
            }
            
            // Timeout test endpoint
            get("/api/proxy/slow") { exchange ->
                exchange.proxyTo(
                    "$upstreamUrl/slow",
                    config = ProxyConfig(requestTimeout = 1.seconds)
                )
            }
            
            // Path rewriting proxy
            get("/v1/*") { exchange ->
                exchange.proxyTo(upstreamUrl) {
                    rewritePath { path -> path.removePrefix("/v1") }
                }
            }
            
            // Query parameter passthrough
            get("/api/proxy/query") { exchange ->
                exchange.proxyTo("$upstreamUrl/echo")
            }
            
            // Manual proxy response handling
            post("/api/proxy/inspect") { exchange ->
                val response = exchange.proxyRequest("$upstreamUrl/echo")
                
                // Log the response before forwarding
                println("Upstream responded with status: ${response.statusCode}")
                
                // Forward the response
                response.forward(exchange)
            }
        }
        
        val pipeline = Pipeline().apply {
            installRecovery {
                handleProxyExceptions()
            }
            use(router.asMiddleware())
        }
        
        val config = VertxServerConfig(port = proxyPort)
        proxyServer = VertxServer(config, pipeline) { exchange ->
            exchange.notFound("Not Found")
        }
        proxyServer.start()
    }

    // ============== Request Forwarding Tests ==============

    @Test
    fun `test method passthrough - POST`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/api/proxy/echo"))
            .POST(HttpRequest.BodyPublishers.ofString("""{"test":"data"}"""))
            .header("Content-Type", "application/json")
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode(), "Should return 200")
        assertTrue(response.body().contains("\"method\": \"POST\""), "Should forward POST method")
    }

    @Test
    fun `test header passthrough and modification`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/api/proxy/auth"))
            .POST(HttpRequest.BodyPublishers.ofString("test body"))
            .header("Content-Type", "text/plain")
            .header("Authorization", "Bearer user-token-should-be-removed")
            .header("X-Custom-Header", "custom-value")
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
        
        // Verify custom header was forwarded (case-insensitive check)
        val customHeaderKey = receivedHeaders.keys.find { it.equals("X-Custom-Header", ignoreCase = true) }
        assertTrue(customHeaderKey != null, "Custom header should be forwarded. Received headers: ${receivedHeaders.keys}")
        assertEquals("custom-value", receivedHeaders[customHeaderKey])
        
        // Verify Authorization was replaced (case-insensitive check)
        val authHeaderKey = receivedHeaders.keys.find { it.equals("Authorization", ignoreCase = true) }
        assertTrue(authHeaderKey != null, "Authorization header should be present")
        assertEquals("Bearer service-api-key-12345", receivedHeaders[authHeaderKey], 
            "Authorization should be replaced with service key")
    }

    @Test
    fun `test body passthrough`() {
        val testBody = """{"message":"Hello, proxy!","count":42}"""
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/api/proxy/echo"))
            .POST(HttpRequest.BodyPublishers.ofString(testBody))
            .header("Content-Type", "application/json")
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
        assertTrue(receivedBody.toString().contains("Hello, proxy!"), "Body should be forwarded")
    }

    @Test
    fun `test query parameter passthrough`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/api/proxy/query?foo=bar&baz=qux&array=1&array=2"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
        assertTrue(receivedQuery.toString().contains("foo=bar"), "Query params should be forwarded")
        assertTrue(receivedQuery.toString().contains("baz=qux"), "All query params should be forwarded")
    }

    // ============== Response Handling Tests ==============

    @Test
    fun `test status code passthrough`() {
        // Test 200
        val okRequest = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/api/proxy/echo"))
            .POST(HttpRequest.BodyPublishers.ofString("test"))
            .build()
        val okResponse = httpClient.send(okRequest, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, okResponse.statusCode())
        
        // Test 404 via path rewrite
        val notFoundRequest = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/v1/nonexistent"))
            .GET()
            .build()
        val notFoundResponse = httpClient.send(notFoundRequest, HttpResponse.BodyHandlers.ofString())
        assertEquals(404, notFoundResponse.statusCode())
    }

    @Test
    fun `test response header passthrough`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/api/proxy/echo"))
            .POST(HttpRequest.BodyPublishers.ofString("test"))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
        
        // Verify upstream response headers are passed through
        val upstreamHeader = response.headers().firstValue("X-Upstream-Response")
        assertTrue(upstreamHeader.isPresent, "Upstream headers should be passed through")
        assertEquals("true", upstreamHeader.get())
    }

    @Test
    fun `test SSE streaming response`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/api/proxy/chat"))
            .POST(HttpRequest.BodyPublishers.ofString("""{"prompt":"test"}"""))
            .header("Content-Type", "application/json")
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
        
        // Verify SSE content type
        val contentType = response.headers().firstValue("Content-Type")
        assertTrue(contentType.isPresent, "Content-Type header should be present")
        assertTrue(contentType.get().contains("text/event-stream"), "Should be SSE content type")
        
        // Verify all SSE events were received
        val body = response.body()
        assertTrue(body.contains("data: {\"id\":\"1\""), "Should contain first SSE event")
        assertTrue(body.contains("data: {\"id\":\"2\""), "Should contain second SSE event")
        assertTrue(body.contains("data: {\"id\":\"3\""), "Should contain third SSE event")
        assertTrue(body.contains("data: [DONE]"), "Should contain DONE event")
    }

    // ============== Error Handling Tests ==============

    @Test
    fun `test timeout returns 504 Gateway Timeout`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/api/proxy/slow"))
            .GET()
            .timeout(Duration.ofSeconds(10))  // Client timeout longer than proxy timeout
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body()
        
        println("Timeout test response status: ${response.statusCode()}")
        println("Timeout test response body: $body")
        
        assertEquals(504, response.statusCode(), "Should return 504 Gateway Timeout. Body: $body")
        assertTrue(body.contains("Gateway Timeout") || body.contains("Timeout"), "Should indicate timeout. Body: $body")
    }

    @Test
    fun `test connection failure returns 502 Bad Gateway`() = runBlocking(AetherDispatcher.dispatcher) {
        // Create a temporary router that proxies to a non-existent server
        val router = router {
            get("/bad-upstream") { exchange ->
                exchange.proxyTo(
                    "http://localhost:19999/nonexistent",
                    config = ProxyConfig(connectTimeout = 1.seconds)
                )
            }
        }
        
        val pipeline = Pipeline().apply {
            installRecovery {
                handleProxyExceptions()
            }
            use(router.asMiddleware())
        }
        
        // Start a temporary server for this test
        val tempServer = VertxServer(VertxServerConfig(port = 8084), pipeline) { exchange ->
            exchange.notFound("Not Found")
        }
        tempServer.start()
        delay(300)
        
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8084/bad-upstream"))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            assertEquals(502, response.statusCode(), "Should return 502 Bad Gateway")
            assertTrue(response.body().contains("Bad Gateway"), "Should indicate connection failure")
        } finally {
            tempServer.stop()
        }
    }

    // ============== Path Rewriting Tests ==============

    @Test
    fun `test path rewriting`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/v1/echo"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
        // The path should be rewritten from /v1/echo to /echo
        assertTrue(response.body().contains("\"path\": \"/echo\""), "Path should be rewritten")
    }

    // ============== Manual Response Inspection Tests ==============

    @Test
    fun `test manual response inspection before forwarding`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/api/proxy/inspect"))
            .POST(HttpRequest.BodyPublishers.ofString("test body"))
            .header("Content-Type", "text/plain")
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
        // Response should be forwarded after inspection
        assertTrue(response.body().contains("\"method\": \"POST\""))
    }
}
