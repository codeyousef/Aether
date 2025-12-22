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
