package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.Exchange
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
 * CallLogging records only allowlisted request metadata. Raw paths, queries, headers, cookies and
 * bodies are never formatted.
 */
class CallLogging(
    private val level: LogLevel = LogLevel.INFO,
    private val loggerName: String = "codes.yousef.aether.core.pipeline.CallLogging",
    private val diagnostics: RequestDiagnosticsPolicy = RequestDiagnosticsPolicy(),
    private val messageSink: ((String) -> Unit)? = null
) {
    private val logger = LoggerFactory.getLogger(loggerName)

    /**
     * Format the log message.
     */
    private fun formatMessage(
        requestId: String,
        method: String,
        routeTemplate: String,
        statusCode: Int,
        duration: Duration,
        errorCategory: String?
    ): String = buildString {
        append("request_id=")
        append(requestId)
        append(" method=")
        append(method)
        append(" route=")
        append(routeTemplate)
        append(" status=")
        append(statusCode)
        append(" duration_ms=")
        append(duration.inWholeMilliseconds)
        if (errorCategory != null) {
            append(" category=")
            append(errorCategory)
        }
    }

    /**
     * Log the message at the configured level.
     */
    private fun log(message: String) {
        messageSink?.invoke(message)
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
        val requestId = establishRequestDiagnostics(exchange, diagnostics)
        val startTime = TimeSource.Monotonic.markNow()
        val method = exchange.request.method.name
        var failureKind: ApiErrorKind? = null

        try {
            next()
        } catch (throwable: Throwable) {
            failureKind = ApiErrorPolicy().classify(throwable)
            exchange.attributes.put(RequestDiagnostics.ErrorCategoryKey, failureKind.code)
            throw throwable
        } finally {
            val route = sanitizeLogToken(
                value = if (diagnostics.includeRouteTemplate) {
                    exchange.attributes.get(RequestDiagnostics.RouteTemplateKey)
                        ?: diagnostics.unmatchedRouteLabel
                } else {
                    "<redacted>"
                },
                maximumLength = diagnostics.maximumRouteTemplateLength
            )
            log(
                formatMessage(
                    requestId = requestId,
                    method = method,
                    routeTemplate = route,
                    statusCode = failureKind?.statusCode ?: exchange.response.statusCode,
                    duration = startTime.elapsedNow(),
                    errorCategory = failureKind?.code
                        ?: exchange.attributes.get(RequestDiagnostics.ErrorCategoryKey)
                )
            )
        }
    }
}

/**
 * Install CallLogging middleware with default settings.
 */
fun Pipeline.installCallLogging(
    level: LogLevel = LogLevel.INFO,
    loggerName: String = "codes.yousef.aether.core.pipeline.CallLogging",
    diagnostics: RequestDiagnosticsPolicy = RequestDiagnosticsPolicy(),
    messageSink: ((String) -> Unit)? = null
) {
    use(CallLogging(level, loggerName, diagnostics, messageSink).middleware())
}
