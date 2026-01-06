package codes.yousef.aether.web

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.pipeline.LoggerFactory
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.core.websocket.VertxWebSocketServer
import codes.yousef.aether.core.websocket.WebSocketConfig
import codes.yousef.aether.core.jvm.createVertxExchangeWithBody
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.SelfSignedCertificate
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Configuration for an Aether server with Router support.
 */
data class AetherServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val compressionSupported: Boolean = true,
    val decompressionSupported: Boolean = true,
    val maxHeaderSize: Int = 8192,
    val maxChunkSize: Int = 8192,
    val maxInitialLineLength: Int = 4096,
    val webSocket: WebSocketConfig = WebSocketConfig(),
    val ssl: SslConfig? = null
)

/**
 * Configuration for SSL/TLS.
 */
data class SslConfig(
    val enabled: Boolean = true,
    val keyPath: String? = null,
    val certPath: String? = null,
    val selfSigned: Boolean = false
)

/**
 * Aether HTTP + WebSocket server that integrates with the Router.
 *
 * This server handles both HTTP requests via the Router's HTTP routes and
 * WebSocket upgrades via the Router's WebSocket routes.
 *
 * Example:
 * ```kotlin
 * val router = router {
 *     get("/api/health") { exchange ->
 *         exchange.respond(200, """{"status": "healthy"}""")
 *     }
 *
 *     ws("/ws/agent/{sessionId}") {
 *         onConnect { session ->
 *             val sessionId = session.pathParams["sessionId"]
 *             println("Agent connected: $sessionId")
 *         }
 *         onText { session, message ->
 *             // Handle messages
 *         }
 *     }
 * }
 *
 * val server = AetherServer.startBlocking(
 *     config = AetherServerConfig(port = 8080),
 *     router = router
 * )
 * ```
 */
class AetherServer(
    private val config: AetherServerConfig,
    private val router: Router,
    private val pipeline: Pipeline = Pipeline()
) {
    private val logger = LoggerFactory.getLogger("codes.yousef.aether.web.AetherServer")
    private val vertx: Vertx = Vertx.vertx()
    private var server: HttpServer? = null
    private val scope = CoroutineScope(AetherDispatcher.dispatcher)
    private val webSocketServer = VertxWebSocketServer(vertx, config.webSocket)

    init {
        // Register all WebSocket routes from the router
        for (route in router.getWebSocketRoutes()) {
            webSocketServer.registerHandler(route.path, route.handler)
        }
    }

    /**
     * Start the HTTP + WebSocket server.
     */
    suspend fun start() {
        val options = HttpServerOptions()
            .setHost(config.host)
            .setPort(config.port)
            .setCompressionSupported(config.compressionSupported)
            .setDecompressionSupported(config.decompressionSupported)
            .setMaxHeaderSize(config.maxHeaderSize)
            .setMaxChunkSize(config.maxChunkSize)
            .setMaxInitialLineLength(config.maxInitialLineLength)

        if (config.ssl?.enabled == true) {
            options.isSsl = true
            if (config.ssl.selfSigned) {
                val certificate = SelfSignedCertificate.create()
                options.keyCertOptions = certificate.keyCertOptions()
                options.trustOptions = certificate.trustOptions()
            } else if (config.ssl.keyPath != null && config.ssl.certPath != null) {
                options.setKeyCertOptions(
                    PemKeyCertOptions()
                        .setKeyPath(config.ssl.keyPath)
                        .setCertPath(config.ssl.certPath)
                )
            }
        }

        server = vertx.createHttpServer(options)
            .webSocketHandler { ws ->
                // Handle WebSocket upgrade requests
                scope.launch {
                    try {
                        val path = ws.path()
                        val handler = router.findWebSocketHandler(path)

                        if (handler != null) {
                            // Extract path parameters and set them in the session
                            val wsRoutes = router.getWebSocketRoutes()
                            val matchingRoute = wsRoutes.find { route ->
                                matchWebSocketPathInternal(route.path, path)
                            }
                            val pathParams = matchingRoute?.let {
                                extractWebSocketPathParams(it.path, path)
                            } ?: emptyMap()

                            // Accept the connection and handle it
                            @Suppress("DEPRECATION")
                            ws.accept()

                            val session = codes.yousef.aether.core.websocket.VertxWebSocketSession(ws, scope)
                            // Store path params in session attributes
                            session.attributes["_pathParams"] = pathParams

                            handleWebSocketSession(session, handler)
                        } else {
                            // No handler found, reject the connection
                            @Suppress("DEPRECATION")
                            ws.reject(404)
                        }
                    } catch (e: Exception) {
                        logger.error("Error handling WebSocket upgrade", e)
                        try {
                            @Suppress("DEPRECATION")
                            ws.reject(500)
                        } catch (_: Exception) {
                            // Ignore rejection errors
                        }
                    }
                }
            }
            .requestHandler { vertxRequest ->
                // Handle HTTP requests (same as VertxServer)
                val bodyDeferred = CompletableDeferred<ByteArray>()
                val buffer = io.vertx.core.buffer.Buffer.buffer()

                vertxRequest.handler { chunk ->
                    buffer.appendBuffer(chunk)
                }
                vertxRequest.endHandler {
                    bodyDeferred.complete(buffer.bytes)
                }
                vertxRequest.exceptionHandler { e ->
                    if (!bodyDeferred.isCompleted) {
                        bodyDeferred.complete(ByteArray(0))
                    }
                }

                scope.launch {
                    try {
                        val bodyBytes = bodyDeferred.await()
                        val exchange = createVertxExchangeWithBody(vertxRequest, bodyBytes)

                        // Execute through pipeline with router as the final handler
                        pipeline.execute(exchange) {
                            val handled = router.handle(exchange)
                            if (!handled) {
                                exchange.notFound("Route not found: ${exchange.request.path}")
                            }
                        }

                        if (!vertxRequest.response().ended()) {
                            exchange.response.end()
                        }
                    } catch (e: Exception) {
                        logger.error("Error processing request", e)
                        try {
                            vertxRequest.response()
                                .setStatusCode(500)
                                .end("Internal Server Error")
                                .coAwait()
                        } catch (responseError: Exception) {
                            logger.error("Failed to send error response", responseError)
                        }
                    }
                }
            }
            .listen()
            .coAwait()

        val wsRouteCount = router.getWebSocketRoutes().size
        val wsInfo = if (wsRouteCount > 0) " (WebSocket: $wsRouteCount routes)" else ""
        logger.info("Aether server started on ${config.host}:${config.port}$wsInfo")
    }

    private suspend fun handleWebSocketSession(
        session: codes.yousef.aether.core.websocket.VertxWebSocketSession,
        handler: codes.yousef.aether.core.websocket.WebSocketHandler
    ) {
        try {
            handler.onConnect(session)

            session.incoming().collect { message ->
                when (message) {
                    is codes.yousef.aether.core.websocket.WebSocketMessage.Text ->
                        handler.onText(session, message.content)
                    is codes.yousef.aether.core.websocket.WebSocketMessage.Binary ->
                        handler.onBinary(session, message.data)
                    is codes.yousef.aether.core.websocket.WebSocketMessage.Ping ->
                        handler.onPing(session, message.data)
                    is codes.yousef.aether.core.websocket.WebSocketMessage.Pong ->
                        handler.onPong(session, message.data)
                    is codes.yousef.aether.core.websocket.WebSocketMessage.Close ->
                        handler.onClose(session, message.code, message.reason)
                }
            }
        } catch (e: Exception) {
            handler.onError(session, e)
        }
    }

    /**
     * Stop the server.
     */
    suspend fun stop() {
        server?.close()?.coAwait()
        logger.info("Aether server stopped")
    }

    /**
     * Close the server and clean up resources.
     */
    suspend fun close() {
        stop()
        vertx.close().coAwait()
        logger.info("Aether server closed")
    }

    /**
     * Get the WebSocket server for broadcasting and session management.
     */
    fun webSocket(): VertxWebSocketServer = webSocketServer

    companion object {
        /**
         * Create an AetherServer with the given configuration and router.
         */
        fun create(
            config: AetherServerConfig = AetherServerConfig(),
            router: Router,
            pipeline: Pipeline = Pipeline()
        ): AetherServer {
            return AetherServer(config, router, pipeline)
        }

        /**
         * Create and immediately start a server.
         * This method blocks until the server is started.
         */
        fun startBlocking(
            config: AetherServerConfig = AetherServerConfig(),
            router: Router,
            pipeline: Pipeline = Pipeline()
        ): AetherServer {
            val server = create(config, router, pipeline)
            runBlocking {
                server.start()
            }
            return server
        }
    }
}

