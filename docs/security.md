# Security

Aether provides built-in mechanisms for handling authentication, session management, and common security practices.

## Authentication

The `aether-core` module includes an extensible authentication system.

### Setup

Install the `AuthMiddleware` in your pipeline:

```kotlin
val pipeline = Pipeline()

pipeline.use(AuthMiddleware(
    providers = listOf(BasicAuthProvider(), BearerAuthProvider())
))
```

### Protecting Routes

You can check for authentication in your handlers:

```kotlin
get("/dashboard") { exchange ->
    val user = exchange.attributes[Auth.UserKey]
    if (user == null) {
        exchange.unauthorized()
        return@get
    }
    // ...
}
```

## Session Management

Sessions allow you to store state across requests.

### Configuration

Install the `SessionMiddleware` with a storage backend:

```kotlin
val sessionStore = InMemorySessionStore() // Or RedisSessionStore, DatabaseSessionStore
val sessionConfig = SessionConfig(
    cookieName = "AETHER_SESSION",
    ttl = 3600 // 1 hour
)

pipeline.use(SessionMiddleware(sessionStore, sessionConfig))
```

### Usage

Access the session from the `Exchange`:

```kotlin
get("/login") { exchange ->
    val session = exchange.session()
    session["userId"] = "12345"
    exchange.respond(body = "Logged in")
}

get("/me") { exchange ->
    val session = exchange.session()
    val userId = session["userId"]
    exchange.respond(body = "User: $userId")
}
```

## CSRF Protection

(Coming Soon)
Cross-Site Request Forgery protection will be available as a standard middleware.

## Secure Headers

It is recommended to set standard security headers. You can create a simple middleware for this:

```kotlin
val securityHeaders = { exchange: Exchange, next: suspend () -> Unit ->
    exchange.response.setHeader("X-Content-Type-Options", "nosniff")
    exchange.response.setHeader("X-Frame-Options", "DENY")
    exchange.response.setHeader("X-XSS-Protection", "1; mode=block")
    next()
}

pipeline.use(securityHeaders)
```
