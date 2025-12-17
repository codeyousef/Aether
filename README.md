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

## Project Structure

```
aether/
├── aether-core/       # Core abstractions (Exchange, Pipeline, Dispatcher)
├── aether-db/         # ORM and database drivers
├── aether-web/        # Routing and controllers
├── aether-ui/         # UI rendering (SSR + CBOR)
├── aether-net/        # Network transport abstraction
├── aether-plugin/     # Gradle plugin
├── aether-cli/        # Command-line tools
└── example-app/       # Example application
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

## Development Roadmap

### Phase 1: Foundation ✅
- [x] Version catalog and build infrastructure
- [x] Multi-module KMP project structure
- [x] JVM, WasmJS, and WasmWASI targets

### Phase 2: Core Kernel ✅
- [x] AetherDispatcher with Virtual Threads
- [x] Exchange abstraction
- [x] Middleware pipeline
- [x] Vert.x adapter

### Phase 3: Data Plane ✅
- [x] Query AST
- [x] Model DSL
- [x] DatabaseDriver interface
- [x] VertxPgDriver
- [x] HTTP driver for Wasm

### Phase 4: Web Layer ✅
- [x] Radix tree router
- [x] Path parameter extraction
- [x] JVM and Wasm adapters

### Phase 5: UI Integration ✅
- [x] Composable UI DSL
- [x] JVM SSR engine
- [x] CBOR serialization

### Phase 6: Network Layer ✅
- [x] NetworkTransport interface
- [x] TCP implementation (JVM)
- [x] UWW stubs (WASI)

### Phase 7: Tooling ✅
- [x] Gradle plugin
- [x] CLI tool structure

### Phase 8: Testing ✅
- [x] Integration tests
- [x] TestContainers setup

### Future Work
- [ ] KSP-based migration generation
- [ ] WebSocket support
- [ ] File upload handling
- [ ] Session management
- [ ] CSRF protection
- [ ] Authentication middleware
- [ ] Alternative transport implementations
- [ ] Production deployment guides

## Contributing

This is a proof-of-concept framework demonstrating production-ready KMP architecture. Contributions are welcome!

## License

MIT License - See LICENSE file for details
