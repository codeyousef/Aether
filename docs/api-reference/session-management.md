# Session Management API

The `aether-core` module provides a flexible session management system.

## Session Interface

The `Session` interface represents the current user's session. It behaves like a mutable map.

```kotlin
interface Session {
    val id: String
    operator fun get(key: String): String?
    operator fun set(key: String, value: String)
    fun remove(key: String)
    fun clear()
    fun invalidate()
}
```

## SessionMiddleware

The middleware handles cookie parsing, session retrieval, and saving.

```kotlin
class SessionMiddleware(
    private val store: SessionStore,
    private val config: SessionConfig
) : Middleware
```

### Configuration

```kotlin
data class SessionConfig(
    val cookieName: String = "AETHER_SESSION",
    val ttl: Long = 3600, // Seconds
    val secure: Boolean = true,
    val httpOnly: Boolean = true
)
```

## SessionStore

The `SessionStore` interface defines how session data is persisted.

```kotlin
interface SessionStore {
    suspend fun save(session: Session)
    suspend fun load(id: String): Session?
    suspend fun delete(id: String)
}
```

### Implementations

*   `InMemorySessionStore`: Stores sessions in a `ConcurrentHashMap`. Good for development and single-instance deployments.
*   `RedisSessionStore` (Planned): Stores sessions in Redis for distributed deployments.
*   `DatabaseSessionStore` (Planned): Stores sessions in the SQL database.
