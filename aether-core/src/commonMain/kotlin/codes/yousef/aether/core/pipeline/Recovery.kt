package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.Exchange

/**
 * Exception handler that can handle specific exceptions or all exceptions.
 */
typealias ExceptionHandler = suspend (exchange: Exchange, throwable: Throwable) -> Unit

/**
 * Recovery middleware provides global exception handling.
 * It catches exceptions thrown during request processing and handles them gracefully.
 */
class Recovery(
    private val loggerName: String = "codes.yousef.aether.core.pipeline.Recovery"
) {
    private val logger = LoggerFactory.getLogger(loggerName)
    private val specificHandlers = mutableMapOf<String, ExceptionHandler>()
    private var defaultHandler: ExceptionHandler = { exchange, throwable ->
        defaultExceptionHandler(exchange, throwable)
    }

    /**
     * Register a handler for a specific exception type by name.
     */
    fun handleByName(exceptionClassName: String, handler: ExceptionHandler) {
        specificHandlers[exceptionClassName] = handler
    }

    /**
     * Set the default exception handler for unhandled exceptions.
     */
    fun handleAll(handler: ExceptionHandler) {
        defaultHandler = handler
    }

    /**
     * Default exception handler that sends a 500 response.
     */
    private suspend fun defaultExceptionHandler(exchange: Exchange, throwable: Throwable) {
        logger.error("Unhandled exception during request processing", throwable)

        try {
            val statusCode = when (throwable) {
                is IllegalArgumentException -> 400
                is IllegalStateException -> 409
                is NoSuchElementException -> 404
                is UnsupportedOperationException -> 501
                else -> 500
            }

            val message = when (statusCode) {
                400 -> "Bad Request"
                404 -> "Not Found"
                409 -> "Conflict"
                501 -> "Not Implemented"
                else -> "Internal Server Error"
            }

            exchange.response.statusCode = statusCode
            exchange.response.setHeader("Content-Type", "text/plain; charset=utf-8")
            exchange.response.write(message)
            exchange.response.end()
        } catch (e: Exception) {
            logger.error("Failed to send error response", e)
        }
    }

    /**
     * Find the appropriate handler for the given exception.
     */
    private fun findHandler(throwable: Throwable): ExceptionHandler {
        val className = throwable::class.simpleName ?: "Unknown"
        return specificHandlers[className] ?: defaultHandler
    }

    /**
     * Create the middleware function.
     */
    fun middleware(): Middleware = { exchange, next ->
        try {
            next()
        } catch (throwable: Throwable) {
            val handler = findHandler(throwable)
            handler(exchange, throwable)
        }
    }
}

/**
 * Install Recovery middleware with configuration.
 */
fun Pipeline.installRecovery(
    loggerName: String = "codes.yousef.aether.core.pipeline.Recovery",
    configure: Recovery.() -> Unit = {}
) {
    val recovery = Recovery(loggerName).apply(configure)
    use(recovery.middleware())
}

/**
 * Install Recovery middleware with default settings.
 */
fun Pipeline.installRecovery() {
    use(Recovery().middleware())
}
