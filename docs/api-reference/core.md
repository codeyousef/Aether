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
