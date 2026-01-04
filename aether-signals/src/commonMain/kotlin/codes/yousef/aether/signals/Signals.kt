package codes.yousef.aether.signals

import codes.yousef.aether.signals.Signal

/**
 * Payload for entity lifecycle signals.
 *
 * @param T The entity type.
 * @property entity The entity being saved/deleted.
 * @property isNew True if this is a new entity (INSERT), false for existing (UPDATE).
 */
data class EntitySignalPayload<T>(
    val entity: T,
    val isNew: Boolean
)

/**
 * Payload for request lifecycle signals.
 */
data class RequestSignalPayload(
    /**
     * The exchange being processed.
     * Type is Any to avoid circular dependency with aether-core.
     */
    val exchange: Any,

    /**
     * The request path.
     */
    val path: String,

    /**
     * The HTTP method.
     */
    val method: String,

    /**
     * Timestamp when the request started (milliseconds since epoch).
     */
    val startTime: Long,

    /**
     * Duration in milliseconds (only set for requestFinished).
     */
    val duration: Long? = null,

    /**
     * Response status code (only set for requestFinished).
     */
    val statusCode: Int? = null,

    /**
     * Exception if the request failed (only set for requestFinished on error).
     */
    val error: Throwable? = null
)

/**
 * Built-in signals for Aether framework events.
 *
 * These signals allow you to hook into framework events without modifying
 * core code. Similar to Django signals.
 *
 * Example:
 * ```kotlin
 * // Listen for entity saves
 * Signals.postSave.connect { payload ->
 *     if (payload.entity is User && payload.isNew) {
 *         sendWelcomeEmail(payload.entity)
 *     }
 * }
 *
 * // Listen for request completion
 * Signals.requestFinished.connect { payload ->
 *     logRequest(payload.path, payload.duration)
 * }
 * ```
 */
object Signals {
    /**
     * Fired before an entity is saved (INSERT or UPDATE).
     * Receivers can modify the entity before it's persisted.
     */
    val preSave = Signal<EntitySignalPayload<Any>>()

    /**
     * Fired after an entity is saved (INSERT or UPDATE).
     * The entity has been persisted and has a primary key.
     */
    val postSave = Signal<EntitySignalPayload<Any>>()

    /**
     * Fired before an entity is deleted.
     * Receivers can perform cleanup or prevent deletion by throwing.
     */
    val preDelete = Signal<EntitySignalPayload<Any>>()

    /**
     * Fired after an entity is deleted.
     * The entity has been removed from the database.
     */
    val postDelete = Signal<EntitySignalPayload<Any>>()

    /**
     * Fired when a request starts processing.
     */
    val requestStarted = Signal<RequestSignalPayload>()

    /**
     * Fired when a request finishes processing (success or error).
     */
    val requestFinished = Signal<RequestSignalPayload>()

    /**
     * Fired when an unhandled exception occurs during request processing.
     */
    val requestError = Signal<RequestSignalPayload>()

    /**
     * Disconnect all receivers from all built-in signals.
     * Useful for testing.
     */
    fun disconnectAll() {
        preSave.disconnectAll()
        postSave.disconnectAll()
        preDelete.disconnectAll()
        postDelete.disconnectAll()
        requestStarted.disconnectAll()
        requestFinished.disconnectAll()
        requestError.disconnectAll()
    }
}

/**
 * Type-safe signal for a specific entity type.
 *
 * Use this to create signals that only accept a specific entity type:
 * ```kotlin
 * val userCreated = TypedSignal<User>()
 * userCreated.connect { user -> ... }
 * ```
 */
class TypedSignal<T : Any>(
    config: SignalConfig = SignalConfig()
) {
    private val signal = Signal<EntitySignalPayload<T>>(config)

    /**
     * Connect a receiver that only handles the entity.
     */
    fun connect(handler: suspend (T) -> Unit): Disposable {
        return signal.connect { payload ->
            handler(payload.entity)
        }
    }

    /**
     * Connect a receiver that handles both entity and metadata.
     */
    fun connectWithMeta(handler: suspend (EntitySignalPayload<T>) -> Unit): Disposable {
        return signal.connect(handler = handler)
    }

    /**
     * Send the signal.
     */
    suspend fun send(entity: T, isNew: Boolean = false) {
        signal.send(EntitySignalPayload(entity, isNew))
    }

    /**
     * Disconnect all receivers.
     */
    fun disconnectAll() = signal.disconnectAll()

    val receiverCount: Int get() = signal.receiverCount
    val hasReceivers: Boolean get() = signal.hasReceivers
}
