package codes.yousef.aether.web

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.pipeline.Pipeline
import kotlinx.coroutines.runBlocking

/**
 * Start an Aether server with a Router.
 *
 * This is the simplest way to start a full-featured Aether server with
 * HTTP routing and WebSocket support.
 *
 * Example:
 * ```kotlin
 * fun main() = aetherStart(port = 8080) {
 *     get("/") { exchange ->
 *         exchange.respond("Hello, Aether!")
 *     }
 *
 *     get("/api/users") { exchange ->
 *         exchange.respondJson(listOf("alice", "bob"))
 *     }
 *
 *     ws("/ws/chat") {
 *         onConnect { session ->
 *             println("Client connected: ${session.id}")
 *         }
 *         onText { session, message ->
 *             session.send("Echo: $message")
 *         }
 *     }
 * }
 * ```
 *
 * @param port The port to listen on (default: 8080)
 * @param host The host/bind address (default: 0.0.0.0)
 * @param showBanner Whether to display the Aether startup banner
 * @param pipeline Optional pre-configured pipeline for middleware
 * @param ssl Optional SSL configuration
 * @param routing DSL block for defining routes
 */
fun aetherStart(
    port: Int = 8080,
    host: String = "0.0.0.0",
    showBanner: Boolean = true,
    pipeline: Pipeline = Pipeline(),
    ssl: SslConfig? = null,
    routing: Router.() -> Unit
) {
    val router = router(routing)
    val config = AetherServerConfig(host = host, port = port, ssl = ssl)

    if (showBanner) {
        printAetherBanner(host, port, router.getWebSocketRoutes().size, ssl?.enabled == true)
    }

    runBlocking(AetherDispatcher.dispatcher) {
        val server = AetherServer.create(config, router, pipeline)

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

        server.start()
        Thread.currentThread().join()
    }
}

/**
 * Start an Aether server with an existing Router instance.
 *
 * Use this overload when you have a pre-configured Router.
 *
 * Example:
 * ```kotlin
 * val apiRouter = router {
 *     get("/users") { ... }
 *     post("/users") { ... }
 * }
 *
 * fun main() = aetherStart(router = apiRouter, port = 8080)
 * ```
 *
 * @param router The pre-configured router
 * @param port The port to listen on (default: 8080)
 * @param host The host/bind address (default: 0.0.0.0)
 * @param showBanner Whether to display the Aether startup banner
 * @param pipeline Optional pre-configured pipeline for middleware
 * @param ssl Optional SSL configuration
 */
fun aetherStart(
    router: Router,
    port: Int = 8080,
    host: String = "0.0.0.0",
    showBanner: Boolean = true,
    pipeline: Pipeline = Pipeline(),
    ssl: SslConfig? = null
) {
    val config = AetherServerConfig(host = host, port = port, ssl = ssl)

    if (showBanner) {
        printAetherBanner(host, port, router.getWebSocketRoutes().size, ssl?.enabled == true)
    }

    runBlocking(AetherDispatcher.dispatcher) {
        val server = AetherServer.create(config, router, pipeline)

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

        server.start()
        Thread.currentThread().join()
    }
}

/**
 * Prints the Aether startup banner to the console.
 */
private fun printAetherBanner(host: String, port: Int, wsRouteCount: Int, isSsl: Boolean = false) {
    val displayHost = if (host == "0.0.0.0") "localhost" else host
    val wsInfo = if (wsRouteCount > 0) " ($wsRouteCount WebSocket routes)" else ""
    val protocol = if (isSsl) "https" else "http"

    val banner = """
    
       ___        __  __             
      / _ | ___  / /_/ /  ___  ____  
     / __ |/ -_)/ __/ _ \/ -_)/ __/  
    /_/ |_|\__/ \__/_//_/\__//_/     
    
    ⚡ Aether Framework
    ▸ Server:    $protocol://$displayHost:$port$wsInfo
    ▸ Runtime:   JVM (Virtual Threads)
    ▸ Docs:      https://aether.codes
    
    """.trimIndent()
    println(banner)
}
