package codes.yousef.aether.channels

import codes.yousef.aether.core.websocket.WebSocketSession
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Global channel layer singleton for easy access throughout the application.
 * 
 * Initialize at application startup:
 * ```kotlin
 * Channels.initialize(InMemoryChannelLayer())
 * ```
 * 
 * Then use throughout your application:
 * ```kotlin
 * // In WebSocket handler
 * Channels.group("chat_$roomId").add(session)
 * Channels.group("chat_$roomId").broadcast("User joined!")
 * ```
 */
object Channels {
    private var _layer: ChannelLayer? = null
    
    /**
     * The underlying channel layer.
     */
    val layer: ChannelLayer
        get() = _layer ?: throw IllegalStateException(
            "Channels not initialized. Call Channels.initialize() first."
        )
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    /**
     * Initialize the global channel layer.
     * Call this at application startup.
     * 
     * @param channelLayer The channel layer implementation to use
     */
    fun initialize(channelLayer: ChannelLayer) {
        _layer = channelLayer
    }
    
    /**
     * Check if channels have been initialized.
     */
    val isInitialized: Boolean get() = _layer != null
    
    /**
     * Get a group handle for convenient operations.
     * 
     * @param name The group name
     * @return A Group handle
     */
    fun group(name: String): Group = Group(name, layer)
    
    /**
     * Send a message to a specific session.
     */
    suspend fun send(session: WebSocketSession, message: String) {
        session.sendText(message)
    }
    
    /**
     * Send a typed message to a session.
     */
    suspend fun sendMessage(session: WebSocketSession, message: ChannelMessage) {
        session.sendText(json.encodeToString(ChannelMessage.serializer(), message))
    }
    
    /**
     * Remove a session from all groups.
     * Call this when a session disconnects.
     */
    suspend fun disconnect(session: WebSocketSession) {
        layer.discardAll(session)
    }
    
    /**
     * Close the channel layer and cleanup.
     */
    suspend fun close() {
        _layer?.close()
        _layer = null
    }
}

/**
 * A handle for a specific group with convenient methods.
 */
class Group(
    val name: String,
    private val layer: ChannelLayer
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    /**
     * Add a session to this group.
     */
    suspend fun add(session: WebSocketSession) {
        layer.groupAdd(name, session)
    }
    
    /**
     * Remove a session from this group.
     */
    suspend fun discard(session: WebSocketSession) {
        layer.groupDiscard(name, session)
    }
    
    /**
     * Broadcast a text message to all sessions in this group.
     */
    suspend fun broadcast(message: String): SendResult {
        return layer.groupSend(name, message)
    }
    
    /**
     * Broadcast binary data to all sessions in this group.
     */
    suspend fun broadcastBinary(data: ByteArray): SendResult {
        return layer.groupSendBinary(name, data)
    }
    
    /**
     * Broadcast a typed message to all sessions.
     */
    suspend fun broadcastMessage(
        type: String,
        payload: JsonElement? = null,
        sender: String? = null
    ): SendResult {
        val message = ChannelMessage(
            type = type,
            payload = payload,
            sender = sender,
            target = name,
            timestamp = Clock.System.now().toEpochMilliseconds()
        )
        return layer.groupSendMessage(name, message)
    }
    
    /**
     * Send a message to all sessions except the specified one.
     */
    suspend fun broadcastExcept(
        message: String,
        except: WebSocketSession
    ): SendResult {
        val sessions = layer.getGroupSessions(name) - except
        var sent = 0
        var failed = 0
        val errors = mutableListOf<Throwable>()
        
        for (session in sessions) {
            if (!session.isOpen) {
                failed++
                continue
            }
            try {
                session.sendText(message)
                sent++
            } catch (e: Exception) {
                failed++
                errors.add(e)
            }
        }
        
        return SendResult(sent, failed, errors)
    }
    
    /**
     * Get all sessions in this group.
     */
    suspend fun sessions(): Set<WebSocketSession> {
        return layer.getGroupSessions(name)
    }
    
    /**
     * Get the number of sessions in this group.
     */
    suspend fun size(): Int {
        return layer.groupSize(name)
    }
    
    /**
     * Check if a session is in this group.
     */
    suspend fun contains(session: WebSocketSession): Boolean {
        return layer.isInGroup(name, session)
    }
}

/**
 * Extension to easily add a session to a group.
 */
suspend fun WebSocketSession.joinGroup(group: String, layer: ChannelLayer = Channels.layer) {
    layer.groupAdd(group, this)
}

/**
 * Extension to easily leave a group.
 */
suspend fun WebSocketSession.leaveGroup(group: String, layer: ChannelLayer = Channels.layer) {
    layer.groupDiscard(group, this)
}

/**
 * Extension to leave all groups.
 */
suspend fun WebSocketSession.leaveAllGroups(layer: ChannelLayer = Channels.layer) {
    layer.discardAll(this)
}

/**
 * Extension to get all groups a session belongs to.
 */
suspend fun WebSocketSession.groups(layer: ChannelLayer = Channels.layer): Set<String> {
    return layer.getSessionGroups(this)
}
