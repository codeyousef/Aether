package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.Exchange

/**
 * Represents a middleware function that processes an Exchange.
 * Middleware can short-circuit by not calling next(), or pass control by calling next().
 */
typealias Middleware = suspend (exchange: Exchange, next: suspend () -> Unit) -> Unit

/**
 * Pipeline implements the middleware chain pattern (Russian Doll model).
 * Middleware is executed in order, with each middleware deciding whether to call next().
 */
class Pipeline {
    private val middlewares = mutableListOf<Middleware>()

    /**
     * Add middleware to the pipeline.
     * Middleware is executed in the order it's added.
     */
    fun use(middleware: Middleware) {
        middlewares.add(middleware)
    }

    /**
     * Execute the pipeline for the given exchange.
     * Builds a chain where each middleware can call next() to proceed.
     *
     * @param exchange The HTTP exchange to process
     * @param handler The final handler to execute after all middleware
     */
    suspend fun execute(exchange: Exchange, handler: suspend (Exchange) -> Unit) {
        var index = 0

        suspend fun next() {
            if (index < middlewares.size) {
                val middleware = middlewares[index++]
                middleware(exchange, ::next)
            } else {
                handler(exchange)
            }
        }

        next()
    }

    /**
     * Create a new pipeline with additional middleware.
     * The original pipeline is not modified.
     */
    fun copy(): Pipeline {
        val newPipeline = Pipeline()
        newPipeline.middlewares.addAll(this.middlewares)
        return newPipeline
    }

    /**
     * Get the number of middleware in the pipeline.
     */
    fun size(): Int = middlewares.size

    /**
     * Clear all middleware from the pipeline.
     */
    fun clear() {
        middlewares.clear()
    }
}

/**
 * Builder for creating pipelines with a DSL.
 */
fun pipeline(block: Pipeline.() -> Unit): Pipeline {
    return Pipeline().apply(block)
}
