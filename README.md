# Aether Framework

A Django-like Kotlin Multiplatform framework that runs on JVM (Vert.x + Virtual Threads) and Wasm (Cloudflare/Browser).

> **Release status:** `0.6.0.0` was released on 2026-07-16, and the Maven coordinates below use this
> version. Production wasmWasi identity-authority hosting is not supported in this release because
> the combined component-host integration is not complete; use the JVM authority or another
> supported trusted server host for production.

## Architecture

Aether is designed to be a "colorless" framework that abstracts away the differences between JVM and Wasm platforms, enabling developers to write their application logic once and deploy it anywhere.

### Key Features

- **Multiplatform Core**: Write once, run on JVM and Wasm
- **Virtual Threads**: JVM implementation uses Java 21 Virtual Threads for high-performance concurrent request handling
- **Type-Safe Routing**: Radix tree-based routing with path parameter extraction
- **Active Record ORM**: Django-inspired model system with automatic query building
- **Middleware Pipeline**: Composable middleware for request/response processing
- **SSR + Hydration**: Server-side rendering with client-side hydration support
- **Transport Abstraction**: Pluggable network layer allowing alternative transport implementations
- **Session Management**: Secure, configurable session handling with multiple storage backends
- **CSRF Protection**: Built-in cross-site request forgery protection middleware
- **Identity**: Passkey-first people, organizations, opaque sessions, CLI device flow, service identities, OIDC, SAML, and SCIM
- **File Uploads**: Multipart form-data parsing with validation and streaming support
- **WebSocket Support**: Full-duplex WebSocket communication with DSL-based configuration
- **KSP Migrations**: Kotlin Symbol Processing for automatic database schema migration generation

## Quickstart

**1. Add the dependency** (`build.gradle.kts`):

```kotlin
kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation("codes.yousef.aether:aether-core:0.6.0.0")
            implementation("codes.yousef.aether:aether-web:0.6.0.0")
            implementation("codes.yousef.aether:aether-db:0.6.0.0")
        }
    }
}
```

**2. Create your app** (`src/jvmMain/kotlin/Main.kt`):

```kotlin
import codes.yousef.aether.core.*
import codes.yousef.aether.web.*

fun main() {
    val router = router {
        get("/") { exchange ->
            exchange.respondHtml("<h1>Hello, Aether!</h1>")
        }
        get("/api/hello") { exchange ->
            exchange.respondJson(mapOf("message" to "Hello, World!"))
        }
    }

    val pipeline = pipeline {
        installRecovery()
        installCallLogging()
        use(router.asMiddleware())
    }

    AetherServer.start(port = 8080, pipeline = pipeline)
}
```

**3. Run it:**

```bash
./gradlew run
# Open http://localhost:8080
```

See [Full Documentation](docs/quickstart.md) for detailed setup instructions.

## Project Structure

```
aether/
├── aether-core/       # Core abstractions (Exchange, Pipeline, Dispatcher)
├── aether-db/         # ORM and database drivers
├── aether-web/        # Routing and controllers
├── aether-ui/         # UI rendering (SSR + CBOR)
├── aether-net/        # Network transport abstraction
├── aether-ksp/        # KSP-based migration generation
├── aether-auth/       # Storage-neutral passkey-first identity engine
├── aether-auth-*/     # Optional storage, UI, OIDC, SAML, and SCIM adapters
├── aether-plugin/     # Gradle plugin
├── aether-cli/        # Command-line tools
├── example-app/       # Example application
└── docs/              # Documentation and deployment guides
```

## Quick Start

### Prerequisites

- JDK 21 or later
- Kotlin 2.3.21 or later

### Running the Example App

1. Build and run the KMP passkey identity example (JVM SSR plus wasmJs hydration):

```bash
AETHER_IDENTITY_BOOTSTRAP_SECRET=a-development-secret-at-least-16-chars \
  ./gradlew :example-app:run
```

2. Open `http://localhost:8080/identity`.

The example keeps the authority/storage host seam explicit. Production deployment selects either
the PostgreSQL 16 or Firestore identity adapter and follows the [identity deployment
guide](docs/identity/deployment.md).

### Running Tests

The framework includes comprehensive integration tests using TestContainers:

```bash
./gradlew :example-app:test
```

## Core Concepts

### Exchange

The `Exchange` interface represents an HTTP request-response cycle. It provides a unified API across JVM and Wasm:

