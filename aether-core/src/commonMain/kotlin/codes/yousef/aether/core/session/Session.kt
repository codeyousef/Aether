package codes.yousef.aether.core.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Represents a user session with key-value storage.
 * Sessions are identified by a unique session ID and can store arbitrary data.
 */
interface Session {
    /**
     * The unique identifier for this session.
     */
    val id: String

    /**
     * Timestamp when this session was created (epoch millis).
     */
    val createdAt: Long

    /**
     * Timestamp of the last access (epoch millis).
     */
    var lastAccessedAt: Long

    /**
     * Get a value from the session.
     */
    fun get(key: String): Any?

    /**
     * Get a value from the session with a specific type.
     */
    fun <T> getTyped(key: String): T? = get(key) as? T

    /**
     * Set a value in the session.
     */
    fun set(key: String, value: Any?)

    /**
     * Remove a value from the session.
     */
    fun remove(key: String): Any?

    /**
     * Check if the session contains a key.
     */
    fun contains(key: String): Boolean

    /**
     * Get all keys in the session.
     */
    fun keys(): Set<String>

    /**
     * Clear all values from the session.
     */
    fun clear()

    /**
     * Check if the session is new (just created).
     */
    val isNew: Boolean

    /**
     * Mark the session as not new.
     */
    fun markAccessed()

    /**
     * Invalidate the session (mark for removal).
     */
    fun invalidate()

    /**
     * Check if the session has been invalidated.
     */
    val isInvalidated: Boolean

    /**
     * Get a string value from the session.
     */
    fun getString(key: String): String? = get(key)?.toString()

    /**
     * Get an integer value from the session.
     */
    fun getInt(key: String): Int? = when (val value = get(key)) {
        is Int -> value
        is Long -> value.toInt()
        is String -> value.toIntOrNull()
        is JsonPrimitive -> value.intOrNull
        else -> null
    }

    /**
     * Get a long value from the session.
     */
    fun getLong(key: String): Long? = when (val value = get(key)) {
        is Long -> value
        is Int -> value.toLong()
        is String -> value.toLongOrNull()
        is JsonPrimitive -> value.longOrNull
        else -> null
    }

    /**
     * Get a boolean value from the session.
     */
    fun getBoolean(key: String): Boolean? = when (val value = get(key)) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        is JsonPrimitive -> value.booleanOrNull
        else -> null
    }

    /**
     * Get a double value from the session.
     */
    fun getDouble(key: String): Double? = when (val value = get(key)) {
        is Double -> value
        is Float -> value.toDouble()
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is String -> value.toDoubleOrNull()
        is JsonPrimitive -> value.doubleOrNull
        else -> null
    }
}

/**
 * Configuration for session management.
 */
data class SessionConfig(
    /**
     * Name of the session cookie.
     */
    val cookieName: String = "AETHER_SESSION",

    /**
     * Session timeout in seconds.
     */
    val maxAge: Long = 3600, // 1 hour

    /**
     * Cookie path.
     */
    val path: String = "/",

    /**
     * Cookie domain (null = current domain).
     */
    val domain: String? = null,

    /**
     * Whether the cookie should be secure (HTTPS only).
     */
    val secure: Boolean = false,

    /**
     * Whether the cookie should be HTTP only (not accessible via JavaScript).
     */
    val httpOnly: Boolean = true,

    /**
     * SameSite attribute for the cookie.
     */
    val sameSite: SameSitePolicy = SameSitePolicy.LAX,

    /**
     * Length of session ID in bytes (before base64 encoding).
     */
    val sessionIdLength: Int = 32
)

/**
 * SameSite cookie policy.
 */
enum class SameSitePolicy {
    STRICT,
    LAX,
    NONE
}

/**
 * Default implementation of Session using a map for storage.
 */
class DefaultSession(
    override val id: String,
    override val createdAt: Long,
    initialData: Map<String, Any?> = emptyMap()
) : Session {
    private val data = mutableMapOf<String, Any?>()
    override var lastAccessedAt: Long = createdAt
    private var _isNew: Boolean = true
    private var _isInvalidated: Boolean = false

    init {
        data.putAll(initialData)
    }

    override val isNew: Boolean
        get() = _isNew

    override val isInvalidated: Boolean
        get() = _isInvalidated

    override fun get(key: String): Any? = data[key]

    override fun set(key: String, value: Any?) {
        if (value == null) {
            data.remove(key)
        } else {
            data[key] = value
        }
    }

    override fun remove(key: String): Any? = data.remove(key)

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun keys(): Set<String> = data.keys.toSet()

    override fun clear() {
        data.clear()
    }

    override fun markAccessed() {
        _isNew = false
    }

    override fun invalidate() {
        _isInvalidated = true
        data.clear()
    }

    /**
     * Get all data for serialization.
     */
    fun getAllData(): Map<String, Any?> = data.toMap()

    /**
     * Create a serializable representation of this session.
     */
    fun toSerializable(): SerializableSession {
        val jsonData = data.mapValues { (_, value) ->
            when (value) {
                is String -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                is Double -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is JsonElement -> value
                null -> JsonPrimitive("null")
                else -> JsonPrimitive(value.toString())
            }
        }
        return SerializableSession(
            id = id,
            createdAt = createdAt,
            lastAccessedAt = lastAccessedAt,
            data = jsonData
        )
    }

    companion object {
        /**
         * Create a session from a serializable representation.
         */
        fun fromSerializable(serializable: SerializableSession): DefaultSession {
            val session = DefaultSession(
                id = serializable.id,
                createdAt = serializable.createdAt
            )
            session.lastAccessedAt = serializable.lastAccessedAt
            session._isNew = false
            serializable.data.forEach { (key, value) ->
                session.data[key] = when {
                    value is JsonPrimitive && value.isString -> value.content
                    value is JsonPrimitive -> value.content
                    else -> value
                }
            }
            return session
        }
    }
}

/**
 * Serializable representation of a session for storage.
 */
@Serializable
data class SerializableSession(
    val id: String,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val data: Map<String, JsonElement>
)
