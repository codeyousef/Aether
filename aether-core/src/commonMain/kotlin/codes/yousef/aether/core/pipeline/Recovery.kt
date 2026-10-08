package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.Exchange
import kotlinx.coroutines.CancellationException

/**
 * Exception handler that can handle specific exceptions or all exceptions.
 */
typealias ExceptionHandler = suspend (exchange: Exchange, throwable: Throwable) -> Unit

/**
 * Recovery middleware provides global exception handling.
 * It catches exceptions thrown during request processing and handles them gracefully.
 */
class Recovery(
    private val loggerName: String = "codes.yousef.aether.core.pipeline.Recovery",
    private val errorPolicy: ApiErrorPolicy = ApiErrorPolicy()
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
     * Default exception handler. Only typed [ApiException] values affect public status and code;
     * arbitrary exception classes and messages remain an opaque internal failure.
     */
    private suspend fun defaultExceptionHandler(exchange: Exchange, throwable: Throwable) {
        val kind = errorPolicy.classify(throwable)
        val requestId = establishRequestDiagnostics(exchange, errorPolicy.diagnostics)
        logger.error("request_id=$requestId category=${kind.code} unhandled_request_failure")
        respondWithApiError(exchange, kind, errorPolicy.diagnostics)
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
        establishRequestDiagnostics(exchange, errorPolicy.diagnostics)
        try {
            next()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (fatal: Error) {
            throw fatal
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
    errorPolicy: ApiErrorPolicy = ApiErrorPolicy(),
    configure: Recovery.() -> Unit = {}
) {
    val recovery = Recovery(loggerName, errorPolicy).apply(configure)
    use(recovery.middleware())
}

/**
 * Install Recovery middleware with default settings.
 */
fun Pipeline.installRecovery() {
    use(Recovery().middleware())
}
