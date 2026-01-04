package codes.yousef.aether.core.middleware

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.pipeline.Middleware
import codes.yousef.aether.core.pipeline.Pipeline
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.datetime.Clock

/**
 * Represents a quota usage record.
 */
data class QuotaUsage(
    val used: Long,
    val limit: Long,
    val remaining: Long,
    val resetsAt: Long
) {
    val isExhausted: Boolean get() = remaining <= 0
    val percentUsed: Double get() = if (limit > 0) (used.toDouble() / limit) * 100 else 0.0
}

/**
 * Attribute key for accessing quota usage from an Exchange.
 */
val QuotaUsageAttributeKey = AttributeKey<QuotaUsage>("aether.ratelimit.usage", QuotaUsage::class)

/**
 * Interface for quota checking strategies.
 */
interface QuotaProvider {
    /**
     * Get the current quota usage for the given key.
     * @param key The identifier (e.g., user ID, IP address)
     * @return Current quota usage, or null if no quota applies
     */
    suspend fun getUsage(key: String): QuotaUsage?

    /**
     * Record usage against the quota.
     * @param key The identifier
     * @param amount The amount to deduct (default 1)
     * @return Updated quota usage
     */
    suspend fun recordUsage(key: String, amount: Long = 1): QuotaUsage

    /**
     * Check if the quota is exhausted without recording usage.
     * @param key The identifier
     * @return true if quota is exhausted
     */
    suspend fun isExhausted(key: String): Boolean
}

/**
 * In-memory quota provider using sliding window rate limiting.
 */
class InMemoryQuotaProvider(
    private val limit: Long,
    private val windowMillis: Long
) : QuotaProvider {
    
    private data class BucketEntry(
        val count: Long,
        val windowStart: Long
    )
    
    private val buckets = atomic(mapOf<String, BucketEntry>())

    override suspend fun getUsage(key: String): QuotaUsage {
        val now = Clock.System.now().toEpochMilliseconds()
        val entry = buckets.value[key]
        
        return if (entry == null || isExpired(entry, now)) {
            QuotaUsage(
                used = 0,
                limit = limit,
                remaining = limit,
                resetsAt = now + windowMillis
            )
        } else {
            QuotaUsage(
                used = entry.count,
                limit = limit,
                remaining = maxOf(0, limit - entry.count),
                resetsAt = entry.windowStart + windowMillis
            )
        }
    }

    override suspend fun recordUsage(key: String, amount: Long): QuotaUsage {
        val now = Clock.System.now().toEpochMilliseconds()
        
        var newEntry: BucketEntry? = null
        buckets.update { current ->
            val existing = current[key]
            newEntry = if (existing == null || isExpired(existing, now)) {
                BucketEntry(amount, now)
            } else {
                BucketEntry(existing.count + amount, existing.windowStart)
            }
            current + (key to newEntry!!)
        }
        
        val entry = newEntry!!
        return QuotaUsage(
            used = entry.count,
            limit = limit,
            remaining = maxOf(0, limit - entry.count),
            resetsAt = entry.windowStart + windowMillis
        )
    }

    override suspend fun isExhausted(key: String): Boolean {
        val usage = getUsage(key)
        return usage.isExhausted
    }
    
    private fun isExpired(entry: BucketEntry, now: Long): Boolean {
        return now >= entry.windowStart + windowMillis
    }

    /**
     * Clear all quota buckets. Useful for testing.
     */
    fun clear() {
        buckets.update { emptyMap() }
    }
}

/**
 * Configuration for the rate limit middleware.
 */
