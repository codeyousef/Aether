package codes.yousef.aether.channels

import codes.yousef.aether.core.websocket.WebSocketSession
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory implementation of ChannelLayer.
 * 
 * This implementation stores all group memberships in memory, making it
 * suitable for single-server deployments and development/testing.
 * 
 * For multi-server deployments, use RedisChannelLayer or another
 * distributed implementation.
 * 
 * Example:
 * ```kotlin
 * val channels = InMemoryChannelLayer()
 * 
 * // Add session to a chat room
 * channels.groupAdd("chat_room_1", session)
 * 
 * // Broadcast to all in room
 * channels.groupSend("chat_room_1", "Hello everyone!")
 * 
 * // Remove session when disconnected
 * channels.discardAll(session)
 * ```
 */
class InMemoryChannelLayer : ChannelLayer {
    
    // Group name -> Set of sessions
    private val groups = atomic(mapOf<String, Set<WebSocketSession>>())
    
    // Session ID -> Set of group names (for reverse lookup)
    private val sessionGroups = atomic(mapOf<String, Set<String>>())
    
    private val mutex = Mutex()
    
    override suspend fun groupAdd(group: String, session: WebSocketSession) {
        mutex.withLock {
            // Add session to group
            groups.update { current ->
                val existing = current[group] ?: emptySet()
                current + (group to (existing + session))
            }
            
            // Track which groups this session is in
            sessionGroups.update { current ->
                val existing = current[session.id] ?: emptySet()
                current + (session.id to (existing + group))
            }
        }
    }

    override suspend fun groupDiscard(group: String, session: WebSocketSession) {
        mutex.withLock {
            // Remove session from group
            groups.update { current ->
                val existing = current[group] ?: return@update current
                val updated = existing - session
                if (updated.isEmpty()) {
                    current - group
                } else {
                    current + (group to updated)
                }
            }
            
            // Update session's group tracking
            sessionGroups.update { current ->
                val existing = current[session.id] ?: return@update current
                val updated = existing - group
                if (updated.isEmpty()) {
                    current - session.id
                } else {
                    current + (session.id to updated)
                }
            }
        }
    }

    override suspend fun discardAll(session: WebSocketSession) {
        mutex.withLock {
            val memberGroups = sessionGroups.value[session.id] ?: return
            
            // Remove from all groups
            groups.update { current ->
                var updated = current
                for (group in memberGroups) {
                    val existing = updated[group] ?: continue
                    val newSet = existing - session
                    updated = if (newSet.isEmpty()) {
                        updated - group
                    } else {
                        updated + (group to newSet)
                    }
                }
                updated
            }
            
            // Remove session tracking
            sessionGroups.update { it - session.id }
        }
    }

    override suspend fun groupSend(
        group: String,
        message: String,
        options: ChannelOptions
    ): SendResult {
        val sessions = getGroupSessions(group)
        if (sessions.isEmpty()) {
            return SendResult(0, 0)
        }
        
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
                if (options.throwOnError) {
                    throw e
                }
            }
        }
        
        return SendResult(sent, failed, errors)
    }

    override suspend fun groupSendBinary(
        group: String,
        data: ByteArray,
        options: ChannelOptions
    ): SendResult {
        val sessions = getGroupSessions(group)
        if (sessions.isEmpty()) {
            return SendResult(0, 0)
        }
        
        var sent = 0
        var failed = 0
        val errors = mutableListOf<Throwable>()
        
        for (session in sessions) {
            if (!session.isOpen) {
                failed++
                continue
            }
            
            try {
                session.sendBinary(data)
                sent++
            } catch (e: Exception) {
                failed++
                errors.add(e)
                if (options.throwOnError) {
                    throw e
                }
            }
        }
        
        return SendResult(sent, failed, errors)
    }

    override suspend fun getGroupSessions(group: String): Set<WebSocketSession> {
        return groups.value[group] ?: emptySet()
    }

    override suspend fun getSessionGroups(session: WebSocketSession): Set<String> {
        return sessionGroups.value[session.id] ?: emptySet()
    }

    override suspend fun groupSize(group: String): Int {
        return groups.value[group]?.size ?: 0
    }

    override suspend fun isInGroup(group: String, session: WebSocketSession): Boolean {
        return groups.value[group]?.contains(session) == true
    }

    override suspend fun getAllGroups(): Set<String> {
        return groups.value.keys
    }

    override suspend fun close() {
        mutex.withLock {
            groups.update { emptyMap() }
            sessionGroups.update { emptyMap() }
        }
    }
    
    /**
     * Get total number of tracked sessions.
     */
    fun totalSessions(): Int = sessionGroups.value.size
    
    /**
     * Get total number of groups.
     */
    fun totalGroups(): Int = groups.value.size
}
