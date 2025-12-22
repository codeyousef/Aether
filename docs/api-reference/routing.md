# Routing API

The `aether-web` module provides a high-performance routing mechanism based on a Radix Tree implementation. It supports standard HTTP methods and dynamic path parameters.

## Router

The `Router` class allows you to define routes and their handlers using a clean DSL.

### Basic Usage

```kotlin
val appRouter = router {
    get("/") { exchange ->
        exchange.respond(body = "Welcome to Aether")
    }

    post("/api/data") { exchange ->
        // Handle POST request
    }
}
```

### Route Methods

The `Router` supports the following HTTP methods:

*   `get(path: String, handler: RouteHandler)`
*   `post(path: String, handler: RouteHandler)`
*   `put(path: String, handler: RouteHandler)`
*   `delete(path: String, handler: RouteHandler)`
*   `patch(path: String, handler: RouteHandler)`
*   `head(path: String, handler: RouteHandler)`
*   `options(path: String, handler: RouteHandler)`

### Path Parameters

Routes can include dynamic parameters prefixed with a colon (`:`). These parameters are extracted and made available in the `Exchange.attributes`.

```kotlin
get("/users/:id") { exchange ->
    val userId = exchange.pathParam("id")
    exchange.respond(body = "User ID: $userId")
}
```

### Radix Tree

Under the hood, `Router` maintains a separate `RadixTree` for each HTTP method. This ensures:
*   **O(k)** lookup time, where *k* is the length of the path.
*   Efficient storage of routes sharing common prefixes.
*   Fast parameter extraction.

### Integration with Pipeline

The `Router` can be converted into a middleware for easy integration into the main application pipeline:

```kotlin
val pipeline = Pipeline()
pipeline.use(router.asMiddleware())
```
