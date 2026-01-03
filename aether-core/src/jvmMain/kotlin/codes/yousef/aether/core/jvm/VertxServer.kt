package codes.yousef.aether.core.jvm

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.LoggerFactory
import codes.yousef.aether.core.pipeline.Pipeline
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CoroutineScope
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
    val maxInitialLineLength: Int = 4096
)

/**
 * Vert.x HTTP server that integrates with Aether's Pipeline and Exchange.
 * Uses Virtual Threads via AetherDispatcher for request handling.
 */
class VertxServer(
    private val config: VertxServerConfig = VertxServerConfig(),
    private val pipeline: Pipeline = Pipeline(),
    private val handler: suspend (Exchange) -> Unit
) {
    private val logger = LoggerFactory.getLogger("codes.yousef.aether.core.jvm.VertxServer")
    private val vertx: Vertx = Vertx.vertx()
    private var server: HttpServer? = null
    private val scope = CoroutineScope(AetherDispatcher.dispatcher)

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
            .requestHandler { vertxRequest ->
                // CRITICAL: Set up body handler SYNCHRONOUSLY before any async work
                // This must happen on the Vert.x event loop thread, before launching coroutine
                val bodyDeferred = kotlinx.coroutines.CompletableDeferred<ByteArray>()
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
                
                // Now launch coroutine to process the request
                scope.launch {
                    try {
                        // Wait for body to be fully read
                        val bodyBytes = bodyDeferred.await()
                        val exchange = createVertxExchangeWithBody(vertxRequest, bodyBytes)
                        pipeline.execute(exchange, handler)
                        // Ensure response is finalized after all middleware completes
                        // This allows middleware (like SessionMiddleware) to add cookies in finally blocks
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

        logger.info("Vert.x server started on ${config.host}:${config.port}")
    }

    /**
     * Stop the HTTP server.
     * This method blocks until the server is successfully stopped.
     */
    suspend fun stop() {
        server?.close()?.coAwait()
        logger.info("Vert.x server stopped")
    }

    /**
     * Close the Vert.x instance and clean up resources.
     */
    suspend fun close() {
        stop()
        vertx.close().coAwait()
        logger.info("Vert.x instance closed")
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
