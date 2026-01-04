# Aether Framework

A Django-like Kotlin Multiplatform framework that runs on JVM (Vert.x + Virtual Threads) and Wasm (Cloudflare/Browser).

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
- **Authentication**: Pluggable authentication with Basic, Bearer, JWT, API Key, and Form providers
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
            implementation("codes.yousef.aether:aether-core:0.4.0")
            implementation("codes.yousef.aether:aether-web:0.4.0")
            implementation("codes.yousef.aether:aether-db:0.4.0")
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
├── aether-plugin/     # Gradle plugin
├── aether-cli/        # Command-line tools
├── example-app/       # Example application
└── docs/              # Documentation and deployment guides
```

## Quick Start

### Prerequisites

- JDK 21 or later
- Kotlin 2.1.0 or later
- PostgreSQL (for JVM deployment)

### Running the Example App

1. Start a PostgreSQL database:
```bash
docker run -d \
  --name aether-postgres \
  -e POSTGRES_USER=aether \
  -e POSTGRES_PASSWORD=aether \
  -e POSTGRES_DB=aether_dev \
  -p 5432:5432 \
  postgres:16-alpine
```

2. Build and run the example application:
```bash
./gradlew :example-app:run
```

3. Open your browser to `http://localhost:8080`

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

## Transport Abstraction

The framework includes a pluggable network abstraction layer (`:aether-net`) that decouples application logic from the underlying transport mechanism. This enables:

- Flexible networking implementation
- Support for alternative transport protocols
- Platform-specific optimizations
- Future extensibility without breaking changes

The transport layer provides a clean interface that can be implemented for different networking paradigms while keeping application code unchanged.

## Security Features

### Session Management

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

### CSRF Protection

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

### Authentication

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

### Docker

```bash
# Build image
docker build -t aether-app:latest -f docs/deployment/Dockerfile .

# Run with Docker Compose
cd docs/deployment
docker compose up -d
```

### Kubernetes

```bash
# Apply all manifests
kubectl apply -f docs/deployment/kubernetes/

# Check status
kubectl -n aether get pods
```

See [Deployment Guide](docs/deployment/DEPLOYMENT.md) for detailed instructions on AWS, GCP, Azure, and DigitalOcean deployments.

## Contributing

This is a proof-of-concept framework demonstrating production-ready KMP architecture. Contributions are welcome!

## License

MIT License - See LICENSE file for details
