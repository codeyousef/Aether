package codes.yousef.aether.core.websocket

import kotlinx.coroutines.flow.Flow

/**
 * Represents a WebSocket message.
 */
sealed class WebSocketMessage {
    /**
     * Text message.
     */
    data class Text(val content: String) : WebSocketMessage()

    /**
     * Binary message.
     */
    data class Binary(val data: ByteArray) : WebSocketMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    /**
     * Ping message.
     */
    data class Ping(val data: ByteArray = ByteArray(0)) : WebSocketMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ping) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    /**
     * Pong message.
     */
    data class Pong(val data: ByteArray = ByteArray(0)) : WebSocketMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Pong) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    /**
     * Close message.
     */
    data class Close(val code: Int = 1000, val reason: String = "") : WebSocketMessage()
}

/**
 * WebSocket close codes.
 */
object WebSocketCloseCode {
    const val NORMAL = 1000
    const val GOING_AWAY = 1001
    const val PROTOCOL_ERROR = 1002
    const val UNSUPPORTED_DATA = 1003
    const val NO_STATUS = 1005
    const val ABNORMAL_CLOSURE = 1006
    const val INVALID_PAYLOAD = 1007
    const val POLICY_VIOLATION = 1008
    const val MESSAGE_TOO_BIG = 1009
    const val MANDATORY_EXTENSION = 1010
    const val INTERNAL_ERROR = 1011
    const val SERVICE_RESTART = 1012
    const val TRY_AGAIN_LATER = 1013
    const val BAD_GATEWAY = 1014
    const val TLS_HANDSHAKE_FAILURE = 1015
}

/**
 * Represents a WebSocket session.
 */
interface WebSocketSession {
    /**
     * Unique identifier for this session.
     */
    val id: String

    /**
     * The path this WebSocket is connected to.
     */
    val path: String

    /**
     * Query parameters from the WebSocket URL.
     */
    val queryParameters: Map<String, List<String>>

    /**
     * Headers from the WebSocket handshake.
     */
    val headers: Map<String, String>

    /**
     * Custom attributes for storing session-specific data.
     */
    val attributes: MutableMap<String, Any?>

    /**
     * Whether the WebSocket connection is open.
     */
    val isOpen: Boolean

    /**
     * Send a text message.
     */
    suspend fun sendText(text: String)

    /**
     * Send a binary message.
     */
    suspend fun sendBinary(data: ByteArray)

    /**
     * Send a ping message.
     */
    suspend fun sendPing(data: ByteArray = ByteArray(0))

    /**
     * Send a pong message.
     */
    suspend fun sendPong(data: ByteArray = ByteArray(0))

    /**
     * Close the WebSocket connection.
     */
    suspend fun close(code: Int = WebSocketCloseCode.NORMAL, reason: String = "")

    /**
     * Receive incoming messages as a Flow.
     */
    fun incoming(): Flow<WebSocketMessage>
}

/**
 * WebSocket handler interface.
 */
interface WebSocketHandler {
    /**
     * Called when a new WebSocket connection is established.
     */
    suspend fun onConnect(session: WebSocketSession)

    /**
     * Called when a text message is received.
     */
    suspend fun onText(session: WebSocketSession, text: String)

    /**
     * Called when a binary message is received.
     */
    suspend fun onBinary(session: WebSocketSession, data: ByteArray)

    /**
     * Called when a ping message is received.
     * Default implementation sends a pong response.
     */
    suspend fun onPing(session: WebSocketSession, data: ByteArray) {
        session.sendPong(data)
    }

    /**
     * Called when a pong message is received.
     * Default implementation does nothing.
     */
    suspend fun onPong(session: WebSocketSession, data: ByteArray) {}

    /**
     * Called when the WebSocket connection is closed.
     */
    suspend fun onClose(session: WebSocketSession, code: Int, reason: String)

    /**
     * Called when an error occurs.
     */
    suspend fun onError(session: WebSocketSession, error: Throwable)
}

/**
 * Simple WebSocket handler that can be created with lambda functions.
 */
class LambdaWebSocketHandler(
    private val onConnectHandler: suspend (WebSocketSession) -> Unit = {},
    private val onTextHandler: suspend (WebSocketSession, String) -> Unit = { _, _ -> },
    private val onBinaryHandler: suspend (WebSocketSession, ByteArray) -> Unit = { _, _ -> },
    private val onCloseHandler: suspend (WebSocketSession, Int, String) -> Unit = { _, _, _ -> },
    private val onErrorHandler: suspend (WebSocketSession, Throwable) -> Unit = { _, _ -> }
) : WebSocketHandler {
    override suspend fun onConnect(session: WebSocketSession) = onConnectHandler(session)
    override suspend fun onText(session: WebSocketSession, text: String) = onTextHandler(session, text)
    override suspend fun onBinary(session: WebSocketSession, data: ByteArray) = onBinaryHandler(session, data)
    override suspend fun onClose(session: WebSocketSession, code: Int, reason: String) = onCloseHandler(session, code, reason)
    override suspend fun onError(session: WebSocketSession, error: Throwable) = onErrorHandler(session, error)
}

/**
 * Configuration for WebSocket server.
 */
data class WebSocketConfig(
    /**
     * Maximum frame size in bytes.
     */
    val maxFrameSize: Int = 65536,

    /**
     * Maximum message size in bytes (for fragmented messages).
     */
    val maxMessageSize: Int = 1024 * 1024,

    /**
     * Ping interval in milliseconds (0 = disabled).
     */
    val pingIntervalMs: Long = 30000,

    /**
     * Pong timeout in milliseconds.
     */
    val pongTimeoutMs: Long = 10000,

    /**
     * Allowed origins (empty = all allowed).
     */
    val allowedOrigins: Set<String> = emptySet(),

    /**
     * Subprotocols to negotiate.
     */
    val subprotocols: List<String> = emptyList()
)

/**
 * DSL builder for WebSocket handler.
 */
class WebSocketHandlerBuilder {
    private var onConnectHandler: suspend (WebSocketSession) -> Unit = {}
    private var onTextHandler: suspend (WebSocketSession, String) -> Unit = { _, _ -> }
    private var onBinaryHandler: suspend (WebSocketSession, ByteArray) -> Unit = { _, _ -> }
    private var onCloseHandler: suspend (WebSocketSession, Int, String) -> Unit = { _, _, _ -> }
    private var onErrorHandler: suspend (WebSocketSession, Throwable) -> Unit = { _, _ -> }

    fun onConnect(handler: suspend (WebSocketSession) -> Unit) {
        onConnectHandler = handler
    }

    fun onText(handler: suspend (WebSocketSession, String) -> Unit) {
        onTextHandler = handler
    }

    fun onBinary(handler: suspend (WebSocketSession, ByteArray) -> Unit) {
        onBinaryHandler = handler
    }

    fun onClose(handler: suspend (WebSocketSession, Int, String) -> Unit) {
        onCloseHandler = handler
    }

    fun onError(handler: suspend (WebSocketSession, Throwable) -> Unit) {
        onErrorHandler = handler
    }

    fun build(): WebSocketHandler = LambdaWebSocketHandler(
        onConnectHandler,
        onTextHandler,
        onBinaryHandler,
        onCloseHandler,
        onErrorHandler
    )
}

/**
 * DSL function to create a WebSocket handler.
 */
fun webSocketHandler(block: WebSocketHandlerBuilder.() -> Unit): WebSocketHandler {
    return WebSocketHandlerBuilder().apply(block).build()
}
