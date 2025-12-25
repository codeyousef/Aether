package codes.yousef.aether.example

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.core.pipeline.installCallLogging
import codes.yousef.aether.core.pipeline.installContentNegotiation
import codes.yousef.aether.core.pipeline.installRecovery
import codes.yousef.aether.db.DatabaseDriverRegistry
import codes.yousef.aether.db.jvm.VertxPgDriver
import codes.yousef.aether.web.router
import codes.yousef.aether.web.pathParam
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive integration tests for the Aether example application.
 * Uses TestContainers for a real PostgreSQL database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegrationTest {

    companion object {
        private val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            withDatabaseName("test_db")
            withUsername("test_user")
            withPassword("test_password")
        }
    }

    private lateinit var server: VertxServer
    private lateinit var driver: VertxPgDriver
    private val httpClient = HttpClient.newHttpClient()
    private val testPort = 8081
    private val baseUrl = "http://localhost:$testPort"

    @BeforeAll
    fun setup() = runBlocking(AetherDispatcher.dispatcher) {
        // Start PostgreSQL container
        try {
            postgres.start()
        } catch (e: Throwable) {
            Assumptions.assumeTrue(false, "Docker is not available: ${e.message}")
        }

        // Initialize database driver
        driver = VertxPgDriver.create(
            host = postgres.host,
            port = postgres.getMappedPort(5432),
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password
        )
        DatabaseDriverRegistry.initialize(driver)

        // Create tables
        Users.createTable()

        // Insert test data
        User.create("alice", "alice@example.com", 30)
        User.create("bob", "bob@example.com", 25)
        User.create("charlie", "charlie@example.com", null)

        // Create router
        val router = router {
            get("/") { exchange ->
                exchange.respond(200, "Welcome to Aether")
            }

            get("/users") { exchange ->
                val users = User.all()
                val html = buildString {
                    append("<html><body><h1>Users</h1><ul>")
                    for (user in users) {
                        append("<li>${user.username}</li>")
                    }
                    append("</ul></body></html>")
                }
                exchange.respondHtml(200, html)
            }

            get("/users/:id") { exchange ->
                val userId = exchange.pathParam("id")?.toLongOrNull()
                if (userId == null) {
                    exchange.badRequest("Invalid ID")
                    return@get
                }

                val user = User.findById(userId)
                if (user == null) {
                    exchange.notFound("User not found")
                    return@get
                }

                exchange.respond(200, "User: ${user.username}")
            }

            get("/api/users") { exchange ->
                val users = User.all()
                val json = Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(UserDto.serializer()),
                    users.map { UserDto(it.id, it.username, it.email, it.age) }
                )
                exchange.response.statusCode = 200
                exchange.response.setHeader("Content-Type", "application/json")
                exchange.response.write(json)
                exchange.response.end()
            }

            post("/api/users") { exchange ->
                val body = exchange.request.bodyBytes().decodeToString()
                val request = Json.decodeFromString<CreateUserRequest>(body)
                
                if (request.username.isNullOrBlank() || request.email.isNullOrBlank()) {
                    exchange.badRequest("Username and email required")
                    return@post
                }

                val user = User.create(request.username, request.email, request.age)
                val json = Json.encodeToString(
                    UserDto.serializer(),
                    UserDto(user.id, user.username, user.email, user.age)
                )
                exchange.response.statusCode = 201
                exchange.response.setHeader("Content-Type", "application/json")
                exchange.response.write(json)
                exchange.response.end()
            }

            get("/complex/:userId/posts/:postId") { exchange ->
                val userId = exchange.pathParam("userId")
                val postId = exchange.pathParam("postId")
                exchange.respond(200, "User: $userId, Post: $postId")
            }
        }

        // Create pipeline
        val pipeline = Pipeline().apply {
            installRecovery()
            installCallLogging()
            installContentNegotiation()
            use(router.asMiddleware())
        }

        // Create and start server
        val config = VertxServerConfig(port = testPort)
        server = VertxServer(config, pipeline) { exchange ->
            exchange.notFound("Not Found")
        }
        server.start()

        // Wait a bit for server to be ready
        Thread.sleep(500)
    }

    @AfterAll
    fun teardown() = runBlocking(AetherDispatcher.dispatcher) {
        if (::server.isInitialized) {
            server.stop()
        }
        if (::driver.isInitialized) {
            driver.close()
        }
        postgres.stop()
    }

    @Test
    fun testHomePage() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode(), "Home page should return 200")
        assertTrue(response.body().contains("Welcome to Aether"), "Response should contain welcome message")
    }

    @Test
    fun testListUsers() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/users"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode(), "Users page should return 200")
        assertTrue(response.body().contains("<html>"), "Response should be HTML")
        assertTrue(response.body().contains("Users"), "Response should contain users")
        assertTrue(response.body().contains("alice"), "Response should contain test user alice")
        assertTrue(response.body().contains("bob"), "Response should contain test user bob")
    }

    @Test
    fun testUserById() = runBlocking {
        val users = User.all()
        val firstUser = users.first()
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/users/${firstUser.id}"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode(), "User detail page should return 200")
        assertTrue(response.body().contains("User: ${firstUser.username}"), "Response should contain username")
    }

    @Test
    fun testUserNotFound() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/users/99999"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(404, response.statusCode(), "Non-existent user should return 404")
        assertTrue(response.body().contains("User not found"), "Response should contain not found message")
    }

    @Test
    fun testApiListUsers() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/users"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode(), "API should return 200")
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""), "Content-Type should be JSON")
        
        val users = Json.decodeFromString<List<UserDto>>(response.body())
        assertTrue(users.size >= 3, "Should have at least 3 test users")
        assertTrue(users.any { it.username == "alice" }, "Should contain alice")
        assertTrue(users.any { it.username == "bob" }, "Should contain bob")
        assertTrue(users.any { it.username == "charlie" }, "Should contain charlie")
    }

    @Test
    fun testApiCreateUser() {
        val newUser = CreateUserRequest(
            username = "david",
            email = "david@example.com",
            age = 35
        )
        val requestBody = Json.encodeToString(CreateUserRequest.serializer(), newUser)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/users"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(201, response.statusCode(), "User creation should return 201")
        
        val createdUser = Json.decodeFromString<UserDto>(response.body())
        assertNotNull(createdUser.id, "Created user should have an ID")
        assertEquals("david", createdUser.username, "Username should match")
        assertEquals("david@example.com", createdUser.email, "Email should match")
        assertEquals(35, createdUser.age, "Age should match")
    }

    @Test
    fun testDatabaseOperations() = runBlocking {
        // Test User.create()
        val newUser = User.create("testuser", "test@example.com", 28)
        assertNotNull(newUser.id, "Created user should have an ID")
        assertEquals("testuser", newUser.username)

        // Test User.findById()
        val foundUser = User.findById(newUser.id!!)
        assertNotNull(foundUser, "Should find user by ID")
        assertEquals("testuser", foundUser?.username)

        // Test User.save() - update
        foundUser?.age = 29
        foundUser?.save()
        
        val updatedUser = User.findById(newUser.id!!)
        assertEquals(29, updatedUser?.age, "Age should be updated")

        // Test User.findByUsername()
        val userByUsername = User.findByUsername("testuser")
        assertNotNull(userByUsername, "Should find user by username")
        assertEquals("testuser", userByUsername?.username)

        // Test User.delete()
        foundUser?.delete()
        val deletedUser = User.findById(newUser.id!!)
        assertEquals(null, deletedUser, "User should be deleted")
    }

    @Test
    fun testRoutingWithMultipleParams() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/complex/123/posts/456"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(200, response.statusCode(), "Complex route should return 200")
        assertTrue(response.body().contains("User: 123"), "Response should contain userId parameter")
        assertTrue(response.body().contains("Post: 456"), "Response should contain postId parameter")
    }

    @Test
    fun testInvalidRouteReturns404() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/nonexistent/route"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(404, response.statusCode(), "Invalid route should return 404")
    }

    @Test
    fun testBadRequestHandling() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/users/invalid"))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        assertEquals(400, response.statusCode(), "Invalid ID should return 400")
        assertTrue(response.body().contains("Invalid ID"), "Response should contain error message")
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            if (::server.isInitialized) {
                server.stop()
            }
            if (::driver.isInitialized) {
                driver.close()
            }
        }
        postgres.stop()
    }
}
