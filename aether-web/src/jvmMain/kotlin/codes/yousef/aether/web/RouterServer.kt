package codes.yousef.aether.web

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.RequestBodyStreamFailure
import codes.yousef.aether.core.pipeline.LoggerFactory
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.core.pipeline.ApiErrorKind
import codes.yousef.aether.core.pipeline.ApiErrorPolicy
import codes.yousef.aether.core.pipeline.encodeApiError
import codes.yousef.aether.core.pipeline.establishRequestDiagnostics
import codes.yousef.aether.core.pipeline.respondWithApiError
import codes.yousef.aether.core.pipeline.selectRequestId
import codes.yousef.aether.core.websocket.VertxWebSocketServer
import codes.yousef.aether.core.websocket.WebSocketConfig
import codes.yousef.aether.core.jvm.createVertxExchangeWithBody
import codes.yousef.aether.core.jvm.VertxRequestBodySource
import codes.yousef.aether.core.jvm.createVertxExchangeWithStream
import codes.yousef.aether.core.jvm.BoundedRequestBodyResult
import codes.yousef.aether.core.jvm.InternalAetherServerApi
import codes.yousef.aether.core.jvm.readBoundedRequestBody
import codes.yousef.aether.core.jvm.RequestBodyReadFailure
import codes.yousef.aether.core.jvm.rejectRequestBody
import codes.yousef.aether.core.jvm.respondToRawRequestFailure
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.SelfSignedCertificate
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking

