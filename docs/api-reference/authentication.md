# Authentication API

Aether provides a modular authentication system.

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