/**
 * Internal path matching function for WebSocket routes.
 */
private fun matchWebSocketPathInternal(pattern: String, path: String): Boolean {
    val patternParts = pattern.split("/").filter { it.isNotEmpty() }
    val pathParts = path.split("/").filter { it.isNotEmpty() }

    if (patternParts.size != pathParts.size) {
        return false
    }

    for (i in patternParts.indices) {
        val patternPart = patternParts[i]
        val pathPart = pathParts[i]

        // Path parameter - matches anything
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
 * DSL function for creating an AetherServer with a Router.
 */
fun aetherServer(block: AetherServerBuilder.() -> Unit): AetherServer {
    return AetherServerBuilder().apply(block).build()
}

/**
 * Builder for creating an AetherServer with a DSL.
 */
class AetherServerBuilder {
    private var config = AetherServerConfig()
    private var router: Router? = null
    private var pipeline = Pipeline()

    /**
     * Set the host.
     */
    fun host(host: String) {
        config = config.copy(host = host)
    }

    /**
     * Set the port.
     */
    fun port(port: Int) {
        config = config.copy(port = port)
    }

    /**
     * Configure the server.
     */
    fun config(block: AetherServerConfig.() -> AetherServerConfig) {
        config = config.block()
    }

    /**
     * Set the router.
     */
    fun router(router: Router) {
        this.router = router
    }

    /**
     * Configure the router inline.
     */
    fun routing(block: Router.() -> Unit) {
        this.router = codes.yousef.aether.web.router(block)
    }

    /**
     * Configure the pipeline.
     */
    fun pipeline(block: Pipeline.() -> Unit) {
        pipeline.block()
    }

    /**
     * Build the server.
     */
    fun build(): AetherServer {
        val r = router ?: throw IllegalStateException("Router must be configured")
        return AetherServer(config, r, pipeline)
    }
}
