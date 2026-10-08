package codes.yousef.aether.core.jvm

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.RequestBodyStreamFailure
import codes.yousef.aether.core.pipeline.LoggerFactory
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.core.pipeline.ApiErrorKind
import codes.yousef.aether.core.pipeline.ApiErrorPolicy
import codes.yousef.aether.core.pipeline.establishRequestDiagnostics
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking

import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
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
    /** Hard decoded-byte limit for either buffered or streaming request bodies. */
    val maxRequestBodySize: Int = 16 * 1024 * 1024,
    /** Maximum time allowed for a complete buffered or streaming body. */
    val requestBodyTimeoutMillis: Long = 30_000,
    /** Opt in to demand-driven request bodies. Buffered compatibility remains the default. */
    val streamRequestBodies: Boolean = false,
    /** Maximum decoded chunks retained per streaming request. */
    val streamBufferChunks: Int = 2,
    /** Time allowed for active request children to drain during stop. */
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
    private val stopping = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val stopped = CompletableDeferred<Unit>()

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
                        pipeline.execute(exchange, handler)
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
     * Close the Vert.x instance and clean up resources. Repeated calls are safe.
     */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
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
