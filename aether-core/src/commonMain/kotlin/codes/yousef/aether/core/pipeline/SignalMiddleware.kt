package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.Exchange
import kotlinx.datetime.Clock

/**
 * Configuration for signal middleware.
 */
data class SignalMiddlewareConfig(
    /**
     * Whether to include request/response details in signals.
     */
    val includeDetails: Boolean = true,

    /**
     * Whether to measure request duration.
     */
    val measureDuration: Boolean = true
)

/**
 * Data class for request signal payloads.
 * This is defined in aether-core to avoid circular dependencies with aether-signals.
 */
data class RequestLifecycleData(
    val exchange: Exchange,
    val path: String,
    val method: String,
    val startTime: Long,
    val duration: Long? = null,
    val statusCode: Int? = null,
    val error: Throwable? = null
)

/**
 * Type alias for signal handlers.
 */
typealias RequestSignalHandler = suspend (RequestLifecycleData) -> Unit

/**
 * Registry for request lifecycle signal handlers.
 * This allows components to subscribe to request lifecycle events
 * without creating a circular dependency between aether-core and aether-signals.
 */
object RequestSignals {
    private val requestStartedHandlers = mutableListOf<RequestSignalHandler>()
    private val requestFinishedHandlers = mutableListOf<RequestSignalHandler>()
    private val requestErrorHandlers = mutableListOf<RequestSignalHandler>()

    /**
     * Register a handler for request started events.
     */
    fun onRequestStarted(handler: RequestSignalHandler): () -> Unit {
        requestStartedHandlers.add(handler)
        return { requestStartedHandlers.remove(handler) }
    }

    /**
     * Register a handler for request finished events.
     */
    fun onRequestFinished(handler: RequestSignalHandler): () -> Unit {
        requestFinishedHandlers.add(handler)
        return { requestFinishedHandlers.remove(handler) }
    }

    /**
     * Register a handler for request error events.
     */
    fun onRequestError(handler: RequestSignalHandler): () -> Unit {
        requestErrorHandlers.add(handler)
        return { requestErrorHandlers.remove(handler) }
    }

    internal suspend fun fireRequestStarted(data: RequestLifecycleData) {
        requestStartedHandlers.forEach { it(data) }
    }

    internal suspend fun fireRequestFinished(data: RequestLifecycleData) {
        requestFinishedHandlers.forEach { it(data) }
    }

    internal suspend fun fireRequestError(data: RequestLifecycleData) {
        requestErrorHandlers.forEach { it(data) }
    }

    /**
     * Clear all handlers. Useful for testing.
     */
    fun clearAll() {
        requestStartedHandlers.clear()
        requestFinishedHandlers.clear()
        requestErrorHandlers.clear()
    }
}

/**
 * Middleware that fires request lifecycle signals.
 * Install this middleware early in the pipeline to capture the full request lifecycle.
 *
 * Example:
 * ```kotlin
 * val pipeline = Pipeline().apply {
 *     installRequestSignals()
 *     // ... other middleware
 * }
 *
 * // Subscribe to signals
 * RequestSignals.onRequestFinished { data ->
 *     println("Request to ${data.path} took ${data.duration}ms")
 * }
 * ```
 */
fun Pipeline.installRequestSignals(config: SignalMiddlewareConfig = SignalMiddlewareConfig()): Pipeline {
    use { exchange, next ->
        val startTime = if (config.measureDuration) Clock.System.now().toEpochMilliseconds() else 0L
        val path = exchange.request.path
        val method = exchange.request.method.name

        val startData = RequestLifecycleData(
            exchange = exchange,
            path = path,
            method = method,
            startTime = startTime
        )

        RequestSignals.fireRequestStarted(startData)

        var error: Throwable? = null
        try {
            next()
        } catch (e: Throwable) {
            error = e
            val errorData = startData.copy(
                duration = if (config.measureDuration) Clock.System.now().toEpochMilliseconds() - startTime else null,
                statusCode = exchange.response.statusCode,
                error = e
            )
            RequestSignals.fireRequestError(errorData)
            throw e
        } finally {
            val duration = if (config.measureDuration) Clock.System.now().toEpochMilliseconds() - startTime else null
            val finishData = startData.copy(
                duration = duration,
                statusCode = exchange.response.statusCode,
                error = error
            )
            RequestSignals.fireRequestFinished(finishData)
        }
    }
    return this
}
