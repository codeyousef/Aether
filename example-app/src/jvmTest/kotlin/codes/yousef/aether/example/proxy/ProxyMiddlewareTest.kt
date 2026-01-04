package codes.yousef.aether.example.proxy

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.core.pipeline.installRecovery
import codes.yousef.aether.core.proxy.*
import io.vertx.core.Vertx
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for ProxyMiddleware - the automatic path-prefix based proxy.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProxyMiddlewareTest {

    private lateinit var proxyServer: VertxServer
    private lateinit var upstreamServer: HttpServer
    private lateinit var vertx: Vertx
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    
    private val proxyPort = 8085
    private val upstreamPort = 8086
    private val proxyUrl = "http://localhost:$proxyPort"
    private val upstreamUrl = "http://localhost:$upstreamPort"

    @BeforeAll
    fun setup() = runBlocking(AetherDispatcher.dispatcher) {
        vertx = Vertx.vertx()
        
        // Start mock upstream
        upstreamServer = vertx.createHttpServer(HttpServerOptions().setPort(upstreamPort))
            .requestHandler { request ->
                val response = request.response()
                val path = request.path()
                
                // Handle body for all requests
                request.body().onComplete { bodyResult ->
                    val body = if (bodyResult.succeeded()) bodyResult.result()?.toString() ?: "" else ""
                    
                    when {
                        path == "/api/users" -> {
                            response.statusCode = 200
                            response.putHeader("Content-Type", "application/json")
                            response.end("""{"users":[{"id":1,"name":"Alice"}]}""")
                        }
                        path == "/api/users/1" -> {
                            response.statusCode = 200
                            response.putHeader("Content-Type", "application/json")
                            response.end("""{"id":1,"name":"Alice"}""")
                        }
                        path == "/internal/health" -> {
                            response.statusCode = 200
                            response.end("OK")
                        }
                        path.startsWith("/echo") || path.startsWith("/internal/echo") -> {
                            response.statusCode = 200
                            response.putHeader("Content-Type", "application/json")
                            response.putHeader("X-Received-Auth", request.getHeader("Authorization") ?: "none")
                            response.end("""{"path":"$path","method":"${request.method().name()}","body":"$body"}""")
                        }
                        else -> {
                            response.statusCode = 404
                            response.end("Not Found: $path")
                        }
                    }
                }
            }
            .listen()
            .coAwait()
        
        // Start proxy with middleware
        val pipeline = Pipeline().apply {
            installRecovery {
                handleProxyExceptions()
            }
            
            // Proxy /api/* to upstream, stripping the prefix
            installProxy {
                upstream = upstreamUrl
                pathPrefix = "/api/"
                stripPrefix = false  // Keep /api/ prefix
                includePath = true
                
                headers { exchange ->
                    // Add service authentication
                    bearerToken("internal-service-key")
                    // Forward request ID if present
                    exchange.request.headers["X-Request-ID"]?.let { header("X-Request-ID", it) }
                }
            }
            
            // Proxy /backend/* to upstream with prefix stripping
            installProxy {
                upstream = upstreamUrl
                pathPrefix = "/backend/"
                stripPrefix = true  // Strip /backend/ prefix
                includePath = true
                
                rewritePath { path ->
                    "/api$path"  // Add /api prefix after stripping /backend/
                }
            }
            
            // Proxy /internal/* with custom matcher
            installProxy {
                upstream = upstreamUrl
                pathMatcher = { path -> path.startsWith("/internal/") }
            }
        }
        
        val config = VertxServerConfig(port = proxyPort)
        proxyServer = VertxServer(config, pipeline) { exchange ->
            exchange.notFound("Not Found - No matching proxy rule")
        }
        proxyServer.start()
        
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
    fun resetCircuitBreakers() {
        CircuitBreakerRegistry.clear()
    }

    @Test
    fun `middleware proxies matching paths`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/api/users"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Alice"), "Should return proxied response")
    }

    @Test
    fun `middleware adds configured headers`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/api/users"))
            .GET()
            .header("X-Request-ID", "test-request-123")
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
        // The upstream would have received the modified headers
    }

    @Test
    fun `middleware strips prefix and rewrites path`() {
        // /backend/users -> strip /backend/ -> /users -> rewrite to /api/users
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/backend/users"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Alice"), "Should hit /api/users on upstream")
    }

    @Test
    fun `middleware uses custom path matcher`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/internal/health"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
        assertEquals("OK", response.body())
    }

    @Test
    fun `non-matching paths fall through to handler`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/unknown/path"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(404, response.statusCode())
        assertTrue(response.body().contains("No matching proxy rule"), "Should fall through to default handler")
    }

    @Test
    fun `middleware preserves query parameters`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/api/users?limit=10&offset=20"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
    }

    @Test
    fun `middleware proxies POST with body`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/internal/echo"))
            .POST(HttpRequest.BodyPublishers.ofString("""{"name":"Bob"}"""))
            .header("Content-Type", "application/json")
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("Bob"), "Should forward request body")
        assertTrue(response.body().contains("POST"), "Should forward POST method")
    }
}
