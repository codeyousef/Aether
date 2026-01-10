package codes.yousef.aether.core.websocket

import io.vertx.core.Vertx
import io.vertx.core.http.ServerWebSocket
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM implementation of WebSocketSession using Vert.x.
 */
class VertxWebSocketSession(
    private val socket: ServerWebSocket,
    private val scope: CoroutineScope
) : WebSocketSession {
    override val id: String = UUID.randomUUID().toString()
    override val path: String = socket.path()
    override val queryParameters: Map<String, List<String>> = parseQueryParams(socket.query())
    override val headers: Map<String, String> = socket.headers().associate { it.key to it.value }
    override val attributes: MutableMap<String, Any?> = ConcurrentHashMap()

    private val incomingChannel = Channel<WebSocketMessage>(Channel.UNLIMITED)
    private var _isOpen = true

    override val isOpen: Boolean
        get() = _isOpen && !socket.isClosed

    init {
        setupHandlers()
    }

    private fun setupHandlers() {
        socket.textMessageHandler { text ->
            scope.launch {
                incomingChannel.send(WebSocketMessage.Text(text))
            }
        }

        socket.binaryMessageHandler { buffer ->
            scope.launch {
                incomingChannel.send(WebSocketMessage.Binary(buffer.bytes))
            }
        }

        socket.pongHandler { buffer ->
            scope.launch {
                incomingChannel.send(WebSocketMessage.Pong(buffer.bytes))
            }
        }

        socket.closeHandler {
            _isOpen = false
            scope.launch {
                incomingChannel.send(
                    WebSocketMessage.Close(
                        socket.closeStatusCode()?.toInt() ?: 1000,
                        socket.closeReason() ?: ""
                    )
                )
                incomingChannel.close()
            }
        }

        socket.exceptionHandler { error ->
            scope.launch {
                incomingChannel.close(error)
            }
        }
    }

    override suspend fun sendText(text: String) {
        if (isOpen) {
            socket.writeTextMessage(text).coAwait()
        }
    }

    override suspend fun sendBinary(data: ByteArray) {
        if (isOpen) {
            socket.writeBinaryMessage(io.vertx.core.buffer.Buffer.buffer(data)).coAwait()
        }
    }

    override suspend fun sendPing(data: ByteArray) {
        if (isOpen) {
            socket.writePing(io.vertx.core.buffer.Buffer.buffer(data)).coAwait()
        }
    }

    override suspend fun sendPong(data: ByteArray) {
        if (isOpen) {
            socket.writePong(io.vertx.core.buffer.Buffer.buffer(data)).coAwait()
        }
    }

    override suspend fun close(code: Int, reason: String) {
        if (isOpen) {
            _isOpen = false
            socket.close(code.toShort(), reason).coAwait()
        }
    }

    override fun incoming(): Flow<WebSocketMessage> = incomingChannel.consumeAsFlow()

    private fun parseQueryParams(query: String?): Map<String, List<String>> {
        if (query.isNullOrBlank()) return emptyMap()
        
        return query.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index > 0) {
                    val name = part.substring(0, index)
                    val value = part.substring(index + 1)
                    name to value
                } else {
                    null
                }
            }
            .groupBy({ it.first }, { it.second })
    }
}

/**
 * WebSocket server using Vert.x.
 */
class VertxWebSocketServer(
    private val vertx: Vertx,
    private val config: WebSocketConfig = WebSocketConfig()
) {
    private val handlers = ConcurrentHashMap<String, WebSocketHandler>()
    private val sessions = ConcurrentHashMap<String, VertxWebSocketSession>()

    /**
     * Register a WebSocket handler for a path.
     */
    fun registerHandler(path: String, handler: WebSocketHandler) {
        handlers[path] = handler
    }

    /**
     * Handle a WebSocket upgrade request.
     * Returns true if the request was handled, false otherwise.
     */
    @Suppress("DEPRECATION")
    suspend fun handleUpgrade(
        socket: ServerWebSocket,
        scope: CoroutineScope
    ): Boolean {
        val path = socket.path()
        val handler = findHandler(path) ?: return false

        // Check origin if configured
        if (config.allowedOrigins.isNotEmpty()) {
            val origin = socket.headers()["Origin"]
            if (origin != null && origin !in config.allowedOrigins) {
                socket.reject(403)
                return true
            }
        }

        // Accept the WebSocket connection
        socket.accept()

        val session = VertxWebSocketSession(socket, scope)
        sessions[session.id] = session

        scope.launch {
            try {
                handler.onConnect(session)

                session.incoming().collect { message ->
                    when (message) {
                        is WebSocketMessage.Text -> handler.onText(session, message.content)
                        is WebSocketMessage.Binary -> handler.onBinary(session, message.data)
                        is WebSocketMessage.Ping -> handler.onPing(session, message.data)
                        is WebSocketMessage.Pong -> handler.onPong(session, message.data)
                        is WebSocketMessage.Close -> {
                            handler.onClose(session, message.code, message.reason)
                        }
                    }
                }
            } catch (e: Exception) {
                handler.onError(session, e)
            } finally {
                sessions.remove(session.id)
            }
        }

        return true
    }

    private fun findHandler(path: String): WebSocketHandler? {
        // Exact match first
        handlers[path]?.let { return it }

        // Pattern matching
        for ((pattern, handler) in handlers) {
            if (matchPath(pattern, path)) {
                return handler
            }
        }

        return null
    }

    private fun matchPath(pattern: String, path: String): Boolean {
        val patternParts = pattern.split("/").filter { it.isNotEmpty() }
        val pathParts = path.split("/").filter { it.isNotEmpty() }

        if (patternParts.size != pathParts.size) {
            return false
        }

        for (i in patternParts.indices) {
            val patternPart = patternParts[i]
            val pathPart = pathParts[i]

            if (patternPart.startsWith(":") || 
                (patternPart.startsWith("{") && patternPart.endsWith("}"))) {
                continue
            }

            if (patternPart != pathPart) {
                return false
            }
        }

        return true
    }

    /**
     * Get all active sessions.
     */
    fun getSessions(): Collection<WebSocketSession> = sessions.values

    /**
     * Get a session by ID.
     */
    fun getSession(id: String): WebSocketSession? = sessions[id]

    /**
     * Broadcast a message to all sessions.
     */
    suspend fun broadcast(message: String) {
        sessions.values.forEach { session ->
            if (session.isOpen) {
                try {
                    session.sendText(message)
                } catch (e: Exception) {
                    // Ignore errors during broadcast
                }
            }
        }
    }

    /**
     * Broadcast binary data to all sessions.
     */
    suspend fun broadcastBinary(data: ByteArray) {
        sessions.values.forEach { session ->
            if (session.isOpen) {
                try {
                    session.sendBinary(data)
                } catch (e: Exception) {
                    // Ignore errors during broadcast
                }
            }
        }
    }
}
