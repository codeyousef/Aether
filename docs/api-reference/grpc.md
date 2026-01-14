# gRPC API

Aether provides first-class gRPC support with a code-first approach, supporting gRPC-Web, Connect protocol, and native
HTTP/2 gRPC.

## Overview

The `aether-grpc` module enables:

- **Code-First Proto Generation**: Define services in Kotlin, generate `.proto` files automatically
- **Multi-Protocol Support**: gRPC-Web, Connect JSON, and native HTTP/2 gRPC
- **Unified Authentication**: Same auth strategies work for REST and gRPC
- **Streaming**: Server, client, and bidirectional streaming with Kotlin Flow
- **Cross-Platform**: Works on JVM, WasmJS, and WasmWasi

## Quick Start

### 1. Add the Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("codes.yousef.aether:aether-grpc:0.5.0.2")
    ksp("codes.yousef.aether:aether-ksp:0.5.0.2")
}
```

### 2. Define Messages

```kotlin
@AetherMessage
data class User(
    @ProtoField(1) val id: String,
    @ProtoField(2) val name: String,
    @ProtoField(3) val email: String? = null
)

@AetherMessage
data class GetUserRequest(
    @ProtoField(1) val id: String
)
```

### 3. Define Services

```kotlin
@AetherService
interface UserService {
    @AetherRpc
    suspend fun getUser(request: GetUserRequest): User

    @AetherRpc
    suspend fun listUsers(request: ListUsersRequest): Flow<User>
}
```

### 4. Configure the Server

```kotlin
val config = grpc {
    port = 50051
    reflection = true

    service("UserService", "users.v1") {
        unary<GetUserRequest, User>("GetUser") { request ->
            userRepository.findById(request.id)
                ?: throw GrpcException.notFound("User not found")
        }

        serverStreaming<ListUsersRequest, User>("ListUsers") { request ->
            userRepository.streamAll()
        }
    }
}
```

---

## Annotations

### `@AetherMessage`

Marks a data class as a gRPC message. The KSP processor generates a corresponding `.proto` message definition.

```kotlin
@AetherMessage
data class User(
    @ProtoField(1) val id: String,
    @ProtoField(2) val name: String,
    @ProtoField(3) val age: Int? = null  // Optional field
)
```

**Generated Proto:**

```protobuf
message User {
    string id = 1;
    string name = 2;
    optional int32 age = 3;
}
```

**Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | String | Class name | Custom message name |

### `@ProtoField`

Specifies the field number for a protobuf field. Field numbers must be unique within a message.

```kotlin
@ProtoField(
    id = 1,
    deprecated = false,
    json = ""
)
```

**Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `id` | Int | Required | Field number (must be positive, unique) |
| `deprecated` | Boolean | `false` | Mark field as deprecated |
| `json` | String | `""` | Custom JSON field name |

### `@AetherService`

Marks an interface as a gRPC service.

```kotlin
@AetherService(name = "UserService")
interface UserServiceApi {
    // RPC methods
}
```

### `@AetherRpc`

Marks a function as an RPC method. The method signature determines the RPC type:

| Signature                                        | RPC Type         |
|--------------------------------------------------|------------------|
| `suspend fun method(req: Req): Resp`             | Unary            |
| `suspend fun method(req: Req): Flow<Resp>`       | Server streaming |
| `suspend fun method(req: Flow<Req>): Resp`       | Client streaming |
| `suspend fun method(req: Flow<Req>): Flow<Resp>` | Bidirectional    |

```kotlin
@AetherRpc(name = "GetUser", deprecated = false)
suspend fun getUser(request: GetUserRequest): User
```

---

## Type Mapping

Kotlin types are automatically mapped to protobuf types:

| Kotlin Type     | Proto Type        |
|-----------------|-------------------|
| `Int`           | `int32`           |
| `Long`          | `int64`           |
| `Float`         | `float`           |
| `Double`        | `double`          |
| `Boolean`       | `bool`            |
| `String`        | `string`          |
| `ByteArray`     | `bytes`           |
| `UInt`          | `uint32`          |
| `ULong`         | `uint64`          |
| `List<T>`       | `repeated T`      |
| `T?` (nullable) | `optional T`      |
| Custom class    | Message reference |

---

## GrpcStatus

Standard gRPC status codes:

```kotlin
enum class GrpcStatus(val code: Int) {
    OK(0),
    CANCELLED(1),
    UNKNOWN(2),
    INVALID_ARGUMENT(3),
    DEADLINE_EXCEEDED(4),
    NOT_FOUND(5),
    ALREADY_EXISTS(6),
    PERMISSION_DENIED(7),
    RESOURCE_EXHAUSTED(8),
    FAILED_PRECONDITION(9),
    ABORTED(10),
    OUT_OF_RANGE(11),
    UNIMPLEMENTED(12),
    INTERNAL(13),
    UNAVAILABLE(14),
    DATA_LOSS(15),
    UNAUTHENTICATED(16)
}
```

## GrpcException

Throw `GrpcException` to return gRPC errors:

```kotlin
// Using factory methods
throw GrpcException.notFound("User not found")
throw GrpcException.invalidArgument("Invalid email format")
throw GrpcException.permissionDenied("Insufficient permissions")
throw GrpcException.unauthenticated("Token expired")
throw GrpcException.internal("Database error")

