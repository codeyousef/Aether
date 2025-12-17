package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.Exchange
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Log levels for the CallLogging middleware.
 */
enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/**
 * Platform-specific logger interface.
 */
expect interface Logger {
    fun trace(message: String)
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

/**
 * Factory for creating platform-specific loggers.
 */
expect object LoggerFactory {
    fun getLogger(name: String): Logger
}

/**
 * CallLogging middleware provides universal request/response logging.
 * Logs request method, path, status code, and response time.
 */
class CallLogging(
    private val level: LogLevel = LogLevel.INFO,
    private val loggerName: String = "codes.yousef.aether.core.pipeline.CallLogging"
) {
    private val logger = LoggerFactory.getLogger(loggerName)

    /**
     * Format the log message.
     */
    private fun formatMessage(
        method: String,
        path: String,
        statusCode: Int,
        duration: Duration
    ): String {
        return "$method $path - $statusCode (${duration.inWholeMilliseconds}ms)"
    }

    /**
     * Log the message at the configured level.
     */
    private fun log(message: String) {
        when (level) {
            LogLevel.TRACE -> logger.trace(message)
            LogLevel.DEBUG -> logger.debug(message)
            LogLevel.INFO -> logger.info(message)
            LogLevel.WARN -> logger.warn(message)
            LogLevel.ERROR -> logger.error(message)
        }
    }

    /**
     * Create the middleware function.
     */
    fun middleware(): Middleware = { exchange, next ->
        val startTime = TimeSource.Monotonic.markNow()
        val method = exchange.request.method.name
        val path = exchange.request.path

        try {
            next()
        } finally {
            val duration = startTime.elapsedNow()
            val statusCode = exchange.response.statusCode
            val message = formatMessage(method, path, statusCode, duration)
            log(message)
        }
    }
}

/**
 * Install CallLogging middleware with default settings.
 */
fun Pipeline.installCallLogging(
    level: LogLevel = LogLevel.INFO,
    loggerName: String = "codes.yousef.aether.core.pipeline.CallLogging"
) {
    use(CallLogging(level, loggerName).middleware())
}
