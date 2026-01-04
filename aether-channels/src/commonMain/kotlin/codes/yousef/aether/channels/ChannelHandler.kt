package codes.yousef.aether.channels

import codes.yousef.aether.core.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json

/**
 * A WebSocket handler that automatically manages channel group membership.
 * 
 * Example:
 * ```kotlin
 * router.ws("/chat/:roomId") {
 *     onConnect { session ->
 *         val roomId = session.pathParam("roomId")
 *         Channels.group("chat_$roomId").add(session)
 *         Channels.group("chat_$roomId").broadcast("User joined!")
 *     }
 *     
 *     onText { session, text ->
 *         val roomId = session.pathParam("roomId")
 *         Channels.group("chat_$roomId").broadcastExcept("User: $text", session)
 *     }
 *     
 *     onClose { session, _, _ ->
 *         // Automatically leaves all groups
 *     }
 * }
 * ```
 */
class ChannelWebSocketHandler(
    private val layer: ChannelLayer = Channels.layer,
    private val autoLeaveOnClose: Boolean = true,
    private val onConnectHandler: suspend (WebSocketSession) -> Unit = {},
    private val onTextHandler: suspend (WebSocketSession, String) -> Unit = { _, _ -> },
    private val onBinaryHandler: suspend (WebSocketSession, ByteArray) -> Unit = { _, _ -> },
    private val onCloseHandler: suspend (WebSocketSession, Int, String) -> Unit = { _, _, _ -> },
    private val onErrorHandler: suspend (WebSocketSession, Throwable) -> Unit = { _, _ -> }
) : WebSocketHandler {
    
    override suspend fun onConnect(session: WebSocketSession) {
        onConnectHandler(session)
    }

    override suspend fun onText(session: WebSocketSession, text: String) {
        onTextHandler(session, text)
    }

    override suspend fun onBinary(session: WebSocketSession, data: ByteArray) {
        onBinaryHandler(session, data)
    }

    override suspend fun onClose(session: WebSocketSession, code: Int, reason: String) {
        try {
            onCloseHandler(session, code, reason)
        } finally {
            if (autoLeaveOnClose) {
                layer.discardAll(session)
            }
        }
    }

    override suspend fun onError(session: WebSocketSession, error: Throwable) {
        try {
            onErrorHandler(session, error)
        } finally {
            if (autoLeaveOnClose) {
                layer.discardAll(session)
            }
        }
    }
}

/**
 * Builder for creating channel-aware WebSocket handlers.
 */
class ChannelWebSocketHandlerBuilder(
    private val layer: ChannelLayer = Channels.layer
) {
    private var autoLeaveOnClose = true
    private var onConnectHandler: suspend (WebSocketSession) -> Unit = {}
    private var onTextHandler: suspend (WebSocketSession, String) -> Unit = { _, _ -> }
    private var onBinaryHandler: suspend (WebSocketSession, ByteArray) -> Unit = { _, _ -> }
    private var onCloseHandler: suspend (WebSocketSession, Int, String) -> Unit = { _, _, _ -> }
    private var onErrorHandler: suspend (WebSocketSession, Throwable) -> Unit = { _, _ -> }
    
    /**
     * Whether to automatically remove session from all groups on close/error.
     */
    fun autoLeaveOnClose(value: Boolean) {
        autoLeaveOnClose = value
    }
    
    /**
     * Handler called when connection is established.
     */
    fun onConnect(handler: suspend (WebSocketSession) -> Unit) {
        onConnectHandler = handler
    }
    
    /**
     * Handler called when text message is received.
     */
    fun onText(handler: suspend (WebSocketSession, String) -> Unit) {
        onTextHandler = handler
    }
    
    /**
     * Handler called when binary message is received.
     */
    fun onBinary(handler: suspend (WebSocketSession, ByteArray) -> Unit) {
        onBinaryHandler = handler
    }
    
    /**
     * Handler called when connection is closed.
     */
    fun onClose(handler: suspend (WebSocketSession, Int, String) -> Unit) {
        onCloseHandler = handler
    }
    
    /**
     * Handler called when error occurs.
     */
    fun onError(handler: suspend (WebSocketSession, Throwable) -> Unit) {
        onErrorHandler = handler
    }
    
    fun build(): ChannelWebSocketHandler {
        return ChannelWebSocketHandler(
            layer = layer,
            autoLeaveOnClose = autoLeaveOnClose,
            onConnectHandler = onConnectHandler,
            onTextHandler = onTextHandler,
            onBinaryHandler = onBinaryHandler,
            onCloseHandler = onCloseHandler,
            onErrorHandler = onErrorHandler
        )
    }
}

/**
 * Create a channel-aware WebSocket handler.
 */
fun channelHandler(
    layer: ChannelLayer = Channels.layer,
    block: ChannelWebSocketHandlerBuilder.() -> Unit
): ChannelWebSocketHandler {
    return ChannelWebSocketHandlerBuilder(layer).apply(block).build()
}

/**
 * JSON message handler for structured WebSocket communication.
 */
class JsonMessageHandler(
    private val layer: ChannelLayer = Channels.layer,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val handlers = mutableMapOf<String, suspend (WebSocketSession, ChannelMessage) -> Unit>()
    
    /**
     * Register a handler for a specific message type.
     */
    fun on(type: String, handler: suspend (WebSocketSession, ChannelMessage) -> Unit) {
        handlers[type] = handler
    }
    
    /**
     * Handle an incoming text message.
     */
    suspend fun handle(session: WebSocketSession, text: String) {
        try {
            val message = json.decodeFromString(ChannelMessage.serializer(), text)
            val handler = handlers[message.type]
            if (handler != null) {
                handler(session, message)
            }
        } catch (e: Exception) {
            // Invalid JSON or unhandled type - ignore or log
        }
    }
    
    /**
     * Create a WebSocket handler from this message handler.
     */
    fun toWebSocketHandler(): ChannelWebSocketHandler {
        return channelHandler(layer) {
            onText { session, text ->
                handle(session, text)
            }
        }
    }
}
