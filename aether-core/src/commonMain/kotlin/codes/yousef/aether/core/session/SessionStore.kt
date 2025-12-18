package codes.yousef.aether.core.session

import kotlinx.serialization.json.Json

/**
 * Interface for storing and retrieving sessions.
 * Implementations can use various backends (memory, database, Redis, etc.).
 */
interface SessionStore {
    /**
     * Get a session by its ID.
     * Returns null if the session doesn't exist or has expired.
     */
    suspend fun get(sessionId: String): Session?

    /**
     * Save a session.
     * Creates a new session if it doesn't exist, or updates an existing one.
     */
    suspend fun save(session: Session)

    /**
     * Delete a session by its ID.
     */
    suspend fun delete(sessionId: String)

    /**
     * Check if a session exists.
     */
    suspend fun exists(sessionId: String): Boolean

    /**
     * Clean up expired sessions.
     * Implementations should remove sessions that have exceeded maxAge.
     */
    suspend fun cleanup()

    /**
     * Get the count of active sessions.
     */
    suspend fun count(): Long
}

/**
 * In-memory session store for development and testing.
 * Not suitable for production with multiple instances.
 */
class InMemorySessionStore(
    private val config: SessionConfig = SessionConfig()
) : SessionStore {
    private val sessions = mutableMapOf<String, DefaultSession>()

    override suspend fun get(sessionId: String): Session? {
        val session = sessions[sessionId] ?: return null
        
        // Check expiration
        val now = currentTimeMillis()
        val expirationTime = session.lastAccessedAt + (config.maxAge * 1000)
        if (now > expirationTime) {
            sessions.remove(sessionId)
            return null
        }
        
        // Update last accessed time
        session.lastAccessedAt = now
        return session
    }

    override suspend fun save(session: Session) {
        if (session is DefaultSession) {
            sessions[session.id] = session
        } else {
            // Convert to DefaultSession if necessary
            val defaultSession = DefaultSession(
                id = session.id,
                createdAt = session.createdAt
            )
            defaultSession.lastAccessedAt = session.lastAccessedAt
            session.keys().forEach { key ->
                defaultSession.set(key, session.get(key))
            }
            sessions[session.id] = defaultSession
        }
    }

    override suspend fun delete(sessionId: String) {
        sessions.remove(sessionId)
    }

    override suspend fun exists(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        val now = currentTimeMillis()
        val expirationTime = session.lastAccessedAt + (config.maxAge * 1000)
        if (now > expirationTime) {
            sessions.remove(sessionId)
            return false
        }
        return true
    }

    override suspend fun cleanup() {
        val now = currentTimeMillis()
        val expired = sessions.filter { (_, session) ->
            val expirationTime = session.lastAccessedAt + (config.maxAge * 1000)
            now > expirationTime
        }
        expired.keys.forEach { sessions.remove(it) }
    }

    override suspend fun count(): Long = sessions.size.toLong()

    /**
     * Get all sessions (for testing/debugging).
     */
    fun getAllSessions(): Map<String, Session> = sessions.toMap()
}

/**
 * Platform-specific function to get current time in milliseconds.
 */
expect fun currentTimeMillis(): Long

/**
 * Platform-specific function to generate a secure random session ID.
 */
expect fun generateSecureSessionId(length: Int = 32): String