```kotlin
exchange.respond(200, "Hello, World!")
exchange.respondJson(data = mapOf("message" to "Hello"))
exchange.respondHtml("<h1>Hello</h1>")
```

### Routing

Define routes using a type-safe DSL:

```kotlin
val router = Router.build {
    get("/") { exchange ->
        exchange.respond(200, "Home")
    }

    get("/users/{id}") { exchange ->
        val id = exchange.attributes.get(Attributes.key<String>("id"))
        // Handle user by ID
    }
}
```

### Models

Define database models using the Active Record pattern:

```kotlin
object Users : Model<User>() {
    override val tableName = "users"
    val id = integer("id", unique = true)
    val username = varchar("username", maxLength = 100)
    val email = varchar("email")

    override fun create(row: Row): User {
        return User(
            id = row.getInt("id")!!,
            username = row.getString("username")!!,
            email = row.getString("email")!!
        )
    }
}

// Usage
val user = User.findById(1)
user?.username = "newname"
user?.save()
```

### Middleware

Create reusable middleware for request processing:

```kotlin
val pipeline = Pipeline.build {
    use(Recovery())           // Error handling
    use(CallLogging())        // Request logging
    use(ContentNegotiation()) // Content type detection
    handle { exchange ->
        router.handle(exchange)
    }
}
```

### UI Rendering

Render HTML using a Composable DSL:

```kotlin
exchange.render {
    head {
        title("My App")
    }
    body {
        h1 { text("Welcome") }
        div("container") {
            p { text("Hello, World!") }
        }
    }
}
```

### Database Backends

Aether supports multiple database backends through pluggable drivers. Choose the backend that fits your deployment:

#### PostgreSQL (Default)

```kotlin
// JVM with Vert.x Reactive Client
val driver = VertxPgDriver.create(
    host = "localhost",
    port = 5432,
    database = "mydb",
    user = "postgres",
    password = "secret"
)
DatabaseDriverRegistry.initialize(driver)
```

#### Supabase (PostgreSQL + REST API)

Works on both JVM and Wasm platforms via PostgREST API:

```kotlin
val driver = SupabaseDriver.create(
    projectUrl = "https://your-project.supabase.co",
    apiKey = "your-anon-or-service-key"
)
DatabaseDriverRegistry.initialize(driver)

// Your models work unchanged
val users = User.objects.filter { it.active eq true }.toList()
```

#### Firestore (NoSQL)

Works on both JVM and Wasm platforms via REST API:

```kotlin
// With API key (client-side)
val driver = FirestoreDriver.create(
    projectId = "your-project-id",
    apiKey = "your-api-key"
)

// With OAuth token (server-side)
val driver = FirestoreDriver.createWithToken(
    projectId = "your-project-id",
    accessToken = "your-oauth-token"
)

DatabaseDriverRegistry.initialize(driver)
```

> **Note:** Firestore is NoSQL - JOINs and LIKE queries are not supported. Use denormalized data patterns.

## Platform Support

### JVM

- Uses Vert.x for HTTP server and reactive database client
- Leverages Java 21 Virtual Threads for blocking I/O operations

- PostgreSQL support via Vert.x Reactive PostgreSQL Client
- SSR with automatic client hydration

### Wasm (WasmJS)

- Runs in browser or Cloudflare Workers
- HTTP driver communicates with backend via JSON API
- Client-side rendering with CBOR support
- Event loop-based concurrency

### Wasm (WasmWASI)

- WASI-based runtime support
- Pluggable transport layer for alternative networking
- CBOR-based UI tree serialization
- Future-proof architecture for emerging web platforms

