package codes.yousef.aether.core.jvm

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.LoggerFactory
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.core.pipeline.ApiErrorKind
import codes.yousef.aether.core.pipeline.establishRequestDiagnostics
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Configuration for the Vert.x HTTP server.
 */
data class VertxServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val compressionSupported: Boolean = true,
    val decompressionSupported: Boolean = true,
    val maxHeaderSize: Int = 8192,
    val maxChunkSize: Int = 8192,
    val maxInitialLineLength: Int = 4096,
    /** Hard decoded-byte limit applied before a request body is materialized. */
    val maxRequestBodySize: Int = 16 * 1024 * 1024,
    /** Maximum time between request dispatch and receipt of the complete body. */
    val requestBodyTimeoutMillis: Long = 30_000
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
    }
}

/**
 * Vert.x HTTP server that integrates with Aether's Pipeline and Exchange.
 * Uses Virtual Threads via AetherDispatcher for request handling.
 */
@OptIn(InternalAetherServerApi::class)
class VertxServer(
    private val config: VertxServerConfig = VertxServerConfig(),
    private val pipeline: Pipeline = Pipeline(),
    private val handler: suspend (Exchange) -> Unit
) {
    private val logger = LoggerFactory.getLogger("codes.yousef.aether.core.jvm.VertxServer")
    private val vertx: Vertx = Vertx.vertx()
    private var server: HttpServer? = null
    private val serverJob = SupervisorJob()
    private val scope = CoroutineScope(serverJob + AetherDispatcher.dispatcher)

    /** The bound port after [start], including the OS-selected port when configured with port `0`. */
    val actualPort: Int
        get() = server?.actualPort()?.takeIf { it >= 0 }
            ?: error("Aether server has not been started")

    /**
     * Start the HTTP server.
     * This method blocks until the server is successfully started.
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

        server = vertx.createHttpServer(options)
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
                // Install body callbacks synchronously on the event loop before launching work.
                val bodyDeferred = readBoundedRequestBody(
                    vertx = vertx,
                    request = vertxRequest,
                    maximumBytes = config.maxRequestBodySize,
                    timeoutMillis = config.requestBodyTimeoutMillis
                )

                scope.launch {
                    var requestId: String? = null
                    try {
                        val bodyResult = bodyDeferred.await()
                        if (bodyResult !is BoundedRequestBodyResult.Complete) {
                            rejectRequestBody(vertxRequest, bodyResult)
                            return@launch
                        }
                        val exchange = createVertxExchangeWithBody(vertxRequest, bodyResult.bytes)
                        requestId = establishRequestDiagnostics(exchange)
                        pipeline.execute(exchange, handler)
                        // Ensure response is finalized after all middleware completes
                        // This allows middleware (like SessionMiddleware) to add cookies in finally blocks
                        if (!vertxRequest.response().ended()) {
                            exchange.response.end()
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (e: Exception) {
                        logger.error(
                            "request_id=${requestId ?: "unavailable"} category=${ApiErrorKind.INTERNAL.code} " +
                                "server_request_failure"
                        )
                        try {
                            respondToRawRequestFailure(
                                request = vertxRequest,
                                kind = ApiErrorKind.INTERNAL,
                                establishedRequestId = requestId
                            )
                        } catch (_: Exception) {
                            logger.error("request_id=${requestId ?: "unavailable"} error_response_failed")
                        }
                    }
                }
            }
            .listen()
            .coAwait()

        logger.info("Aether server started on ${config.host}:$actualPort")
    }

    /**
     * Stop the HTTP server.
     * This method blocks until the server is successfully stopped.
     */
    suspend fun stop() {
        server?.close()?.coAwait()
        logger.info("Aether server stopped")
    }

    /**
     * Close the Vert.x instance and clean up resources.
     */
    suspend fun close() {
        serverJob.cancel()
        stop()
        vertx.close().coAwait()
        logger.info("Aether server closed")
    }

    companion object {
        /**
         * Create and start a server with the given configuration.
         * This is a convenience method that uses runBlocking.
         */
        fun create(
            config: VertxServerConfig = VertxServerConfig(),
            pipeline: Pipeline = Pipeline(),
            handler: suspend (Exchange) -> Unit
        ): VertxServer {
            return VertxServer(config, pipeline, handler)
        }

        /**
         * Create and immediately start a server.
         * This method blocks until the server is started.
         */
        fun startBlocking(
            config: VertxServerConfig = VertxServerConfig(),
            pipeline: Pipeline = Pipeline(),
            handler: suspend (Exchange) -> Unit
        ): VertxServer {
            val server = create(config, pipeline, handler)
            runBlocking {
                server.start()
            }
            return server
        }
    }
}



/**
 * Builder for creating a VertxServer with a DSL.
 */
class VertxServerBuilder {
    private var config = VertxServerConfig()
    private var pipeline = Pipeline()
    private var handler: suspend (Exchange) -> Unit = { }

    /**
     * Configure the server.
     */
    fun config(block: VertxServerConfig.() -> VertxServerConfig) {
        config = config.block()
    }

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
     * Configure the pipeline.
     */
    fun pipeline(block: Pipeline.() -> Unit) {
        pipeline.block()
    }

    /**
     * Set the request handler.
     */
    fun handler(block: suspend (Exchange) -> Unit) {
        handler = block
    }

    /**
     * Build the server.
     */
    fun build(): VertxServer {
        return VertxServer(config, pipeline, handler)
    }
}

/**
 * DSL for creating a VertxServer.
 */
fun vertxServer(block: VertxServerBuilder.() -> Unit): VertxServer {
    return VertxServerBuilder().apply(block).build()
}
