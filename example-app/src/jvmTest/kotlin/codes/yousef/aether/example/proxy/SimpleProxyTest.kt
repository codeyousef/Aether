package codes.yousef.aether.example.proxy

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.core.pipeline.installRecovery
import codes.yousef.aether.core.proxy.*
import codes.yousef.aether.web.router
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
 * Simple proxy test to debug issues.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimpleProxyTest {

    private lateinit var proxyServer: VertxServer
    private lateinit var upstreamServer: HttpServer
    private lateinit var vertx: Vertx
    
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    
    private val proxyPort = 8090
    private val upstreamPort = 8091
    private val proxyUrl = "http://localhost:$proxyPort"
    private val upstreamUrl = "http://localhost:$upstreamPort"

    @BeforeAll
    fun setup() = runBlocking(AetherDispatcher.dispatcher) {
        vertx = Vertx.vertx()
        
        // Start simple upstream
        upstreamServer = vertx.createHttpServer(HttpServerOptions().setPort(upstreamPort))
            .requestHandler { request ->
                val response = request.response()
                response.statusCode = 200
                response.putHeader("Content-Type", "text/plain")
                response.putHeader("X-Custom", "upstream-header")
                response.end("Hello from upstream! Path: ${request.path()}")
            }
            .listen()
            .coAwait()
        
        println("Upstream server started on port $upstreamPort")
        
        // Start simple proxy server
        val router = router {
            get("/proxy/*") { exchange ->
                println("Proxy handler called for: ${exchange.request.path}")
                try {
                    exchange.proxyTo(upstreamUrl) {
                        rewritePath { path -> path.removePrefix("/proxy") }
                    }
                    println("Proxy completed successfully")
                } catch (e: Exception) {
                    println("Proxy error: ${e::class.simpleName}: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }
        
        val pipeline = Pipeline().apply {
            installRecovery {
                handleAll { exchange, throwable ->
                    println("Recovery caught: ${throwable::class.simpleName}: ${throwable.message}")
                    throwable.printStackTrace()
                    val statusCode = when (throwable) {
                        is IllegalArgumentException -> 400
                        is IllegalStateException -> 409
                        is NoSuchElementException -> 404
                        is UnsupportedOperationException -> 501
                        is ProxyConnectionException -> 502
                        is ProxyTimeoutException -> 504
                        is ProxyCircuitOpenException -> 503
                        else -> 500
                    }
                    exchange.response.statusCode = statusCode
                    exchange.response.setHeader("Content-Type", "text/plain")
                    exchange.response.write("Error: ${throwable::class.simpleName}: ${throwable.message}")
                    exchange.response.end()
                }
            }
            use(router.asMiddleware())
        }
        
        val config = VertxServerConfig(port = proxyPort)
        proxyServer = VertxServer(config, pipeline) { exchange ->
            exchange.notFound("Not Found")
        }
        proxyServer.start()
        
        println("Proxy server started on port $proxyPort")
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
    fun reset() {
        CircuitBreakerRegistry.clear()
    }

    @Test
    fun `test simple proxy`() {
        // First verify upstream is working
        val upstreamRequest = HttpRequest.newBuilder()
            .uri(URI.create("$upstreamUrl/test"))
            .GET()
            .build()
        val upstreamResponse = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofString())
        println("Direct upstream response: ${upstreamResponse.statusCode()} - ${upstreamResponse.body()}")
        assertEquals(200, upstreamResponse.statusCode())
        
        // Now test through proxy
        val proxyRequest = HttpRequest.newBuilder()
            .uri(URI.create("$proxyUrl/proxy/test"))
            .GET()
            .build()
        val proxyResponse = httpClient.send(proxyRequest, HttpResponse.BodyHandlers.ofString())
        println("Proxy response: ${proxyResponse.statusCode()} - ${proxyResponse.body()}")
        assertEquals(200, proxyResponse.statusCode(), "Proxy should return 200. Body: ${proxyResponse.body()}")
    }
}
