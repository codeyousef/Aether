package codes.yousef.aether.core

import kotlinx.coroutines.*
import org.junit.jupiter.api.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for the aetherStart functionality in aether-core.
 * Tests the basic AetherStartBuilder DSL and server lifecycle.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AetherStartTest {

    private val httpClient = HttpClient.newHttpClient()

    @Test
    fun `test AetherConfig default values`() {
        val config = AetherConfig()

        assertEquals("0.0.0.0", config.host)
        assertEquals(8080, config.port)
        assertEquals(true, config.showBanner)
        assertEquals(true, config.compressionSupported)
        assertEquals(true, config.decompressionSupported)
    }

    @Test
    fun `test AetherConfig custom values`() {
        val config = AetherConfig(
            host = "127.0.0.1",
            port = 3000,
            showBanner = false,
            compressionSupported = false
        )

        assertEquals("127.0.0.1", config.host)
        assertEquals(3000, config.port)
        assertEquals(false, config.showBanner)
        assertEquals(false, config.compressionSupported)
    }

    @Test
    fun `test AetherStartBuilder configuration`() {
        val builder = AetherStartBuilder()

        builder.port(9000)
        builder.host("localhost")
        builder.banner(false)
        builder.compression(false)

        assertEquals(9000, builder.config.port)
        assertEquals("localhost", builder.config.host)
        assertEquals(false, builder.showBanner)
        assertEquals(false, builder.config.compressionSupported)
    }

    @Test
    fun `test AetherStartBuilder pipeline configuration`() {
        val builder = AetherStartBuilder()
        var pipelineConfigured = false

        builder.pipeline {
            pipelineConfigured = true
        }

        assertTrue(pipelineConfigured, "Pipeline should be configured")
    }

    @Test
    fun `test AetherStartBuilder handler configuration`() {
        val builder = AetherStartBuilder()
        var handlerSet = false

        builder.handler {
            handlerSet = true
        }

        // Handler is stored but not invoked during configuration
        assertTrue(!handlerSet, "Handler should not be invoked during configuration")
    }

    @Test
    @Timeout(10)
    fun `test server starts and responds to requests`() {
        val testPort = 18080
        var serverJob: Job? = null

        try {
            // Start server in background
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val builder = AetherStartBuilder().apply {
                    port(testPort)
                    banner(false)
                    handler { exchange ->
                        exchange.respond(200, "Hello from AetherStart!")
                    }
                }

                val server = builder.build()
                server.start()

                // Keep server running
                delay(5000)
                server.stop()
            }

            // Wait for server to start
            Thread.sleep(1000)

            // Make request
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/"))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertEquals("Hello from AetherStart!", response.body())
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test server with pipeline middleware`() {
        val testPort = 18081
        var serverJob: Job? = null
        var middlewareExecuted = false

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val builder = AetherStartBuilder().apply {
                    port(testPort)
                    banner(false)
                    pipeline {
                        use { exchange, next ->
                            middlewareExecuted = true
                            exchange.response.setHeader("X-Middleware", "executed")
                            next()
                        }
                    }
                    handler { exchange ->
                        exchange.respond(200, "With middleware")
                    }
                }

                val server = builder.build()
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/"))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertEquals("executed", response.headers().firstValue("X-Middleware").orElse(""))
            assertTrue(middlewareExecuted, "Middleware should have been executed")
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test server handles POST requests with body`() {
        val testPort = 18082
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val builder = AetherStartBuilder().apply {
                    port(testPort)
                    banner(false)
                    handler { exchange ->
                        val body = exchange.request.bodyBytes().decodeToString()
                        exchange.respond(200, "Received: $body")
                    }
                }

                val server = builder.build()
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/"))
                .POST(HttpRequest.BodyPublishers.ofString("Hello Server"))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertEquals("Received: Hello Server", response.body())
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test server handles different HTTP methods`() {
        val testPort = 18083
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val builder = AetherStartBuilder().apply {
                    port(testPort)
                    banner(false)
                    handler { exchange ->
                        exchange.respond(200, "Method: ${exchange.request.method}")
                    }
                }

                val server = builder.build()
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            // Test GET
            val getRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/"))
                .GET()
                .build()
            val getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals("Method: GET", getResponse.body())

            // Test POST
            val postRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/"))
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build()
            val postResponse = httpClient.send(postRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals("Method: POST", postResponse.body())

            // Test PUT
            val putRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/"))
                .PUT(HttpRequest.BodyPublishers.ofString(""))
                .build()
            val putResponse = httpClient.send(putRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals("Method: PUT", putResponse.body())

            // Test DELETE
            val deleteRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/"))
                .DELETE()
                .build()
            val deleteResponse = httpClient.send(deleteRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals("Method: DELETE", deleteResponse.body())
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test server with custom headers`() {
        val testPort = 18084
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val builder = AetherStartBuilder().apply {
                    port(testPort)
                    banner(false)
                    handler { exchange ->
                        exchange.response.setHeader("X-Custom-Header", "custom-value")
                        exchange.response.setHeader("Content-Type", "text/plain")
                        exchange.respond(200, "Headers set")
                    }
                }

                val server = builder.build()
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/"))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertEquals("custom-value", response.headers().firstValue("X-Custom-Header").orElse(""))
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test server reads request headers`() {
        val testPort = 18085
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val builder = AetherStartBuilder().apply {
                    port(testPort)
                    banner(false)
                    handler { exchange ->
                        val customHeader = exchange.request.headers["X-Test-Header"] ?: "not found"
                        exchange.respond(200, "Header: $customHeader")
                    }
                }

                val server = builder.build()
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/"))
                .header("X-Test-Header", "test-value")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertEquals("Header: test-value", response.body())
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test server handles query parameters`() {
        val testPort = 18086
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val builder = AetherStartBuilder().apply {
                    port(testPort)
                    banner(false)
                    handler { exchange ->
                        val name = exchange.request.queryParameter("name") ?: "unknown"
                        val age = exchange.request.queryParameter("age") ?: "0"
                        exchange.respond(200, "Name: $name, Age: $age")
                    }
                }

                val server = builder.build()
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/?name=Alice&age=30"))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertEquals("Name: Alice, Age: 30", response.body())
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test server handles different status codes`() {
        val testPort = 18087
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val builder = AetherStartBuilder().apply {
                    port(testPort)
                    banner(false)
                    handler { exchange ->
                        val code = exchange.request.queryParameter("code")?.toIntOrNull() ?: 200
                        exchange.respond(code, "Status: $code")
                    }
                }

                val server = builder.build()
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            // Test 200
            val request200 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/?code=200"))
                .GET()
                .build()
            assertEquals(200, httpClient.send(request200, HttpResponse.BodyHandlers.ofString()).statusCode())

            // Test 201
            val request201 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/?code=201"))
                .GET()
                .build()
            assertEquals(201, httpClient.send(request201, HttpResponse.BodyHandlers.ofString()).statusCode())

            // Test 400
            val request400 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/?code=400"))
                .GET()
                .build()
            assertEquals(400, httpClient.send(request400, HttpResponse.BodyHandlers.ofString()).statusCode())

            // Test 404
            val request404 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/?code=404"))
                .GET()
                .build()
            assertEquals(404, httpClient.send(request404, HttpResponse.BodyHandlers.ofString()).statusCode())

            // Test 500
            val request500 = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/?code=500"))
                .GET()
                .build()
            assertEquals(500, httpClient.send(request500, HttpResponse.BodyHandlers.ofString()).statusCode())
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test multiple concurrent requests`() {
        val testPort = 18088
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val builder = AetherStartBuilder().apply {
                    port(testPort)
                    banner(false)
                    handler { exchange ->
                        val id = exchange.request.queryParameter("id") ?: "0"
                        // Simulate some work
                        delay(50)
                        exchange.respond(200, "Request $id processed")
                    }
                }

                val server = builder.build()
                server.start()
                delay(10000)
                server.stop()
            }

            Thread.sleep(1000)

            // Send 10 concurrent requests
            val responses = runBlocking {
                (1..10).map { id ->
                    async(Dispatchers.IO) {
                        val request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:$testPort/?id=$id"))
                            .GET()
                            .build()
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                    }
                }.awaitAll()
            }

            // All requests should succeed
            responses.forEachIndexed { index, response ->
                assertEquals(200, response.statusCode(), "Request ${index + 1} should succeed")
                assertTrue(response.body().contains("processed"), "Response should contain 'processed'")
            }
        } finally {
            serverJob?.cancel()
        }
    }
}

