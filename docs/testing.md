# Testing

Aether is designed to be testable. You can write unit tests for your logic and integration tests for your full application.

## Unit Testing

Since most logic resides in `commonMain`, you can use standard `kotlin.test` assertions.

Aether apps are easy to test using standard tools like JUnit and MockK.

### Testing Handlers

You can mock the `Exchange` object to test handlers in isolation.

```kotlin
@Test
fun testHelloHandler() = runTest {
    val exchange = MockExchange(path = "/hello/world")
    val handler = { ex: Exchange -> 
        ex.respond(body = "Hello") 
    }
    
    handler(exchange)
    
    assertEquals(200, exchange.response.statusCode)
    assertEquals("Hello", exchange.response.bodyString)
}
```

## End-to-End (E2E) Testing

For integration testing, you can spin up a real server instance within your test suite.

```kotlin
@Test
fun testServer() {
    val server = VertxServer.create(port = 0) {
        it.respond("OK")
    }
    runBlocking {
        server.start()
        // Use an HTTP client to test endpoints
        server.stop()
    }
}
```

Using `testcontainers` is recommended for testing with real database backends.

## Integration Testing

Integration tests run the full server stack. Aether uses **TestContainers** to spin up dependencies like PostgreSQL.

### Setup

Add the test dependencies in `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}
```

### Writing an Integration Test

```kotlin
@Testcontainers
class UserIntegrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16-alpine")
        
        @JvmStatic
        @BeforeAll
        fun setup() {
            // Configure Aether to use the container's JDBC URL
            DatabaseDriverRegistry.register(
                PostgresDriver(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password
                )
            )
        }
    }

    @Test
    fun `should create and retrieve user`() = runTest {
        // 1. Start Server (or use TestClient directly against Pipeline)
        val app = TestApplication()
        
        // 2. Perform Request
        val response = app.client.post("/users", json = """{"name": "Alice"}""")
        
        // 3. Assert
        assertEquals(201, response.statusCode)
        
        val user = Users.get(1)
        assertNotNull(user)
        assertEquals("Alice", user.name)
    }
}
```

## Wasm Testing

Wasm tests run in a headless browser (Chrome/Firefox) or Node.js.

```bash
./gradlew wasmJsBrowserTest
```

Ensure you have the necessary browsers installed or configured in Karma.
