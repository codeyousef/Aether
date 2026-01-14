# Authentication API

Aether provides a modular authentication system that works across REST and gRPC.

## AuthMiddleware

The `AuthMiddleware` orchestrates the authentication process. It iterates through a list of registered `AuthProvider`s.

```kotlin
class AuthMiddleware(
    private val providers: List<AuthProvider>
) : Middleware
```

## AuthProvider

Implement this interface to support different authentication schemes.

```kotlin
interface AuthProvider {
    suspend fun authenticate(exchange: Exchange): AuthResult
}

sealed class AuthResult {
    data class Success(val principal: Principal) : AuthResult()
    object Failure : AuthResult()
    object Skipped : AuthResult() // Provider doesn't handle this request
}
```

## Principal

Represents the authenticated entity (usually a user).

```kotlin
interface Principal {
    val id: String
    val name: String
    val roles: Set<String>
}
```

## Built-in Providers

### `BasicAuthProvider`
Implements HTTP Basic Auth.

```kotlin
BasicAuthProvider { username, password ->
    // Verify credentials against DB
    if (username == "admin" && password == "secret") {
        UserPrincipal(id = "1", name = "admin", roles = setOf("admin"))
    } else {
        null
    }
}
```

### `BearerAuthProvider`
Implements Bearer Token authentication (e.g., JWT).

```kotlin
BearerAuthProvider { token ->
    // Verify JWT
    jwtService.verify(token)
}
```

### `SessionAuthProvider`
Authenticates a user based on their active session.

```kotlin
SessionAuthProvider { session ->
    val userId = session["userId"]
    if (userId != null) {
        UserPrincipal(id = userId, ...)
    } else {
        null
    }
}
```

## Role-Based Access Control (RBAC)

Aether provides a built-in RBAC system allowing granular control over user permissions using `Groups` and `Permissions`.

### User Model

Your user entity should extend `AbstractUser` to gain built-in RBAC capabilities.

```kotlin
class User : AbstractUser<User>() {
    // ... custom fields
}
```

### Checking Permissions

You can check if a user has a specific permission, either directly assigned or inherited from a group.

```kotlin
if (user.hasPermission("blog.create_post")) {
    // Allowed
}
```

### Models

- **User**: The identity of the actor.
- **Group**: A collection of users. Permissions assigned to a group are inherited by all its members.
- **Permission**: A granular access right (e.g., `app.action_resource`).

### Middleware Integration

RBAC is often used in checking access rights within handlers or middleware after authentication has established the principal.

## UserContext

`UserContext` propagates the authenticated principal through Kotlin's coroutine context, enabling unified authentication
for REST and gRPC.

### Accessing the Current User

```kotlin
// In any suspend function (REST handler, gRPC method, etc.)
suspend fun handleRequest() {
    // Get current user (nullable)
    val user: Principal? = currentUser()

    // Require authenticated user (throws if not authenticated)
    val user: Principal = requireUser()

    // Check authentication status
    if (isAuthenticated()) {
        // User is logged in
    }

    // Check roles
    if (hasRole("admin")) {
        // User has admin role
    }
}
```

### How It Works

The `AuthMiddleware` automatically wraps authenticated requests with `UserContext`:

```kotlin
// Internal implementation
when (val result = provider.authenticate(exchange)) {
    is AuthResult.Success -> {
        withContext(UserContext(result.principal)) {
            next()  // Handler runs with UserContext available
        }
    }
    // ...
}
```

This allows any code running within the request to access the authenticated user without passing it explicitly.

## AuthStrategy

`AuthStrategy` provides protocol-agnostic authentication that works for both REST (via headers) and gRPC (via metadata).

### Interface

```kotlin
interface AuthStrategy {
    suspend fun authenticate(credential: String): AuthResult
    suspend fun authenticateOrNoCredentials(credential: String?): AuthResult
}
```

### Built-in Strategies

#### BearerTokenStrategy

For JWT and other bearer tokens:

```kotlin
val strategy = BearerTokenStrategy { token ->
    // Verify and decode the token
    jwtService.verify(token)?.let { claims ->
        UserPrincipal(
            id = claims.subject,
            name = claims["name"] as String,
            roles = claims["roles"] as Set<String>
        )
    }
}

// Extract token from Authorization header
val token = strategy.extractToken("Bearer eyJhbGc...")  // Returns "eyJhbGc..."

// Authenticate from header directly
val result = strategy.authenticateFromHeader("Bearer eyJhbGc...")
```

#### ApiKeyStrategy

For API key authentication:

```kotlin
val strategy = ApiKeyStrategy { apiKey ->
    apiKeyRepository.findByKey(apiKey)?.let { key ->
        ApiKeyPrincipal(
            id = key.id,
            name = key.name,
            roles = key.scopes.toSet()
        )
    }
}
```

#### BasicAuthStrategy

For HTTP Basic authentication:

```kotlin
val strategy = BasicAuthStrategy { username, password ->
    userService.verifyCredentials(username, password)?.let { user ->
        UserPrincipal(id = user.id, name = user.name, roles = user.roles)
    }
}
```

#### CompositeAuthStrategy

Combines multiple strategies (tries each in order):

```kotlin
val strategy = CompositeAuthStrategy(
    listOf(
        bearerStrategy,   // Try JWT first
        apiKeyStrategy,   // Then API key
        basicStrategy     // Finally basic auth
    )
)

// Returns Success if any strategy succeeds
// Returns NoCredentials if all return NoCredentials
// Returns Failure if any returns Failure
```

### Using with gRPC

```kotlin
grpc {
    intercept { call, next ->
        val authHeader = call.metadata["authorization"]
        val result = bearerStrategy.authenticateFromHeader(authHeader)

        when (result) {
            is AuthResult.Success -> {
                withContext(UserContext(result.principal)) {
                    next(call)
                }
            }
            is AuthResult.NoCredentials -> {
                // Allow unauthenticated access or throw
                next(call)
            }
            is AuthResult.Failure -> {
                throw GrpcException.unauthenticated("Invalid credentials")
            }
        }
    }
}
```