// Direct construction
throw GrpcException(
    status = GrpcStatus.FAILED_PRECONDITION,
    message = "Account must be verified first"
)
```

## GrpcMetadata

Key-value metadata for gRPC headers and trailers:

```kotlin
val metadata = GrpcMetadata()
metadata["authorization"] = "Bearer token123"
metadata.add("x-custom-header", "value1")
metadata.add("x-custom-header", "value2")

val auth = metadata["Authorization"]  // Case-insensitive
val all = metadata.getAll("x-custom-header")  // ["value1", "value2"]
```

---

## DSL Configuration

### `grpc { }` Block

```kotlin
val config = grpc {
    // Server settings
    port = 50051
    mode = GrpcMode.BEST_AVAILABLE
    reflection = true

    // Performance settings
    maxMessageSize = 16 * 1024 * 1024  // 16MB
    keepAliveTime = 30_000L
    keepAliveTimeout = 10_000L

    // Register services
    service(myService)

    // Inline service definition
    service("EchoService", "echo.v1") {
        unary<String, String>("Echo") { request ->
            request
        }
    }

    // Interceptors
    intercept { call, next ->
        println("Request: ${call.serviceName}/${call.methodName}")
        val result = next(call)
        println("Response sent")
        result
    }
}
```

### GrpcMode

| Mode             | Description                                       |
|------------------|---------------------------------------------------|
| `BEST_AVAILABLE` | Auto-selects native on JVM, adapter otherwise     |
| `ADAPTER_ONLY`   | HTTP adapter for gRPC-Web/Connect (all platforms) |
| `NATIVE_ONLY`    | Native HTTP/2 gRPC (JVM only)                     |

### GrpcConfig Properties

| Property           | Type     | Default        | Description              |
|--------------------|----------|----------------|--------------------------|
| `port`             | Int      | 50051          | gRPC server port         |
| `mode`             | GrpcMode | BEST_AVAILABLE | Server mode              |
| `reflection`       | Boolean  | false          | Enable server reflection |
| `maxMessageSize`   | Int      | 4MB            | Maximum message size     |
| `keepAliveTime`    | Long     | 2 hours        | Keepalive ping interval  |
| `keepAliveTimeout` | Long     | 20 sec         | Keepalive timeout        |

---

## Service Definition DSL

### Unary RPC

```kotlin
service("UserService", "users.v1") {
    unary<GetUserRequest, User>("GetUser") { request ->
        userRepository.findById(request.id)
            ?: throw GrpcException.notFound("User ${request.id} not found")
    }
}
```

### Server Streaming

```kotlin
service("UserService", "users.v1") {
    serverStreaming<ListUsersRequest, User>("ListUsers") { request ->
        flow {
            userRepository.findAll().forEach { user ->
                emit(user)
                delay(100)  // Simulate streaming
            }
        }
    }
}
```

### Client Streaming

```kotlin
service("UploadService", "upload.v1") {
    clientStreaming<Chunk, UploadResult>("Upload") { chunks ->
        var totalBytes = 0L
        chunks.collect { chunk ->
            totalBytes += chunk.data.size
            storage.append(chunk)
        }
        UploadResult(totalBytes = totalBytes)
    }
}
```

### Bidirectional Streaming

```kotlin
service("ChatService", "chat.v1") {
    bidiStreaming<ChatMessage, ChatMessage>("Chat") { incoming ->
        incoming.map { message ->
            // Echo with timestamp
            message.copy(timestamp = Clock.System.now())
        }
    }
}
```

---

## Streaming Handlers

For advanced streaming scenarios, use the handler classes directly:

### ServerStreamingHandler

```kotlin
val handler = ServerStreamingHandler<ListRequest, Item> { request ->
    flow {
        database.streamItems(request.filter).collect { item ->
            emit(item)
        }
    }
}