import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
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
    val ssl: SslConfig? = null,
    /** Hard decoded-byte limit for either buffered or streaming request bodies. */
    val maxRequestBodySize: Int = 16 * 1024 * 1024,
    /** Maximum time allowed for a complete buffered or streaming body. */
    val requestBodyTimeoutMillis: Long = 30_000,
    /** Opt in to demand-driven request bodies for dedicated streaming endpoints. */
    val streamRequestBodies: Boolean = false,
    /** Maximum decoded chunks retained per streaming request. */
    val streamBufferChunks: Int = 2,
    /** Time allowed for active HTTP and WebSocket children to drain during stop. */
    val shutdownGraceMillis: Long = 5_000,
    /** Vert.x response queue bound before application writes suspend. */
    val responseWriteQueueBytes: Int = 64 * 1024
) {
    init {
        require(port in 0..65_535) { "Port must be between 0 and 65535" }
        require(maxHeaderSize > 0 && maxChunkSize > 0 && maxInitialLineLength > 0) {
            "HTTP parser limits must be positive"
        }
        require(maxRequestBodySize in 1..1_073_741_824) {
            "Maximum request body size must be between 1 byte and 1 GiB"
        }
        require(requestBodyTimeoutMillis > 0) { "Request body timeout must be positive" }
        require(streamBufferChunks in 1..64) { "Stream buffer chunks must be between 1 and 64" }
        require(shutdownGraceMillis >= 0) { "Shutdown grace period must not be negative" }
        require(responseWriteQueueBytes > 0) { "Response write queue size must be positive" }
    }
}

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
@OptIn(InternalAetherServerApi::class)
class AetherServer(
    private val config: AetherServerConfig,
    private val router: Router,
    private val pipeline: Pipeline = Pipeline()
) {
    private val logger = LoggerFactory.getLogger("codes.yousef.aether.web.AetherServer")
    private val vertx: Vertx = Vertx.vertx()
    private var server: HttpServer? = null
    // HTTP requests and WebSocket sessions are independent children; one transport failure cannot
    // cancel siblings, while close() still cancels every owned task through this retained job.
    private val serverJob = SupervisorJob()
    private val scope = CoroutineScope(
        serverJob +
            AetherDispatcher.dispatcher +
            CoroutineExceptionHandler { _, error ->
                logger.error("Uncaught Aether server task failure", error)
            }
    )
    private val stopping = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val stopped = CompletableDeferred<Unit>()
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
            .setMaxWebSocketFrameSize(config.webSocket.maxFrameSize)
            .setMaxWebSocketMessageSize(config.webSocket.maxMessageSize)

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
            .webSocketHandshakeHandler { handshake ->
                scope.launch {
                    try {
                        when {
                            stopping.get() -> handshake.reject(503).coAwait()
                            !webSocketServer.handleHandshake(handshake, scope) ->
                                handshake.reject(404).coAwait()
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        logger.error("category=${ApiErrorKind.INTERNAL.code} websocket_upgrade_failure")
                        runCatching { handshake.reject(500).coAwait() }
                    }
                }
            }
            .webSocketHandler {
                // Accepted sockets are initialized from ServerWebSocketHandshake.accept().
            }
            .invalidRequestHandler { invalidRequest ->
                scope.launch {
                    respondToRawRequestFailure(
                        request = invalidRequest,
                        kind = ApiErrorKind.BAD_REQUEST,
                        closeConnection = true
                    )
                }
            }
            .requestHandler { vertxRequest ->
                if (stopping.get()) {
                    vertxRequest.connection().close()
                    return@requestHandler
                }
                vertxRequest.response().setWriteQueueMaxSize(config.responseWriteQueueBytes)

                // Install every transport callback synchronously before dispatching the child.
                val bodySource = if (config.streamRequestBodies) {
                    VertxRequestBodySource(
                        vertx = vertx,
                        request = vertxRequest,
                        maximumBytes = config.maxRequestBodySize.toLong(),
                        maximumChunkBytes = config.maxChunkSize,
                        timeoutMillis = config.requestBodyTimeoutMillis,
                        bufferedChunks = config.streamBufferChunks
                    )
                } else {
                    null
                }
                val bodyDeferred = if (bodySource == null) {
                    readBoundedRequestBody(
                        vertx = vertx,
                        request = vertxRequest,
                        maximumBytes = config.maxRequestBodySize,
                        timeoutMillis = config.requestBodyTimeoutMillis
                    )
                } else {
                    null
                }

                try {
                    scope.launch {
                        var requestId: String? = null
                        bodySource?.onDisconnect {
                            coroutineContext.job.cancel(CancellationException("Request connection closed"))
                        }
                        try {
                            val exchange = if (bodySource != null) {
                                bodySource.preflightFailure?.let { failure ->
                                    rejectRequestBody(vertxRequest, failure.toBoundedResult())
                                    return@launch
                                }
                                createVertxExchangeWithStream(vertxRequest, bodySource)
                            } else {
                                val bodyResult = bodyDeferred!!.await()
                                if (bodyResult !is BoundedRequestBodyResult.Complete) {
                                    rejectRequestBody(vertxRequest, bodyResult)
                                    return@launch
                                }
                                createVertxExchangeWithBody(vertxRequest, bodyResult.bytes)
                            }
                            requestId = establishRequestDiagnostics(exchange)
                            pipeline.execute(exchange) {
                                val handled = router.handle(exchange)
                                if (!handled) respondWithApiError(exchange, ApiErrorKind.NOT_FOUND)
                            }
                            if (!vertxRequest.response().ended()) exchange.response.end()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            val kind = ApiErrorPolicy().classify(error)
                            logger.error(
                                "request_id=${requestId ?: "unavailable"} category=${kind.code} " +
                                    "server_request_failure"
                            )
                            try {
                                respondToRawRequestFailure(
                                    request = vertxRequest,
                                    kind = kind,
                                    establishedRequestId = requestId
                                )
                            } catch (_: Exception) {
                                logger.error("request_id=${requestId ?: "unavailable"} error_response_failed")
                            }
                        } finally {
                            bodySource?.cancel()
                        }
                    }
                } catch (fatal: Throwable) {
                    if (fatal is Error) throw fatal
                    val requestId = selectRequestId(vertxRequest.getHeader("X-Request-Id"))
                    logger.error(
                        "request_id=$requestId category=${ApiErrorKind.DEPENDENCY_UNAVAILABLE.code} " +
                            "request_launch_failed"
                    )
                    runCatching {
                        vertxRequest.response()
                            .setStatusCode(ApiErrorKind.DEPENDENCY_UNAVAILABLE.statusCode)
                            .putHeader("Content-Type", "application/json; charset=utf-8")
                            .putHeader("Cache-Control", "no-store")
                            .putHeader("X-Request-Id", requestId)
                            .end(encodeApiError(ApiErrorKind.DEPENDENCY_UNAVAILABLE, requestId))
                    }
                }
            }
            .listen()
            .coAwait()

        val wsRouteCount = router.getWebSocketRoutes().size
        val wsInfo = if (wsRouteCount > 0) " (WebSocket: $wsRouteCount routes)" else ""
        logger.info("Aether server started on ${config.host}:$actualPort$wsInfo")
    }

    /** The bound port after [start], including the OS-selected port when configured with port `0`. */
    val actualPort: Int
        get() = server?.actualPort()?.takeIf { it >= 0 }
            ?: error("Aether server has not been started")


    /**
     * Stop the server.
     */
    suspend fun stop() {
        if (!stopping.compareAndSet(false, true)) {
            stopped.await()
            return
        }
        try {
            val activeChildren = serverJob.children.toList()
            val drained = if (config.shutdownGraceMillis == 0L) {
                activeChildren.isEmpty()
            } else {
                withTimeoutOrNull(config.shutdownGraceMillis) {
                    activeChildren.joinAll()
                    true
                } ?: false
            }
            if (!drained) {
                activeChildren.forEach { it.cancel() }
                activeChildren.joinAll()
            }
            serverJob.cancel()
            server?.close()?.coAwait()
            logger.info("Aether server stopped")
        } finally {
            stopped.complete(Unit)
        }
    }

    /**
     * Close the server and clean up resources. Repeated calls are safe.
     */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
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

@OptIn(InternalAetherServerApi::class)
private fun RequestBodyStreamFailure.toBoundedResult(): BoundedRequestBodyResult = when (this) {
    RequestBodyStreamFailure.TOTAL_LIMIT_EXCEEDED,
    RequestBodyStreamFailure.CHUNK_LIMIT_EXCEEDED -> BoundedRequestBodyResult.TooLarge
    RequestBodyStreamFailure.DEADLINE_EXCEEDED ->
        BoundedRequestBodyResult.Incomplete(RequestBodyReadFailure.TIMEOUT)
    RequestBodyStreamFailure.CONNECTION_CLOSED,
    RequestBodyStreamFailure.TRANSPORT_FAILURE,
    RequestBodyStreamFailure.ALREADY_CONSUMED ->
        BoundedRequestBodyResult.Incomplete(RequestBodyReadFailure.TRANSPORT)
}
