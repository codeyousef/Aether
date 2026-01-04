package codes.yousef.aether.channels

import codes.yousef.aether.core.websocket.WebSocketSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A message sent through a channel.
 */
@Serializable
data class ChannelMessage(
    /** Message type for routing */
    val type: String,
    
    /** Message payload */
    val payload: JsonElement? = null,
    
    /** Optional sender identifier */
    val sender: String? = null,
    
    /** Optional target group or channel */
    val target: String? = null,
    
    /** Timestamp when message was created (epoch millis) */
    val timestamp: Long = 0
)

/**
 * Result of sending a message to a group.
 */
data class SendResult(
    /** Number of sessions the message was sent to */
    val sentTo: Int,
    
    /** Number of sessions that failed to receive */
    val failed: Int,
    
    /** Errors encountered during send */
    val errors: List<Throwable> = emptyList()
)

/**
 * Options for channel layer operations.
 */
data class ChannelOptions(
    /** Whether to include the sender in broadcast */
    val includeSender: Boolean = false,
    
    /** Whether to throw on send errors */
    val throwOnError: Boolean = false
)

/**
 * Interface for channel layer implementations.
 * 
 * A channel layer provides pub/sub functionality for WebSocket sessions,
 * allowing sessions to be organized into groups and receive messages.
 */
interface ChannelLayer {
    /**
     * Add a session to a group.
     * 
     * @param group The group name
     * @param session The WebSocket session to add
     */
    suspend fun groupAdd(group: String, session: WebSocketSession)
    
    /**
     * Remove a session from a group.
     * 
     * @param group The group name
     * @param session The WebSocket session to remove
     */
    suspend fun groupDiscard(group: String, session: WebSocketSession)
    
    /**
     * Remove a session from all groups.
     * 
     * @param session The WebSocket session to remove
     */
    suspend fun discardAll(session: WebSocketSession)
    
    /**
     * Send a text message to all sessions in a group.
     * 
     * @param group The group name
     * @param message The message to send
     * @param options Send options
     * @return Result of the send operation
     */
    suspend fun groupSend(
        group: String, 
        message: String,
        options: ChannelOptions = ChannelOptions()
    ): SendResult
    
    /**
     * Send a binary message to all sessions in a group.
     * 
     * @param group The group name
     * @param data The binary data to send
     * @param options Send options
     * @return Result of the send operation
     */
    suspend fun groupSendBinary(
        group: String,
        data: ByteArray,
        options: ChannelOptions = ChannelOptions()
    ): SendResult
    
    /**
     * Get all sessions in a group.
     * 
     * @param group The group name
     * @return Set of sessions in the group
     */
    suspend fun getGroupSessions(group: String): Set<WebSocketSession>
    
    /**
     * Get all groups a session belongs to.
     * 
     * @param session The WebSocket session
     * @return Set of group names
     */
    suspend fun getSessionGroups(session: WebSocketSession): Set<String>
    
    /**
     * Get the number of sessions in a group.
     * 
     * @param group The group name
     * @return Number of sessions
     */
    suspend fun groupSize(group: String): Int
    
    /**
     * Check if a session is in a group.
     * 
     * @param group The group name
     * @param session The WebSocket session
     * @return true if session is in group
     */
    suspend fun isInGroup(group: String, session: WebSocketSession): Boolean
    
    /**
     * Get all active group names.
     * 
     * @return Set of group names
     */
    suspend fun getAllGroups(): Set<String>
    
    /**
     * Close and cleanup the channel layer.
     */
    suspend fun close()
}

/**
 * Extension function to send a ChannelMessage as JSON.
 */
suspend fun ChannelLayer.groupSendMessage(
    group: String,
    message: ChannelMessage,
    options: ChannelOptions = ChannelOptions()
): SendResult {
    val json = kotlinx.serialization.json.Json.encodeToString(ChannelMessage.serializer(), message)
    return groupSend(group, json, options)
}
