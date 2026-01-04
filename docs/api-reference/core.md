# Core API

The `aether-core` module provides the fundamental building blocks for Aether applications, including the `Exchange` interface for handling HTTP interactions and the `Pipeline` for middleware orchestration.

## Exchange

The `Exchange` interface represents a single HTTP request-response cycle. It provides access to the request data, response manipulation, and helper methods for sending various types of responses.

### Properties

| Property | Type | Description |
| :--- | :--- | :--- |
| `request` | `Request` | The incoming HTTP request. |
| `response` | `Response` | The outgoing HTTP response. |
| `attributes` | `Attributes` | A map for storing request-scoped data (e.g., extracted path parameters, user sessions). |

### Response Methods

#### `respond(statusCode: Int = 200, body: String)`
Sends a plain text response.
*   Sets `Content-Type` to `text/plain; charset=utf-8`.

#### `respondHtml(statusCode: Int = 200, html: String)`
Sends an HTML response.
*   Sets `Content-Type` to `text/html; charset=utf-8`.

#### `respondJson<T>(statusCode: Int = 200, data: T, json: Json = Json, serializer: KSerializer<T>)`
Sends a JSON response.
*   Sets `Content-Type` to `application/json; charset=utf-8`.
*   Uses `kotlinx.serialization` to encode the data.

#### `respondCbor(statusCode: Int = 200, data: ByteArray)`
Sends a CBOR (Concise Binary Object Representation) response.
*   Sets `Content-Type` to `application/cbor`.

#### `respondBytes(statusCode: Int = 200, contentType: String, bytes: ByteArray)`
Sends raw binary data with a specified content type.

#### `redirect(url: String, permanent: Boolean = false)`
Sends a redirect response.
*   Status code: `302 Found` (default) or `301 Moved Permanently`.
*   Sets the `Location` header.

### Error Helpers

*   `notFound(message: String = "Not Found")`: Sends a 404 response.
*   `internalError(message: String = "Internal Server Error")`: Sends a 500 response.
*   `badRequest(message: String = "Bad Request")`: Sends a 400 response.
*   `unauthorized(message: String = "Unauthorized")`: Sends a 401 response.

---

## Pipeline

The `Pipeline` class implements the middleware chain pattern (often called the "Russian Doll" model). Middleware functions are executed sequentially, and each middleware can decide whether to pass control to the next one or short-circuit the request.

### Middleware Definition

Middleware is defined as a suspend function:

```kotlin
typealias Middleware = suspend (exchange: Exchange, next: suspend () -> Unit) -> Unit
```

### Usage

```kotlin
val pipeline = Pipeline()

pipeline.use { exchange, next ->
    println("Before request")
    next() // Pass control to the next middleware
    println("After request")
}
```

### Methods

*   `use(middleware: Middleware)`: Adds a middleware to the end of the pipeline.
*   `execute(exchange: Exchange, handler: suspend (Exchange) -> Unit)`: Executes the pipeline. If all middleware call `next()`, the final `handler` is executed.
*   `copy()`: Creates a shallow copy of the pipeline.
*   `clear()`: Removes all middleware.

### DSL

You can use the `pipeline` builder function:

```kotlin
val appPipeline = pipeline {
    use(loggingMiddleware)
    use(authMiddleware)
}
```

---

## Rate Limit Middleware

The rate limit middleware provides quota-based request limiting to protect your API from abuse.

### Basic Usage

```kotlin
pipeline.installRateLimit {
    quotaProvider = InMemoryQuotaProvider(
        limit = 100,          // 100 requests
        windowMillis = 60_000 // per minute
    )
    keyExtractor = { exchange ->
        exchange.request.headers.get("X-API-Key") ?: exchange.request.remoteAddress
    }
}
```

### Configuration Options

```kotlin
pipeline.installRateLimit {
    // Required: Quota provider
    quotaProvider = InMemoryQuotaProvider(limit = 1000, windowMillis = 3600_000)
    
    // Extract key to identify the client (default: IP address)
    keyExtractor = { exchange -> exchange.request.remoteAddress }
    
    // Cost per request (default: 1)
    costFunction = { exchange ->
        if (exchange.request.path.startsWith("/api/heavy/")) 10L else 1L
    }
    
    // Paths to exclude from rate limiting
    excludedPaths = setOf("/health", "/metrics")
    excludedPathPrefixes = setOf("/public/", "/static/")
    
    // Bypass condition (e.g., for admin users)
    bypassCondition = { exchange ->
        exchange.attributes[UserKey]?.isAdmin == true
    }
    
    // Response customization
    statusCode = 429  // Too Many Requests (default)
    errorMessage = "Rate limit exceeded"
    includeHeaders = true  // X-RateLimit-* headers
    
    // Custom response headers
    responseHeaders = { usage ->
        mapOf("X-Credits-Remaining" to usage.remaining.toString())
    }
}
```

### Database-Backed Credits

For persistent quota tracking:

```kotlin
pipeline.installRateLimitWithCredits(
    getCredits = { userId -> userService.getCredits(userId) },
    deductCredits = { userId, amount -> userService.deductCredits(userId, amount) }
) {
    keyExtractor = { exchange -> exchange.attributes[UserKey]?.id }
}
```

### Response Headers

When `includeHeaders = true`, these headers are added:
- `X-RateLimit-Limit`: Total quota
- `X-RateLimit-Remaining`: Remaining quota
- `X-RateLimit-Reset`: Unix timestamp when quota resets

---

## HTTP Reverse Proxy

The proxy middleware enables forwarding requests to upstream services with full streaming support.

### Simple Proxy

```kotlin
// One-liner: forward entire request and response
exchange.proxyTo("https://api.example.com/v1${exchange.request.path}")
```

### Proxy with Inspection

```kotlin
// Inspect response before forwarding
val response = exchange.proxyRequest("https://api.example.com/v1/data")
if (response.statusCode == 200) {
    exchange.respondBytes(response.statusCode, response.contentType, response.body)
} else {
    exchange.internalError("Upstream error")
}
```

### Proxy Middleware DSL

```kotlin
pipeline.installProxy {
    // Route by path prefix
    route("/api/users") {
        target = "https://users-service.internal:8080"
        stripPrefix = true  // /api/users/123 -> /123
    }
    
    route("/api/orders") {
        target = "https://orders-service.internal:8080"
        
        // Header management
        addHeaders = mapOf("X-Internal-Token" to "secret")
        removeHeaders = setOf("Cookie", "Authorization")
        
        // Path rewriting
        pathRewriter = { path -> "/v2$path" }
    }
    
    // Global settings
    timeout = 30_000  // 30 seconds
    includeForwardedHeaders = true  // X-Forwarded-For, X-Forwarded-Proto
    
    // Circuit breaker
    circuitBreaker {
        failureThreshold = 5
        successThreshold = 2
        timeout = 60_000  // Open state duration
    }
}
```

### Streaming Support

The proxy supports true streaming for SSE and chunked responses:

```kotlin
route("/api/stream") {
    target = "https://streaming-service.internal:8080"
    streaming = true  // Enable Flow<ByteArray> streaming
}
```

### Error Handling

```kotlin
pipeline.apply {
    handleProxyExceptions()  // Automatic 502/503/504 responses
    installProxy { ... }
}
```

Exception types:
- `ProxyConnectionException` → 502 Bad Gateway
- `ProxyTimeoutException` → 504 Gateway Timeout
- `ProxyCircuitOpenException` → 503 Service Unavailable
