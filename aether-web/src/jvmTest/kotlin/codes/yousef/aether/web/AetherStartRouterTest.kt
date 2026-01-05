package codes.yousef.aether.web

import codes.yousef.aether.core.pipeline.Pipeline
import kotlinx.coroutines.*
import org.junit.jupiter.api.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for the router-based aetherStart functionality.
 * Tests the Router DSL integration with the server startup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AetherStartRouterTest {

    private val httpClient = HttpClient.newHttpClient()

    @Test
    @Timeout(10)
    fun `test router-based aetherStart with GET route`() {
        val testPort = 19080
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    get("/") { exchange ->
                        exchange.respond(200, "Hello from Router!")
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
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
            assertEquals("Hello from Router!", response.body())
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test router with multiple routes`() {
        val testPort = 19081
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    get("/") { exchange ->
                        exchange.respond(200, "Home")
                    }
                    get("/users") { exchange ->
                        exchange.respond(200, "Users list")
                    }
                    get("/api/health") { exchange ->
                        exchange.respond(200, """{"status": "healthy"}""")
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            // Test home route
            val homeRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/"))
                .GET()
                .build()
            val homeResponse = httpClient.send(homeRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, homeResponse.statusCode())
            assertEquals("Home", homeResponse.body())

            // Test users route
            val usersRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/users"))
                .GET()
                .build()
            val usersResponse = httpClient.send(usersRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, usersResponse.statusCode())
            assertEquals("Users list", usersResponse.body())

            // Test API health route
            val healthRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/api/health"))
                .GET()
                .build()
            val healthResponse = httpClient.send(healthRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, healthResponse.statusCode())
            assertTrue(healthResponse.body().contains("healthy"))
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test router with path parameters`() {
        val testPort = 19082
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    get("/users/:id") { exchange ->
                        val id = exchange.pathParam("id")
                        exchange.respond(200, "User ID: $id")
                    }
                    get("/posts/:postId/comments/:commentId") { exchange ->
                        val postId = exchange.pathParam("postId")
                        val commentId = exchange.pathParam("commentId")
                        exchange.respond(200, "Post: $postId, Comment: $commentId")
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            // Test single path param
            val userRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/users/123"))
                .GET()
                .build()
            val userResponse = httpClient.send(userRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, userResponse.statusCode())
            assertEquals("User ID: 123", userResponse.body())

            // Test multiple path params
            val commentRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/posts/456/comments/789"))
                .GET()
                .build()
            val commentResponse = httpClient.send(commentRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, commentResponse.statusCode())
            assertEquals("Post: 456, Comment: 789", commentResponse.body())
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test router with different HTTP methods`() {
        val testPort = 19083
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    get("/resource") { exchange ->
                        exchange.respond(200, "GET resource")
                    }
                    post("/resource") { exchange ->
                        exchange.respond(201, "POST resource")
                    }
                    put("/resource") { exchange ->
                        exchange.respond(200, "PUT resource")
                    }
                    delete("/resource") { exchange ->
                        exchange.respond(204, "")
                    }
                    patch("/resource") { exchange ->
                        exchange.respond(200, "PATCH resource")
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            // Test GET
            val getRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/resource"))
                .GET()
                .build()
            assertEquals(200, httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString()).statusCode())

            // Test POST
            val postRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/resource"))
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build()
            assertEquals(201, httpClient.send(postRequest, HttpResponse.BodyHandlers.ofString()).statusCode())

            // Test PUT
            val putRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/resource"))
                .PUT(HttpRequest.BodyPublishers.ofString(""))
                .build()
            assertEquals(200, httpClient.send(putRequest, HttpResponse.BodyHandlers.ofString()).statusCode())

            // Test DELETE
            val deleteRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/resource"))
                .DELETE()
                .build()
            assertEquals(204, httpClient.send(deleteRequest, HttpResponse.BodyHandlers.ofString()).statusCode())
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test router returns 404 for unknown routes`() {
        val testPort = 19084
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    get("/") { exchange ->
                        exchange.respond(200, "Home")
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/nonexistent"))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(404, response.statusCode())
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test router with request body parsing`() {
        val testPort = 19085
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    post("/echo") { exchange ->
                        val body = exchange.request.bodyBytes().decodeToString()
                        exchange.respond(200, "Echo: $body")
                    }
                    post("/json") { exchange ->
                        val body = exchange.request.bodyBytes().decodeToString()
                        exchange.response.setHeader("Content-Type", "application/json")
                        exchange.respond(200, """{"received": "$body"}""")
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            // Test echo
            val echoRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("Hello World"))
                .build()
            val echoResponse = httpClient.send(echoRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, echoResponse.statusCode())
            assertEquals("Echo: Hello World", echoResponse.body())

            // Test JSON
            val jsonRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/json"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("test data"))
                .build()
            val jsonResponse = httpClient.send(jsonRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, jsonResponse.statusCode())
            assertTrue(jsonResponse.body().contains("test data"))
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test router with query parameters`() {
        val testPort = 19086
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    get("/search") { exchange ->
                        val query = exchange.request.queryParameter("q") ?: ""
                        val page = exchange.request.queryParameter("page") ?: "1"
                        val limit = exchange.request.queryParameter("limit") ?: "10"
                        exchange.respond(200, "Search: q=$query, page=$page, limit=$limit")
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/search?q=kotlin&page=2&limit=20"))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertEquals("Search: q=kotlin, page=2, limit=20", response.body())
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test router with middleware pipeline`() {
        val testPort = 19087
        var serverJob: Job? = null
        var middlewareCount = 0

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    get("/") { exchange ->
                        exchange.respond(200, "OK")
                    }
                }

                val pipeline = Pipeline().apply {
                    use { exchange, next ->
                        middlewareCount++
                        exchange.response.setHeader("X-Middleware-1", "executed")
                        next()
                    }
                    use { exchange, next ->
                        middlewareCount++
                        exchange.response.setHeader("X-Middleware-2", "executed")
                        next()
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, pipeline)
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
            assertEquals("executed", response.headers().firstValue("X-Middleware-1").orElse(""))
            assertEquals("executed", response.headers().firstValue("X-Middleware-2").orElse(""))
            assertEquals(2, middlewareCount)
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test router with nested paths`() {
        val testPort = 19088
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    // Define nested routes manually
                    get("/api/v1/users") { exchange ->
                        exchange.respond(200, "API v1 Users")
                    }
                    get("/api/v1/posts") { exchange ->
                        exchange.respond(200, "API v1 Posts")
                    }
                    get("/api/v2/users") { exchange ->
                        exchange.respond(200, "API v2 Users")
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            // Test v1 users
            val v1UsersRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/api/v1/users"))
                .GET()
                .build()
            val v1UsersResponse = httpClient.send(v1UsersRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, v1UsersResponse.statusCode())
            assertEquals("API v1 Users", v1UsersResponse.body())

            // Test v1 posts
            val v1PostsRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/api/v1/posts"))
                .GET()
                .build()
            val v1PostsResponse = httpClient.send(v1PostsRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, v1PostsResponse.statusCode())
            assertEquals("API v1 Posts", v1PostsResponse.body())

            // Test v2 users
            val v2UsersRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/api/v2/users"))
                .GET()
                .build()
            val v2UsersResponse = httpClient.send(v2UsersRequest, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, v2UsersResponse.statusCode())
            assertEquals("API v2 Users", v2UsersResponse.body())
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test router handles concurrent requests`() {
        val testPort = 19089
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    get("/slow/:id") { exchange ->
                        val id = exchange.pathParam("id")
                        delay(100) // Simulate work
                        exchange.respond(200, "Completed $id")
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(10000)
                server.stop()
            }

            Thread.sleep(1000)

            // Send 20 concurrent requests
            val responses = runBlocking {
                (1..20).map { id ->
                    async(Dispatchers.IO) {
                        val request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:$testPort/slow/$id"))
                            .GET()
                            .build()
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                    }
                }.awaitAll()
            }

            // All should succeed
            responses.forEachIndexed { index, response ->
                assertEquals(200, response.statusCode(), "Request ${index + 1} should succeed")
                assertTrue(response.body().startsWith("Completed"), "Response should start with 'Completed'")
            }
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(10)
    fun `test AetherServerConfig default values`() {
        val config = AetherServerConfig()

        assertEquals("0.0.0.0", config.host)
        assertEquals(8080, config.port)
        assertEquals(true, config.compressionSupported)
        assertEquals(true, config.decompressionSupported)
    }

    @Test
    @Timeout(10)
    fun `test router with response headers`() {
        val testPort = 19090
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    get("/api/data") { exchange ->
                        exchange.response.setHeader("Content-Type", "application/json")
                        exchange.response.setHeader("X-Request-Id", "12345")
                        exchange.response.setHeader("Cache-Control", "no-cache")
                        exchange.respond(200, """{"data": "test"}""")
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(5000)
                server.stop()
            }

            Thread.sleep(1000)

            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/api/data"))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            assertEquals(200, response.statusCode())
            assertEquals("12345", response.headers().firstValue("X-Request-Id").orElse(""))
            assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(""))
        } finally {
            serverJob?.cancel()
        }
    }
}

