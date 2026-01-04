````markdown
# Channels API

The `aether-channels` module provides a WebSocket pub/sub layer for real-time group messaging.

## Overview

Channels enable you to organize WebSocket connections into groups and broadcast messages to all members of a group. This is perfect for chat rooms, live notifications, collaborative editing, and real-time dashboards.

## Installation

```kotlin
// build.gradle.kts
implementation("codes.yousef.aether:aether-channels:0.4.0.1")
```

## Basic Usage

### Setting Up the Channel Layer

```kotlin
import codes.yousef.aether.channels.*

// Use the singleton (configured with InMemoryChannelLayer by default)
Channels.configure(InMemoryChannelLayer())

// Or create your own instance
val channelLayer = InMemoryChannelLayer()
```

### Managing Group Membership

```kotlin
// In your WebSocket handler
router {
    ws("/chat/:room") { session ->
        val room = session.pathParam("room")
        
        // Add session to a group
        Channels.groupAdd("room:$room", session.id)
        
        try {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        // Broadcast to the room
                        Channels.group("room:$room").broadcast(frame.readText())
                    }
                }
            }
        } finally {
            // Remove from group on disconnect
            Channels.groupDiscard("room:$room", session.id)
        }
    }
}
```

### Broadcasting Messages

```kotlin
// Broadcast text to a group
Channels.group("room:general").broadcast("Hello everyone!")

// Broadcast with sender info
Channels.group("room:general").broadcast(
    message = "Hello!",
    sender = userId
)

// Broadcast binary data
Channels.group("room:general").broadcastBinary(imageBytes)

// Send to a specific session
Channels.send(sessionId, "Private message")
```

## Channel Layer Interface

```kotlin
interface ChannelLayer {
    // Group management
    suspend fun groupAdd(group: String, sessionId: String)
    suspend fun groupDiscard(group: String, sessionId: String)
    suspend fun groupMembers(group: String): Set<String>
    
    // Messaging
    suspend fun groupSend(group: String, message: String): SendResult
    suspend fun groupSendBinary(group: String, data: ByteArray): SendResult
    suspend fun send(sessionId: String, message: String): Boolean
    
    // Session management
    fun registerSession(sessionId: String, sender: suspend (String) -> Unit)
    fun unregisterSession(sessionId: String)
}
```

## SendResult

Track the outcome of broadcast operations:

```kotlin
data class SendResult(
    val sentCount: Int,       // Successfully delivered
    val failedCount: Int,     // Failed to deliver
    val errors: List<Throwable>  // Exceptions encountered
)

val result = Channels.group("room:lobby").broadcast("Hello!")
println("Sent to ${result.sentCount} clients, ${result.failedCount} failed")
```

## ChannelMessage

Structured messages with metadata:

```kotlin
data class ChannelMessage(
    val type: String,              // Message type identifier
    val payload: String,           // Message content (JSON, text, etc.)
    val sender: String? = null,    // Sender identifier
    val target: String? = null,    // Target group or session
    val timestamp: Long            // Unix timestamp
)

// Send structured message
val message = ChannelMessage(
    type = "chat.message",
    payload = Json.encodeToString(ChatPayload(text = "Hello!")),
    sender = userId,
    target = "room:general"
)
Channels.group("room:general").broadcast(Json.encodeToString(message))
```

## The Channels Singleton

For convenience, use the `Channels` singleton:

```kotlin
// Configure once at startup
Channels.configure(InMemoryChannelLayer())

// Use throughout your app
Channels.groupAdd("notifications:user:123", sessionId)
Channels.group("notifications:user:123").broadcast(notification)
```

## Group Patterns

### Chat Rooms

```kotlin
// Join room
Channels.groupAdd("chat:$roomId", sessionId)

// Leave room  
Channels.groupDiscard("chat:$roomId", sessionId)

// Broadcast message
Channels.group("chat:$roomId").broadcast(message)
```

### User Notifications

```kotlin
// User connects - add to personal channel
Channels.groupAdd("user:$userId", sessionId)

// Send notification to user (all their devices)
Channels.group("user:$userId").broadcast(notification)
```

### Live Dashboard

```kotlin
// All admin dashboards
Channels.groupAdd("dashboard:admin", sessionId)

// Broadcast real-time updates
Channels.group("dashboard:admin").broadcast(Json.encodeToString(statsUpdate))
```

### Presence

```kotlin
// Track online users in a room
val onlineUsers = Channels.groupMembers("room:$roomId")
println("${onlineUsers.size} users online")
```

## Integration with WebSockets

Full example combining WebSockets and Channels:

```kotlin
router {
    ws("/realtime") { session ->
        val userId = session.attributes[UserKey]?.id ?: return@ws session.close()
        
        // Register session for receiving messages
        Channels.registerSession(session.id) { message ->
            session.send(Frame.Text(message))
        }
        
        // Join user's personal channel
        Channels.groupAdd("user:$userId", session.id)
        
        try {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val cmd = Json.decodeFromString<Command>(frame.readText())
                        when (cmd.action) {
                            "join" -> Channels.groupAdd(cmd.group, session.id)
                            "leave" -> Channels.groupDiscard(cmd.group, session.id)
                            "send" -> Channels.group(cmd.group).broadcast(cmd.message)
                        }
                    }
                }
            }
        } finally {
            Channels.unregisterSession(session.id)
        }
    }
}
```

## Scaling Considerations

`InMemoryChannelLayer` works for single-server deployments. For multi-server setups, you'll need a distributed backend:

```kotlin
// Future: Redis-backed channel layer
val channelLayer = RedisChannelLayer(
    host = "redis.example.com",
    port = 6379
)
Channels.configure(channelLayer)
```

## Best Practices

1. **Use meaningful group names** - `room:$id`, `user:$id`, `dashboard:$type`
2. **Always clean up** - Call `groupDiscard` when sessions disconnect
3. **Handle errors** - Check `SendResult` for failed deliveries
4. **Keep messages small** - Large payloads slow down broadcasts
5. **Consider message format** - Use JSON for structured data

````
