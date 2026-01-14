# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew build                              # Full build all targets
./gradlew :aether-core:jvmTest               # Run single module's JVM tests
./gradlew :example-app:run                   # Run example app (requires PostgreSQL)
./gradlew :example-app:test                  # Integration tests with TestContainers
./gradlew check -x wasmJsBrowserTest -x :example-app:test  # CI-style test run
./gradlew publishToMavenLocal                # Publish to local Maven for testing
```

## Architecture Overview

Aether is a Kotlin Multiplatform framework targeting JVM (Vert.x + Virtual Threads) and Wasm (WasmJS/WasmWASI). The
architecture abstracts platform differences via expect/actual declarations.

### Module Structure

| Module            | Purpose                                                      |
|-------------------|--------------------------------------------------------------|
| `aether-core`     | Exchange, Pipeline, Dispatcher - foundation                  |
| `aether-web`      | Radix tree router, path parameter extraction                 |
| `aether-db`       | Django-style ORM, QueryAST, database drivers                 |
| `aether-signals`  | Event system (preSave, postSave, etc.)                       |
| `aether-tasks`    | Background job queue with KSP code generation                |
| `aether-channels` | WebSocket pub/sub groups                                     |
| `aether-auth`     | Authentication providers (Basic, Bearer, JWT, API Key, Form) |
| `aether-ui`       | Composable UI DSL, SSR + CBOR serialization                  |
| `aether-net`      | Transport abstraction (TCP, future protocols)                |
| `aether-ksp`      | Database migration generation                                |

### Source Set Structure

```
src/
├── commonMain/kotlin/codes/yousef/aether/[module]/  # Shared code
├── jvmMain/kotlin/                                   # JVM (Vert.x, Virtual Threads)
├── wasmJsMain/kotlin/                               # Browser/Cloudflare Workers
└── wasmWasiMain/kotlin/                             # WASI runtime
```

### Key expect/actual Declarations

- `AetherDispatcher` - JVM uses Virtual Threads, Wasm uses event loop
- `TcpTransport` - JVM uses Vert.x NetServer, Wasm provides stubs
- `renderToHtml()` - Platform-specific SSR
- `LoggerFactory` - Platform logging

## Essential Patterns

### Router Definition

```kotlin
val router = router {
    get("/users/:id") { exchange ->
        val id = exchange.pathParamInt("id")
        // handler logic
    }
}
```

### Model Definition (ActiveRecord)

```kotlin
object Users : Model<User>() {
    override val tableName = "users"
    val id = integer("id", primaryKey = true, autoIncrement = true)
    val username = varchar("username", maxLength = 100)
}
```

### Pipeline Middleware

```kotlin
val pipeline = Pipeline().apply {
    installRecovery()
    installCallLogging()
    use(router.asMiddleware())
}
```

## Key Technical Decisions

1. **Virtual Threads (Loom)** - JVM handlers run on `Executors.newVirtualThreadPerTaskExecutor()`, allowing blocking I/O
2. **QueryAST** - SQL queries are AST, not strings; translated per-driver (enables HTTP driver for Wasm)
3. **Radix Tree Routing** - O(k) path matching where k = path length
4. **DatabaseDriverRegistry** - Global singleton for driver access
5. **CBOR Serialization** - UI trees can be serialized for transport

## Testing

- Integration tests use TestContainers for PostgreSQL
- JUnit 5 platform with `@TestInstance(PER_CLASS)` for database state
- Target JVM 21

## Package Naming

All code follows `codes.yousef.aether.[module]` hierarchy.