val results: Flow<Item> = handler.handle(request)
```

### ClientStreamingHandler

```kotlin
val handler = ClientStreamingHandler<Chunk, Summary> { chunks ->
    var count = 0
    chunks.collect { count++ }
    Summary(totalChunks = count)
}

val result: Summary = handler.handle(chunksFlow)
```

### BiDirectionalStreamingHandler

```kotlin
val handler = BiDirectionalStreamingHandler<Message, Message> { incoming ->
    incoming
        .filter { it.type != "ping" }
        .map { it.copy(processed = true) }
}

val results: Flow<Message> = handler.handle(incomingFlow)
```

---

## Streaming Codecs

### LpmCodec (Length-Prefixed Message)

Used for gRPC wire format:

```kotlin
val codec = LpmCodec()

// Frame a message
val message = "Hello".encodeToByteArray()
val framed = codec.frame(message)  // Adds 5-byte header

// Unframe a message
val extracted = codec.unframe(framed)

// Read multiple messages
val messages = codec.readMessages(data)
```

### SseCodec (Server-Sent Events)

Used for streaming over HTTP/1.1:

```kotlin
val codec = SseCodec()

// Format an event
val event = codec.formatEvent(
    data = """{"name":"test"}""",
    eventType = "message",
    id = "123"
)
// Output:
// event: message
// id: 123
// data: {"name":"test"}
//

// Parse an event
val parsed = codec.parseEvent(eventText)
println(parsed?.data)
```

---

## Authentication Integration

### UserContext

gRPC and REST share the same authentication context via coroutines:

```kotlin
// In any handler (REST or gRPC)
suspend fun handleRequest() {
    val user = currentUser()  // Returns Principal?
    val user = requireUser()  // Throws if not authenticated

    if (isAuthenticated()) {
        // ...
    }

    if (hasRole("admin")) {
        // ...
    }
}
```

### AuthStrategy

Protocol-agnostic authentication strategies:

```kotlin
// Bearer token (JWT)
val bearerStrategy = BearerTokenStrategy { token ->
    jwtService.verify(token)
}

// API key
val apiKeyStrategy = ApiKeyStrategy { apiKey ->
    apiKeyRepository.findByKey(apiKey)?.toPrincipal()
}

// Basic auth
val basicStrategy = BasicAuthStrategy { username, password ->
    userService.authenticate(username, password)
}

// Composite (try multiple strategies)
val compositeStrategy = CompositeAuthStrategy(
    listOf(bearerStrategy, apiKeyStrategy)
)
```

### gRPC Interceptor for Auth

```kotlin
grpc {
    intercept { call, next ->
        val token = call.metadata["authorization"]
        val principal = bearerStrategy.authenticateFromHeader(token)

        when (principal) {
            is AuthResult.Success -> {
                withContext(UserContext(principal.principal)) {
                    next(call)
                }
            }
            else -> throw GrpcException.unauthenticated("Invalid token")
        }
    }
}
```

---

## Protocol Support

### gRPC-Web

Works with browser clients using the gRPC-Web protocol:

```kotlin
// Automatically detected via Content-Type
// application/grpc-web
// application/grpc-web+proto
```

### Connect Protocol

Supports the Connect protocol for JSON-based gRPC:

```kotlin
// Automatically detected via Content-Type
// application/connect+json
// application/json
```

### Native gRPC (JVM)

Full HTTP/2 gRPC with trailers support:

```kotlin
grpc {
    mode = GrpcMode.NATIVE_ONLY  // Force native mode
}
```

---

## GrpcAdapter

Routes gRPC requests through the HTTP stack:

```kotlin
val adapter = GrpcAdapter(listOf(userService, orderService))

// Route a request
val (service, method) = adapter.route("users.v1.UserService", "GetUser")
    ?: throw GrpcException.unimplemented("Method not found")

// Parse path
val (serviceName, methodName) = GrpcAdapter.parsePath("/users.v1.UserService/GetUser")

// Content-type detection
GrpcAdapter.isGrpcWeb("application/grpc-web")  // true
GrpcAdapter.isConnectJson("application/connect+json")  // true
```

---

## GrpcHttpHandler

HTTP handler that processes gRPC requests:

```kotlin
val adapter = GrpcAdapter(listOf(userService, orderService))
val handler = GrpcHttpHandler(adapter)

