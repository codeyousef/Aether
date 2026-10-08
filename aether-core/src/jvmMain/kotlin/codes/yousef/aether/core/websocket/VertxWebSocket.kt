package codes.yousef.aether.core.websocket

import io.vertx.core.Vertx
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.http.ServerWebSocketHandshake
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM implementation of WebSocketSession using Vert.x.
 */
class VertxWebSocketSession(
    private val socket: ServerWebSocket,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    private val config: WebSocketConfig = WebSocketConfig()
) : WebSocketSession {
    override val id: String = UUID.randomUUID().toString()
    override val path: String = socket.path()
    override val queryParameters: Map<String, List<String>> = parseWebSocketQuery(socket.query())
    override val headers: Map<String, String> = socket.headers().associate { it.key to it.value }
    override val attributes: MutableMap<String, Any?> = ConcurrentHashMap()

    private val incomingChannel = Channel<WebSocketMessage>(config.maxPendingMessages)
    private val terminated = AtomicBoolean(false)
    @Volatile
    private var _isOpen = true

    override val isOpen: Boolean
        get() = _isOpen && !socket.isClosed

    init {
        setupHandlers()
    }

    private fun setupHandlers() {
        socket.textMessageHandler { text ->
            enqueue(WebSocketMessage.Text(text), text.encodeToByteArray().size)
        }

        socket.binaryMessageHandler { buffer ->
            enqueue(WebSocketMessage.Binary(buffer.bytes), buffer.length())
        }

        socket.pongHandler { buffer ->
            enqueue(WebSocketMessage.Pong(buffer.bytes), buffer.length())
        }

        socket.closeHandler {
            finish(
                WebSocketMessage.Close(
                    socket.closeStatusCode()?.toInt() ?: WebSocketCloseCode.NORMAL,
                    socket.closeReason() ?: ""
                )
            )
        }

        socket.exceptionHandler { error ->
            if (finish(error = error) && !socket.isClosed) {
                socket.close(WebSocketCloseCode.INTERNAL_ERROR.toShort(), "transport_error")
            }
        }
    }

    private fun enqueue(message: WebSocketMessage, sizeBytes: Int) {
        if (sizeBytes > config.maxMessageSize) {
            overload(WebSocketCloseCode.MESSAGE_TOO_BIG, "message_too_big")
        } else if (incomingChannel.trySend(message).isFailure) {
            overload(WebSocketCloseCode.TRY_AGAIN_LATER, "inbound_queue_full")
        }
    }

    private fun overload(code: Int, reason: String) {
        if (finish(WebSocketMessage.Close(code, reason)) && !socket.isClosed) {
            socket.close(code.toShort(), reason)
        }
    }

    private fun finish(close: WebSocketMessage.Close? = null, error: Throwable? = null): Boolean {
        if (!terminated.compareAndSet(false, true)) return false
        _isOpen = false
        if (error != null) {
            incomingChannel.close(error)
        } else {
            val closeMessage = close ?: WebSocketMessage.Close()
            if (incomingChannel.trySend(closeMessage).isFailure) {
                incomingChannel.tryReceive()
                incomingChannel.trySend(closeMessage)
            }
            incomingChannel.close()
        }
        return true
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
        if (finish(WebSocketMessage.Close(code, reason)) && !socket.isClosed) {
            socket.close(code.toShort(), reason).coAwait()
        }
    }

    override fun incoming(): Flow<WebSocketMessage> = incomingChannel.consumeAsFlow()

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
    suspend fun handleHandshake(
        handshake: ServerWebSocketHandshake,
        scope: CoroutineScope
    ): Boolean {
        val path = handshake.path()
        val route = findRoute(path) ?: return false

        val origin = handshake.headers()["Origin"]
        if (config.allowedOrigins.isNotEmpty() && (origin == null || origin !in config.allowedOrigins)) {
            handshake.reject(403).coAwait()
            return true
        }
        if (containsBearerCredential(handshake.query())) {
            handshake.reject(400).coAwait()
            return true
        }
        val authorization = config.authorizer.authorize(
            WebSocketUpgradeRequest(
                path = path,
                queryParameters = parseWebSocketQuery(handshake.query()),
                headers = handshake.headers().associate { it.key to it.value }
            )
        )
        if (authorization is WebSocketUpgradeAuthorization.Deny) {
            handshake.reject(authorization.statusCode).coAwait()
            return true
        }

        val socket = handshake.accept().coAwait()
        val session = VertxWebSocketSession(socket, scope, config)
        session.attributes.putAll((authorization as WebSocketUpgradeAuthorization.Allow).attributes)
        session.attributes["_pathParams"] = extractPathParams(route.key, path)
        sessions[session.id] = session

        scope.launch {
            try {
                route.value.onConnect(session)

                session.incoming().collect { message ->
                    when (message) {
                        is WebSocketMessage.Text -> route.value.onText(session, message.content)
                        is WebSocketMessage.Binary -> route.value.onBinary(session, message.data)
                        is WebSocketMessage.Ping -> route.value.onPing(session, message.data)
                        is WebSocketMessage.Pong -> route.value.onPong(session, message.data)
                        is WebSocketMessage.Close -> {
                            route.value.onClose(session, message.code, message.reason)
                        }
                    }
                }
            } catch (e: Exception) {
                route.value.onError(session, e)
            } finally {
                sessions.remove(session.id)
            }
        }

        return true
    }

    private fun findRoute(path: String): Map.Entry<String, WebSocketHandler>? {
        handlers.entries.firstOrNull { it.key == path }?.let { return it }
        return handlers.entries.firstOrNull { matchPath(it.key, path) }
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

    private fun extractPathParams(pattern: String, path: String): Map<String, String> {
        val patternParts = pattern.split("/").filter(String::isNotEmpty)
        val pathParts = path.split("/").filter(String::isNotEmpty)
        return buildMap {
            patternParts.zip(pathParts).forEach { (patternPart, pathPart) ->
                when {
                    patternPart.startsWith(":") -> put(patternPart.drop(1), pathPart)
                    patternPart.startsWith("{") && patternPart.endsWith("}") ->
                        put(patternPart.substring(1, patternPart.lastIndex), pathPart)
                }
            }
        }
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

private fun parseWebSocketQuery(query: String?): Map<String, List<String>> {
    if (query.isNullOrBlank()) return emptyMap()
    return query.split("&")
        .mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) null else part.substring(0, separator) to part.substring(separator + 1)
        }
        .groupBy({ it.first }, { it.second })
}

private fun containsBearerCredential(query: String?): Boolean {
    val normalized = query?.lowercase(Locale.ROOT) ?: return false
    return normalized.split('&').any { parameter ->
        val name = parameter.substringBefore('=')
        val value = parameter.substringAfter('=', "")
        name == "access_token" ||
            name == "authorization" ||
            value.startsWith("bearer%20") ||
            value.startsWith("bearer+")
    }
}
