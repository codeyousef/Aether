---
name: aether
description: Use when developing applications with the Aether Kotlin Multiplatform web framework. Provides patterns, conventions, and guidance for routes, models, middleware, authentication, and cross-platform code.
argument-hint: [task-description]
---

# Aether Framework Development Guide

You are assisting with development using **Aether**, a Django-like Kotlin Multiplatform framework targeting JVM (
Vert.x + Virtual Threads) and Wasm (WasmJS/WasmWASI).

## Core Principles

1. **Multiplatform First** - Code in `commonMain` unless platform-specific. Use `expect`/`actual` for platform
   abstractions.
2. **Virtual Threads on JVM** - Blocking I/O is fine; handlers run on `Executors.newVirtualThreadPerTaskExecutor()`.
3. **QueryAST, Not Strings** - Database queries are AST-based, translated per-driver. Never write raw SQL strings.
4. **Package Convention** - All code under `codes.yousef.aether.[module]`.

## Module Dependencies

```
aether-core    → Foundation (Exchange, Pipeline, Dispatcher)
aether-web     → Routing (depends on core)
aether-db      → ORM (depends on core, signals)
aether-auth    → Authentication (depends on core, web)
aether-ui      → SSR/UI DSL (depends on core)
aether-grpc    → gRPC support (depends on core, web)
aether-channels → WebSockets (depends on core)
aether-tasks   → Background jobs (depends on core, db)
aether-signals → Event system (depends on core)
aether-forms   → Form validation (depends on core)
aether-admin   → Admin panel (depends on core, db, web, ui)
```

## Essential Patterns

### Router Definition

```kotlin
val router = router {
    get("/") { exchange ->
        exchange.respondHtml("<h1>Home</h1>")
    }

    get("/users/:id") { exchange ->
        val id = exchange.pathParamInt("id")
        val user = Users.findById(id)
        exchange.respondJson(user)
    }

    post("/users") { exchange ->
        val body = exchange.receiveJson<CreateUserRequest>()
        // validate and create
        exchange.respond(201, "Created")
    }

    // Nested routes
    route("/api") {
        route("/v1") {
            get("/health") { it.respondJson(mapOf("status" to "ok")) }
        }
    }
}
```

### Model Definition (ActiveRecord)

```kotlin
// Define the model schema
object Users : Model<User>() {
    override val tableName = "users"

    val id = integer("id", primaryKey = true, autoIncrement = true)
    val username = varchar("username", maxLength = 100)
    val email = varchar("email", maxLength = 255)
    val createdAt = timestamp("created_at")
    val isActive = boolean("is_active", default = true)

    // Foreign key
    val roleId = integer("role_id", foreignKey = ForeignKey(Roles, Roles.id))

    override fun create(row: Row): User {
        return User(
            id = row.getInt("id")!!,
            username = row.getString("username")!!,
            email = row.getString("email")!!,
            createdAt = row.getTimestamp("created_at")!!,
            isActive = row.getBoolean("is_active")!!,
            roleId = row.getInt("role_id")
        )
    }
}

// Data class
data class User(
    val id: Int,
    val username: String,
    val email: String,
    val createdAt: Instant,
    val isActive: Boolean,
    val roleId: Int?
)

// Querying
val users = Users.objects.filter { it.isActive eq true }.toList()
val user = Users.findById(1)
val admins = Users.objects.filter { (it.roleId eq 1) and (it.isActive eq true) }.toList()
```

### Pipeline & Middleware

```kotlin
val pipeline = pipeline {
    // Error handling (should be first)
    installRecovery()

    // Logging
    installCallLogging()

    // Security
    installCsrf(CsrfConfig(tokenLength = 32))
    installSessions(InMemorySessionStore(), SessionConfig(
        cookieName = "SESSION",
        maxAge = 3600L,
        httpOnly = true,
        secure = true
    ))

    // Authentication
    installAuthentication(authConfig)

    // Content negotiation
    installContentNegotiation()

    // Router (should be last)
    use(router.asMiddleware())
}
```

### Custom Middleware

```kotlin
class RateLimitMiddleware(private val maxRequests: Int) : Middleware {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun handle(exchange: Exchange, next: suspend (Exchange) -> Unit) {
        val ip = exchange.remoteAddress
        val count = counts.computeIfAbsent(ip) { AtomicInteger(0) }

        if (count.incrementAndGet() > maxRequests) {
            exchange.respond(429, "Too Many Requests")
            return
        }

        next(exchange)
    }
}

// Usage
pipeline {
    use(RateLimitMiddleware(100))
    use(router.asMiddleware())
}
```

### Authentication

```kotlin
val authConfig = AuthenticationConfig().apply {
    // Basic auth
    providers["basic"] = BasicAuthProvider { credentials ->
        val user = Users.objects.filter { it.username eq credentials.username }.firstOrNull()
        if (user != null && verifyPassword(credentials.password, user.passwordHash)) {
            AuthResult.Success(Principal.User(user.username, user.roles))
        } else {
            AuthResult.Failure("Invalid credentials")
        }
    }

    // JWT
    providers["jwt"] = JwtAuthProvider(JwtConfig(
        secret = "your-secret-key",
        issuer = "your-app",
        audience = "your-audience"
    ))

    // API Key
    providers["api-key"] = ApiKeyAuthProvider { apiKey ->
        val key = ApiKeys.objects.filter { it.key eq apiKey }.firstOrNull()
        if (key != null && key.isActive) {
            AuthResult.Success(Principal.ApiKey(key.name, key.scopes))
        } else {
            AuthResult.Failure("Invalid API key")
        }
    }
}

pipeline {
    installAuthentication(authConfig) {
        excludePaths.add("/public")
        excludePaths.add("/health")
    }
    installAuthorization(AuthorizationConfig().apply {
        rules["/admin/*"] = AuthorizationRule(requiredRoles = setOf("admin"))
    })
}
```