data class RateLimitConfig(
    /**
     * Function to extract the quota key from an exchange.
     * Default uses IP address from X-Forwarded-For or remote address.
     */
    var keyExtractor: suspend (Exchange) -> String? = { exchange ->
        exchange.request.headers.get("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: exchange.request.headers.get("X-Real-IP")
            ?: "anonymous"
    },

    /**
     * The quota provider implementation.
     * Default is an in-memory provider with 100 requests per minute.
     */
    var quotaProvider: QuotaProvider = InMemoryQuotaProvider(
        limit = 100,
        windowMillis = 60_000
    ),

    /**
     * HTTP status code to return when quota is exhausted.
     * Default is 429 Too Many Requests.
     */
    var statusCode: Int = 429,

    /**
     * Error message to return when quota is exhausted.
     */
    var errorMessage: String = "Rate limit exceeded",

    /**
     * Whether to add rate limit headers to responses.
     */
    var includeHeaders: Boolean = true,

    /**
     * Paths to exclude from quota checking.
     */
    var excludedPaths: Set<String> = emptySet(),

    /**
     * Path prefixes to exclude from quota checking.
     */
    var excludedPathPrefixes: Set<String> = emptySet(),

    /**
     * Cost function to determine how much quota each request consumes.
     * Default is 1 per request.
     */
    var costFunction: suspend (Exchange) -> Long = { 1L },

    /**
     * Custom handler for quota exhausted scenarios.
     * If null, uses default JSON response.
     */
    var exhaustedHandler: (suspend (Exchange, QuotaUsage) -> Unit)? = null
)

/**
 * Rate limit middleware for quota-based request limiting.
 *
 * This middleware checks a quota for each request and blocks requests when
 * the quota is exhausted. It's designed for scenarios like:
 * - API rate limiting
 * - User credit systems
 * - Resource consumption tracking
 *
 * Example:
 * ```kotlin
 * pipeline.installRateLimit {
 *     quotaProvider = InMemoryQuotaProvider(
 *         limit = 1000,
 *         windowMillis = 3600_000 // 1 hour
 *     )
 *     keyExtractor = { exchange ->
 *         exchange.attributes.get(UserAttributeKey)?.id?.toString()
 *             ?: exchange.request.headers.get("X-API-Key")
 *     }
 *     costFunction = { exchange ->
 *         // Heavy endpoints cost more
 *         if (exchange.request.path.startsWith("/api/ai/")) 10L else 1L
 *     }
 * }
 * ```
 */
class RateLimitMiddleware(
    private val config: RateLimitConfig
) {
    /**
     * Create the middleware function.
     */
    fun asMiddleware(): Middleware = { exchange, next ->
        processRequest(exchange, next)
    }
    
    private suspend fun processRequest(exchange: Exchange, next: suspend () -> Unit) {
        // Check exclusions
        if (shouldSkip(exchange)) {
            next()
            return
        }

        // Extract key
        val key = config.keyExtractor(exchange)
        if (key == null) {
            next()
            return
        }

        // Check quota before processing
        val preUsage = config.quotaProvider.getUsage(key)
        if (preUsage != null && preUsage.isExhausted) {
            handleExhausted(exchange, preUsage)
            return
        }

        // Calculate cost
        val cost = config.costFunction(exchange)

        // Record usage
        val usage = config.quotaProvider.recordUsage(key, cost)

        // Store usage in attributes
        exchange.attributes.put(QuotaUsageAttributeKey, usage)

        // Add headers
        if (config.includeHeaders) {
            exchange.response.setHeader("X-RateLimit-Limit", usage.limit.toString())
            exchange.response.setHeader("X-RateLimit-Remaining", usage.remaining.toString())
            exchange.response.setHeader("X-RateLimit-Reset", usage.resetsAt.toString())
        }

        // Check if this request exhausted the quota
        if (usage.isExhausted) {
            handleExhausted(exchange, usage)
            return
        }

        next()
    }

    private fun shouldSkip(exchange: Exchange): Boolean {
        val path = exchange.request.path

        if (path in config.excludedPaths) {
            return true
        }

        for (prefix in config.excludedPathPrefixes) {
            if (path.startsWith(prefix)) {
                return true
            }
        }

        return false
    }

    private suspend fun handleExhausted(exchange: Exchange, usage: QuotaUsage) {
        if (config.includeHeaders) {
            exchange.response.setHeader("X-RateLimit-Limit", usage.limit.toString())
            exchange.response.setHeader("X-RateLimit-Remaining", "0")
            exchange.response.setHeader("X-RateLimit-Reset", usage.resetsAt.toString())
            exchange.response.setHeader("Retry-After", ((usage.resetsAt - Clock.System.now().toEpochMilliseconds()) / 1000).toString())
        }

        val handler = config.exhaustedHandler
        if (handler != null) {
            handler(exchange, usage)
        } else {
            exchange.respond(config.statusCode, config.errorMessage)
        }
    }
}

/**
 * Install the rate limit middleware for quota-based request limiting.
 *
 * @param configure Configuration block for the middleware.
 */
fun Pipeline.installRateLimit(configure: RateLimitConfig.() -> Unit = {}): Pipeline {
    val config = RateLimitConfig().apply(configure)
    use(RateLimitMiddleware(config).asMiddleware())
    return this
}

/**
 * Create a rate limit middleware with database-backed credits.
 * This is useful when you need persistent quota tracking across server restarts.
 *
 * @param getCredits Function to get current credits for a user.
 * @param deductCredits Function to deduct credits from a user.
 */
fun Pipeline.installRateLimitWithCredits(
    getCredits: suspend (String) -> Long,
    deductCredits: suspend (String, Long) -> Long,
    configure: RateLimitConfig.() -> Unit = {}
): Pipeline {
    val creditProvider = object : QuotaProvider {
        override suspend fun getUsage(key: String): QuotaUsage {
            val credits = getCredits(key)
            return QuotaUsage(
                used = 0, // Not tracked in credit systems
                limit = credits,
                remaining = credits,
                resetsAt = 0 // Credits don't auto-reset
            )
        }

        override suspend fun recordUsage(key: String, amount: Long): QuotaUsage {
            val remaining = deductCredits(key, amount)
            return QuotaUsage(
                used = amount,
                limit = remaining + amount,
                remaining = remaining,
                resetsAt = 0
            )
        }

        override suspend fun isExhausted(key: String): Boolean {
            return getCredits(key) <= 0
        }
    }

    return installRateLimit {
        quotaProvider = creditProvider
        configure()
    }
}