// Handle a request
val response = handler.handle(
    path = "/users.v1.UserService/GetUser",
    body = """{"id":"123"}""",
    contentType = "application/json",
    metadata = GrpcMetadata()
)

// Check response
if (response.isSuccess) {
    println(response.body)
} else {
    println("Error: ${response.status}")
}
```

### Supported Content Types

| Content Type                 | Protocol          |
|------------------------------|-------------------|
| `application/grpc-web`       | gRPC-Web binary   |
| `application/grpc-web+proto` | gRPC-Web protobuf |
| `application/grpc-web-text`  | gRPC-Web base64   |
| `application/json`           | Connect JSON      |
| `application/connect+json`   | Connect JSON      |
| `application/connect+proto`  | Connect protobuf  |

---

## Pipeline Integration

### installGrpc()

Install gRPC middleware into your pipeline:

```kotlin
val pipeline = pipeline {
    installGrpc {
        service(userService)
        service(orderService)
        reflection = true
    }
}
```

### With Pre-built Config

```kotlin
val config = grpc {
    port = 50051
    service(userService)
}

val pipeline = pipeline {
    installGrpc(config)
}
```

### With Service List

```kotlin
val pipeline = pipeline {
    installGrpc(userService, orderService)
}
```

### As Middleware Function

```kotlin
val middleware = grpcMiddleware {
    service(userService)
}

pipeline.use(middleware)
```

### Complete Example

```kotlin
// Define your service
val userService = grpcService("UserService", "users.v1") {
    unary<GetUserRequest, User>("GetUser") { request ->
        userRepository.findById(request.id)
            ?: throw GrpcException.notFound("User not found")
    }
}

// Create pipeline with gRPC support
val pipeline = pipeline {
    installRecovery()
    installCallLogging()
    installGrpc {
        service(userService)
    }
}

// Start server
aetherStart {
    port = 8080
    pipeline(pipeline)
}
```

Requests to `/users.v1.UserService/GetUser` will be handled by the gRPC middleware.

---

## KSP Proto Generation

The KSP processor automatically generates `.proto` files from annotated classes:

### Enable KSP

```kotlin
// build.gradle.kts
plugins {
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
}

dependencies {
    ksp("codes.yousef.aether:aether-ksp:0.5.0.2")
}

// Optional: Configure output location
ksp {
    arg("aether.proto.package", "myapp.v1")
    arg("aether.proto.output", "src/main/proto")
}
```

### Generated Output

For the User and UserService examples above, generates:

```protobuf
syntax = "proto3";

package users.v1;

message User {
    string id = 1;
    string name = 2;
    optional string email = 3;
}

message GetUserRequest {
    string id = 1;
}

service UserService {
    rpc GetUser(GetUserRequest) returns (User);
    rpc ListUsers(ListUsersRequest) returns (stream User);
}
```

---

## Best Practices

### 1. Use Meaningful Field Numbers

Reserve field numbers 1-15 for frequently used fields (they use 1 byte). Numbers 16-2047 use 2 bytes.

```kotlin
@AetherMessage
data class User(
    @ProtoField(1) val id: String,      // Common field
    @ProtoField(2) val name: String,    // Common field
    @ProtoField(16) val metadata: Map<String, String>  // Less common
)
```

### 2. Handle Errors Properly

```kotlin
unary<Request, Response>("Method") { request ->
    try {
        process(request)
    } catch (e: ValidationException) {
        throw GrpcException.invalidArgument(e.message ?: "Validation failed")
    } catch (e: NotFoundException) {
        throw GrpcException.notFound(e.message ?: "Resource not found")
    } catch (e: Exception) {
        logger.error("Unexpected error", e)
        throw GrpcException.internal("Internal server error")
    }
}
```

### 3. Use Streaming for Large Data

```kotlin
// Bad: Load all into memory
unary<Request, Response>("GetAll") { request ->
    Response(items = repository.findAll())  // Memory issues!
}

// Good: Stream results
serverStreaming<Request, Item>("StreamAll") { request ->
    repository.streamAll()  // Returns Flow<Item>
}
```

### 4. Propagate Cancellation

```kotlin
serverStreaming<Request, Item>("Stream") { request ->
    flow {
        repository.streamAll().collect { item ->
            ensureActive()  // Check for cancellation
            emit(transform(item))
        }
    }
}
```
