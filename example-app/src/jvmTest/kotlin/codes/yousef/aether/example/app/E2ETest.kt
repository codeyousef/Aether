package codes.yousef.aether.example.app

import codes.yousef.aether.auth.JwtService
import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.web.router
import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.ServerSocket

@ExtendWith(VertxExtension::class)
class E2ETest {

    companion object {
        private var port: Int = 0
        private var server: VertxServer? = null
        private val userPrincipalKey = AttributeKey("UserPrincipal", String::class)

        @BeforeAll
        @JvmStatic
        fun setup() {
            // Find a free port
            val socket = ServerSocket(0)
            port = socket.localPort
            socket.close()

            val jwtSecret = "integration-test-secret"
            val jwtIssuer = "aether-test"

            // Define router
            val appRouter = router {
                get("/public") { exchange ->
                    exchange.respond(200, "Public Area")
                }

                get("/protected") { exchange ->
                    val principal = exchange.attributes.get(userPrincipalKey)
                    if (principal != null) {
                        exchange.respond(200, "Hello $principal")
                    } else {
                        exchange.respond(401, "Unauthorized")
                    }
                }

                get("/rbac/admin") { exchange ->
                    val principal = exchange.attributes.get(userPrincipalKey)
                    if (principal == "admin") {
                        exchange.respond(200, "Admin Area")
                    } else if (principal != null) {
                        exchange.respond(403, "Forbidden")
                    } else {
                        exchange.respond(401, "Unauthorized")
                    }
                }

                post("/login") { exchange ->
                    val token = JwtService.generateToken(
                        subject = "testuser",
                        secret = jwtSecret,
                        issuer = jwtIssuer,
                        expirationMillis = 3600000
                    )
                    exchange.respond(200, token)
                }

                post("/login-admin") { exchange ->
                    val token = JwtService.generateToken(
                        subject = "admin",
                        secret = jwtSecret,
                        issuer = jwtIssuer,
                        expirationMillis = 3600000
                    )
                    exchange.respond(200, token)
                }
            }

            // Start Server
            server = VertxServer.create(
                config = VertxServerConfig(port = port),
                pipeline = codes.yousef.aether.core.pipeline.Pipeline().apply {
                    use { exchange, next ->
                        val authHeader = exchange.request.headers["Authorization"]
                        if (authHeader != null && authHeader.startsWith("Bearer ")) {
                            val token = authHeader.substring(7)
                            try {
                                val payload = JwtService.verifyToken(token, jwtSecret, jwtIssuer)
                                if (payload != null) {
                                    exchange.attributes.put(userPrincipalKey, payload.subject)
                                }
                            } catch (e: Exception) {
                                // Invalid token
                            }
                        }
                        next()
                    }
                    use(appRouter.asMiddleware())
                }
            ) { exchange ->
                // Fallback handler if no route matches (404)
                exchange.respond(404, "Not Found")
            }

            runBlocking {
                server?.start()
            }
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            runBlocking {
                server?.stop()
            }
        }
    }

    @Test
    fun testPublicEndpoint(vertx: Vertx, testContext: VertxTestContext) {
        val client = WebClient.create(vertx)
        client.get(port, "localhost", "/public")
            .send()
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(200, response.statusCode())
                    assertEquals("Public Area", response.bodyAsString())
                    testContext.completeNow()
                }
            }
            .onFailure { err -> testContext.failNow(err) }
    }

    @Test
    fun testProtectedEndpointWithoutToken(vertx: Vertx, testContext: VertxTestContext) {
        val client = WebClient.create(vertx)
        client.get(port, "localhost", "/protected")
            .send()
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(401, response.statusCode())
                    testContext.completeNow()
                }
            }
            .onFailure { err -> testContext.failNow(err) }
    }

    @Test
    fun testProtectedEndpointWithToken(vertx: Vertx, testContext: VertxTestContext) {
        val client = WebClient.create(vertx)

        // 1. Login to get token
        client.post(port, "localhost", "/login")
            .send()
            .onSuccess { loginResponse ->
                val token = loginResponse.bodyAsString()

                // 2. Access protected resource
                client.get(port, "localhost", "/protected")
                    .putHeader("Authorization", "Bearer $token")
                    .send()
                    .onSuccess { response ->
                        testContext.verify {
                            assertEquals(200, response.statusCode())
                            assertEquals("Hello testuser", response.bodyAsString())
                            testContext.completeNow()
                        }
                    }
                    .onFailure { err -> testContext.failNow(err) }
            }
            .onFailure { err -> testContext.failNow(err) }
    }

    @Test
    fun testRbacAccess(vertx: Vertx, testContext: VertxTestContext) {
        val client = WebClient.create(vertx)

        // 1. Login as user
        client.post(port, "localhost", "/login")
            .send()
            .onSuccess { loginResponse ->
                val userToken = loginResponse.bodyAsString()

                // 2. Try to access admin area (should fail)
                client.get(port, "localhost", "/rbac/admin")
                    .putHeader("Authorization", "Bearer $userToken")
                    .send()
                    .onSuccess { response ->
                        testContext.verify {
                            assertEquals(403, response.statusCode())

                            // 3. Login as admin
                            client.post(port, "localhost", "/login-admin")
                                .send()
                                .onSuccess { adminLoginResponse ->
                                    val adminToken = adminLoginResponse.bodyAsString()

                                    // 4. Access admin area (should hello)
                                    client.get(port, "localhost", "/rbac/admin")
                                        .putHeader("Authorization", "Bearer $adminToken")
                                        .send()
                                        .onSuccess { adminResponse ->
                                            testContext.verify {
                                                assertEquals(200, adminResponse.statusCode())
                                                assertEquals("Admin Area", adminResponse.bodyAsString())
                                                testContext.completeNow()
                                            }
                                        }
                                        .onFailure { err -> testContext.failNow(err) }
                                }
                                .onFailure { err -> testContext.failNow(err) }
                        }
                    }
                    .onFailure { err -> testContext.failNow(err) }
            }
            .onFailure { err -> testContext.failNow(err) }
    }
}