### UI DSL (Server-Side Rendering)

```kotlin
exchange.render {
    head {
        title("My App")
        meta(charset = "utf-8")
        link(rel = "stylesheet", href = "/styles.css")
    }
    body {
        header {
            nav {
                a(href = "/") { text("Home") }
                a(href = "/about") { text("About") }
            }
        }
        main {
            h1 { text("Welcome") }
            div("container") {
                p { text("Hello, ${user.name}!") }

                // Conditional rendering
                if (user.isAdmin) {
                    a(href = "/admin") { text("Admin Panel") }
                }

                // List rendering
                ul {
                    items.forEach { item ->
                        li { text(item.name) }
                    }
                }
            }
        }
        footer {
            p { text("© 2024 My App") }
        }
    }
}
```

### WebSocket Channels

```kotlin
val wsConfig = webSocket {
    path("/ws/chat/:room") {
        onConnect { session ->
            val room = session.pathParam("room")
            session.joinGroup(room)
            session.group(room).broadcast("User joined")
        }

        onMessage { session, message ->
            when (message) {
                is WebSocketMessage.Text -> {
                    val room = session.pathParam("room")
                    session.group(room).broadcast(message.data)
                }
                is WebSocketMessage.Binary -> {
                    session.send(message.data)
                }
            }
        }

        onClose { session, code, reason ->
            val room = session.pathParam("room")
            session.group(room).broadcast("User left")
        }
    }
}
```

### Database Initialization

```kotlin
// JVM with PostgreSQL
fun initDatabase() {
    val driver = VertxPgDriver.create(
        host = System.getenv("DB_HOST") ?: "localhost",
        port = System.getenv("DB_PORT")?.toInt() ?: 5432,
        database = System.getenv("DB_NAME") ?: "app",
        user = System.getenv("DB_USER") ?: "postgres",
        password = System.getenv("DB_PASSWORD") ?: "postgres"
    )
    DatabaseDriverRegistry.initialize(driver)
}

// Cross-platform with Supabase
fun initDatabase() {
    val driver = SupabaseDriver.create(
        projectUrl = System.getenv("SUPABASE_URL")!!,
        apiKey = System.getenv("SUPABASE_KEY")!!
    )
    DatabaseDriverRegistry.initialize(driver)
}
```

### expect/actual for Platform Code

```kotlin
// commonMain
expect fun getCurrentTimestamp(): Long
expect fun hashPassword(password: String): String

// jvmMain
actual fun getCurrentTimestamp(): Long = System.currentTimeMillis()
actual fun hashPassword(password: String): String {
    return BCrypt.hashpw(password, BCrypt.gensalt())
}

// wasmJsMain
actual fun getCurrentTimestamp(): Long = Date.now().toLong()
actual fun hashPassword(password: String): String {
    // Use JS crypto library
    return js("crypto.subtle.digest('SHA-256', password)").toString()
}
```

### File Uploads

```kotlin
post("/upload") { exchange ->
    val file = exchange.file("document")
    if (file != null) {
        // Validate
        if (file.size > 10_000_000) {
            exchange.respond(400, "File too large")
            return@post
        }
        if (file.contentType !in listOf("image/png", "image/jpeg", "application/pdf")) {
            exchange.respond(400, "Invalid file type")
            return@post
        }

        // Save
        val path = "uploads/${UUID.randomUUID()}_${file.filename}"
        Files.write(Path.of(path), file.content)

        exchange.respondJson(mapOf("path" to path))
    } else {
        exchange.respond(400, "No file provided")
    }
}
```

### Form Validation

```kotlin
val userForm = form {
    field("username") {
        required()
        minLength(3)
        maxLength(50)
        pattern(Regex("^[a-zA-Z0-9_]+$"), "Only alphanumeric and underscore")
    }
    field("email") {
        required()
        email()
    }
    field("age") {
        optional()
        integer()
        range(18, 120)
    }
}

post("/register") { exchange ->
    val result = userForm.validate(exchange.formParams())
    if (result.isValid) {
        // Create user
    } else {
        exchange.respondJson(mapOf("errors" to result.errors), status = 400)
    }
}
```

### Signals (Events)

```kotlin
// Register signal handlers
Users.preSave.connect { user ->
    user.updatedAt = Instant.now()
}

Users.postSave.connect { user ->
    EmailService.sendWelcome(user.email)
}

Users.preDelete.connect { user ->
    // Archive before delete
    ArchivedUsers.create(user)
}
```

### Background Tasks

```kotlin
@AetherTask
suspend fun sendEmailTask(to: String, subject: String, body: String) {
    EmailService.send(to, subject, body)
}

// Enqueue
TaskQueue.enqueue(::sendEmailTask, "user@example.com", "Welcome", "Hello!")

// With delay
TaskQueue.enqueue(::sendEmailTask, "user@example.com", "Reminder", "Don't forget!", delay = 1.hours)
```

## Testing

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers
class UserApiTest {
    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @BeforeAll
    fun setup() {
        val driver = VertxPgDriver.create(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password
        )
        DatabaseDriverRegistry.initialize(driver)
    }

    @Test
    fun `should create user`() = runTest {
        val exchange = MockExchange(
            method = "POST",
            path = "/users",
            body = """{"username": "test", "email": "test@example.com"}"""
        )

        router.handle(exchange)

        assertEquals(201, exchange.responseStatus)
    }
}
```

## Common Tasks

When the user asks to: **$ARGUMENTS**

Consider which patterns above apply and generate idiomatic Aether code following these conventions.