The generic target is available for experimentation, but production wasmWasi Identity-authority
hosting is not supported in `0.6.0.0`: the combined Kotlin guest, WIT OpenSSL crypto host, and
`wasi:http` integration is not complete. See the
[identity deployment guide](docs/identity/deployment.md#release-verification).

## Transport Abstraction

The framework includes a pluggable network abstraction layer (`:aether-net`) that decouples application logic from the underlying transport mechanism. This enables:

- Flexible networking implementation
- Support for alternative transport protocols
- Platform-specific optimizations
- Future extensibility without breaking changes

The transport layer provides a clean interface that can be implemented for different networking paradigms while keeping application code unchanged.

## Security Features

### Generic `aether-core` Session Management

This state-bag session API is for unrelated applications. Aether Identity does not use it: identity
sessions are opaque, rotated credentials in the `__Host-aether_session` cookie and are governed by
the [identity session policy](docs/identity/security.md#session-and-token-theft).

```kotlin
val pipeline = Pipeline().apply {
    installSessions(InMemorySessionStore(), SessionConfig(
        cookieName = "AETHER_SESSION",
        maxAge = 3600L,
        secure = true,
        httpOnly = true,
        sameSite = SameSite.STRICT
    ))
}

// In handlers
val session = exchange.session
session["user"] = "john"
val user = session["user"]
exchange.invalidateSession()
```

### Generic `aether-core` CSRF Protection

The form-token helper below belongs to generic application forms. Aether Identity requires its
session-bound header token plus exact `Origin` validation and rejects form/query-string CSRF
tokens; see [identity CSRF policy](docs/identity/security.md#csrf-and-cross-origin-requests).

```kotlin
val pipeline = Pipeline().apply {
    installCsrf(CsrfConfig(
        tokenLength = 32,
        headerName = "X-CSRF-Token"
    ))
}

// In templates
exchange.render {
    form(action = "/submit", method = "POST") {
        csrfInput(exchange) // Adds hidden CSRF token field
        // form fields...
    }
}
```

### Generic `aether-core` authentication (unrelated applications only)

The following providers belong to the generic core protocol layer. They are not Aether Identity,
must not be mounted on `/identity/v1`, and cannot establish an identity user, organization,
membership, capability, or step-up assurance. Identity applications use the passkey authority
documented in [Passkey-first identity](docs/identity/README.md).

```kotlin
val authConfig = AuthenticationConfig().apply {
    providers["basic"] = BasicAuthProvider { credentials ->
        if (validateUser(credentials.username, credentials.password)) {
            AuthResult.Success(Principal.User(credentials.username, setOf("user")))
        } else {
            AuthResult.Failure("Invalid credentials")
        }
    }
    providers["jwt"] = JwtAuthProvider(jwtConfig)
}

val pipeline = Pipeline().apply {
    installAuthentication(authConfig) {
        excludePaths.add("/public")
    }
    installAuthorization(AuthorizationConfig().apply {
        rules["/admin"] = AuthorizationRule(requiredRoles = setOf("admin"))
    })
}
```

## File Uploads

```kotlin
router {
    post("/upload") { exchange ->
        val file = exchange.file("document")
        if (file != null) {
            // file.filename, file.contentType, file.content (ByteArray)
            saveFile(file)
            exchange.respond(200, "Uploaded: ${file.filename}")
        }
    }

    post("/multi-upload") { exchange ->
        val files = exchange.files("attachments")
        files.forEach { saveFile(it) }
        exchange.respond(200, "Uploaded ${files.size} files")
    }
}
```

## WebSocket Support

```kotlin
val wsConfig = webSocket {
    path("/ws/chat") {
        onConnect { session ->
            broadcast("User joined")
        }
        onMessage { session, message ->
            when (message) {
                is WebSocketMessage.Text -> broadcast(message.data)
                is WebSocketMessage.Binary -> session.send(message.data)
            }
        }
        onClose { session, code, reason ->
            broadcast("User left")
        }
    }
}

val wsServer = VertxWebSocketServer(vertx, 8080, wsConfig)
wsServer.start()
```

## Database Migrations

Using KSP to generate migrations automatically:

```kotlin
@AetherModel
object Users : Model<User>() {
    override val tableName = "users"

    @PrimaryKey(autoIncrement = true)
    val id = integer("id")

    @Column(maxLength = 100)
    @Index(unique = true)
    val username = varchar("username", maxLength = 100)

    @Column(maxLength = 255)
    val email = varchar("email", maxLength = 255)
}

// Generated migration
val migration = migration("002", "Add users table") {
    createTable("users") {
        column("id", "SERIAL", primaryKey = true)
        column("username", "VARCHAR(100)", nullable = false)
        column("email", "VARCHAR(255)", nullable = false)
    }
    createIndex("idx_users_username", "users", listOf("username"), unique = true)
}
```

## Production Deployment

Aether Identity deployments must follow the dedicated [identity deployment
guide](docs/identity/deployment.md). It is the only deployment guide in this repository that applies
to the passkey authority. The old sample Docker, Compose, Nginx, SQL, and Kubernetes files used
nonexistent example distribution tasks and legacy JWT/session settings; they were removed and a
non-runnable tombstone remains in their place. Package unrelated `aether-core` applications from
their own application build and deployment model.

## Contributing

This is a proof-of-concept framework demonstrating production-ready KMP architecture. Contributions are welcome!

## License

MIT License - See LICENSE file for details
