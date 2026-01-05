package codes.yousef.aether.core

import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.core.pipeline.Pipeline
import kotlinx.coroutines.runBlocking

/**
 * Configuration for starting an Aether server.
 */
data class AetherConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val showBanner: Boolean = true,
    val compressionSupported: Boolean = true,
    val decompressionSupported: Boolean = true,
    val maxHeaderSize: Int = 8192,
    val maxChunkSize: Int = 8192,
    val maxInitialLineLength: Int = 4096
)

/**
 * Main entry point for starting an Aether application.
 *
 * This is the recommended way to start an Aether server. It provides:
 * - Automatic banner display
 * - Graceful shutdown handling
 * - Virtual Threads dispatcher
 *
 * Example:
 * ```kotlin
 * fun main() = aetherStart {
 *     port(8080)
 *     pipeline {
 *         installRecovery()
 *         use(router.asMiddleware())
 *     }
 *     handler { exchange ->
 *         exchange.respond("Hello, Aether!")
 *     }
 * }
 * ```
 */
fun aetherStart(block: AetherStartBuilder.() -> Unit) {
    val builder = AetherStartBuilder().apply(block)
    val server = builder.build()

    if (builder.showBanner) {
        printAetherBanner(builder.config)
    }

    runBlocking(AetherDispatcher.dispatcher) {
        server.start()

        // Register shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(Thread {
            println("\n⚡ Shutting down Aether...")
            runBlocking {
                try {
                    server.close()
                    println("⚡ Aether stopped gracefully")
                } catch (e: Exception) {
                    println("⚡ Error during shutdown: ${e.message}")
                }
            }
        })

        // Block forever - server runs until interrupted
        Thread.currentThread().join()
    }
}

/**
 * Prints the Aether startup banner to the console.
 */
private fun printAetherBanner(config: AetherConfig) {
    val banner = """
    
       ___        __  __             
      / _ | ___  / /_/ /  ___  ____  
     / __ |/ -_)/ __/ _ \/ -_)/ __/  
    /_/ |_|\__/ \__/_//_/\__//_/     
    
    ⚡ Aether Framework
    ▸ Server:  http://${if (config.host == "0.0.0.0") "localhost" else config.host}:${config.port}
    ▸ Runtime: JVM (Virtual Threads)
    ▸ Docs:    https://aether.codes
    
    """.trimIndent()
    println(banner)
}

/**
 * Builder for configuring an Aether server startup.
 */
class AetherStartBuilder {
    internal var config = AetherConfig()
    internal var pipeline = Pipeline()
    internal var handler: suspend (Exchange) -> Unit = { }
    internal val showBanner: Boolean get() = config.showBanner

    /**
     * Set the server port.
     */
    fun port(port: Int) {
        config = config.copy(port = port)
    }

    /**
     * Set the server host/bind address.
     */
    fun host(host: String) {
        config = config.copy(host = host)
    }

    /**
     * Enable or disable the startup banner.
     */
    fun banner(show: Boolean) {
        config = config.copy(showBanner = show)
    }

    /**
     * Enable or disable compression support.
     */
    fun compression(enabled: Boolean) {
        config = config.copy(compressionSupported = enabled)
    }

    /**
     * Configure the pipeline with middleware.
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
     * Build the server instance.
     */
    internal fun build(): VertxServer {
        return VertxServer(
            config = VertxServerConfig(
                host = config.host,
                port = config.port,
                compressionSupported = config.compressionSupported,
                decompressionSupported = config.decompressionSupported,
                maxHeaderSize = config.maxHeaderSize,
                maxChunkSize = config.maxChunkSize,
                maxInitialLineLength = config.maxInitialLineLength
            ),
            pipeline = pipeline,
            handler = handler
        )
    }
}

