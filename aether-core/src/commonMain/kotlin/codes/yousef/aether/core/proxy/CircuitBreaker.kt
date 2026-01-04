package codes.yousef.aether.core.proxy

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * Thread-safe circuit breaker implementation with sliding window failure tracking.
 * 
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Circuit is tripped, requests are rejected immediately
 * - HALF_OPEN: Testing if upstream has recovered
 * 
 * The circuit opens when failure count in the sliding window exceeds the threshold.
 * After resetTimeout, the circuit moves to half-open to probe with limited requests.
 * If probes succeed, the circuit closes. If they fail, it reopens.
 */
class CircuitBreaker(
    val name: String,
    private val config: CircuitBreakerConfig
) {
    private val state = atomic(State.CLOSED)
    private val lock = reentrantLock()
    
    // Sliding window tracking
    private val failureTimestamps = mutableListOf<Instant>()
    private val successTimestamps = mutableListOf<Instant>()
    
    // Half-open state tracking
    private var halfOpenSuccessCount = atomic(0)
    private var halfOpenRequestCount = atomic(0)
    
    // When the circuit was opened
    private var openedAt: Instant? = null
    
    /**
     * Circuit breaker states.
     */
    enum class State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }
    
    /**
     * Get the current state of the circuit breaker.
     */
    val currentState: State get() = state.value
    
    /**
     * Check if a request should be allowed through.
     * Returns true if allowed, false if the circuit is open.
     */
    fun allowRequest(): Boolean {
        return when (state.value) {
            State.CLOSED -> true
            State.OPEN -> tryTransitionToHalfOpen()
            State.HALF_OPEN -> allowHalfOpenRequest()
        }
    }
    
    /**
     * Record a successful request.
     */
    fun recordSuccess() {
        val now = Clock.System.now()
        
        lock.withLock {
            when (state.value) {
                State.CLOSED -> {
                    successTimestamps.add(now)
                    pruneOldEntries(now)
                }
                State.HALF_OPEN -> {
                    val count = halfOpenSuccessCount.incrementAndGet()
                    if (count >= config.successThreshold) {
                        transitionToClosed()
                    }
                }
                State.OPEN -> {
                    // Shouldn't happen, but ignore
                }
            }
        }
    }
    
    /**
     * Record a failed request.
     */
    fun recordFailure(exception: Throwable? = null) {
        // Check if this exception type should trigger the circuit breaker
        if (exception != null && config.triggerExceptions.isNotEmpty()) {
            val exceptionName = exception::class.simpleName ?: "Unknown"
            if (exceptionName !in config.triggerExceptions) {
                return  // Don't count this failure
            }
        }
        
        val now = Clock.System.now()
        
        lock.withLock {
            when (state.value) {
                State.CLOSED -> {
                    failureTimestamps.add(now)
                    pruneOldEntries(now)
                    
                    if (shouldTrip()) {
                        transitionToOpen(now)
                    }
                }
                State.HALF_OPEN -> {
                    // Any failure in half-open state reopens the circuit
                    transitionToOpen(now)
                }
                State.OPEN -> {
                    // Already open, update the timestamp
                    openedAt = now
                }
            }
        }
    }
    
    /**
     * Manually reset the circuit breaker to closed state.
     */
    fun reset() {
        lock.withLock {
            transitionToClosed()
        }
    }
    
    /**
     * Get statistics about the circuit breaker.
     */
    fun getStats(): CircuitBreakerStats {
        val now = Clock.System.now()
        lock.withLock {
            pruneOldEntries(now)
            return CircuitBreakerStats(
                name = name,
                state = state.value,
                failureCount = failureTimestamps.size,
                successCount = successTimestamps.size,
                openedAt = openedAt,
                config = config
            )
        }
    }
    
    /**
     * Check if we should trip the circuit.
     */
    private fun shouldTrip(): Boolean {
        return failureTimestamps.size >= config.failureThreshold
    }
    
    /**
     * Try to transition from OPEN to HALF_OPEN if reset timeout has elapsed.
     */
    private fun tryTransitionToHalfOpen(): Boolean {
        val now = Clock.System.now()
        val opened = openedAt ?: return false
        
        if (now - opened >= config.resetTimeout) {
            lock.withLock {
                if (state.value == State.OPEN) {
                    state.value = State.HALF_OPEN
                    halfOpenSuccessCount.value = 0
                    halfOpenRequestCount.value = 0
                    return true
                }
            }
        }
        
        return false
    }
    
    /**
     * Check if a half-open request should be allowed.
     */
    private fun allowHalfOpenRequest(): Boolean {
        // Only allow a limited number of concurrent requests in half-open state
        val requestCount = halfOpenRequestCount.incrementAndGet()
        return requestCount <= config.successThreshold + 1  // Allow slightly more than success threshold
    }
    
    /**
     * Transition to OPEN state.
     */
    private fun transitionToOpen(now: Instant) {
        state.value = State.OPEN
        openedAt = now
        failureTimestamps.clear()
        successTimestamps.clear()
    }
    
    /**
     * Transition to CLOSED state.
     */
    private fun transitionToClosed() {
        state.value = State.CLOSED
        openedAt = null
        failureTimestamps.clear()
        successTimestamps.clear()
        halfOpenSuccessCount.value = 0
        halfOpenRequestCount.value = 0
    }
    
    /**
     * Remove entries outside the sliding window.
     */
    private fun pruneOldEntries(now: Instant) {
        val cutoff = now - config.slidingWindowDuration
        failureTimestamps.removeAll { it < cutoff }
        successTimestamps.removeAll { it < cutoff }
        
        // Also enforce max window size
        while (failureTimestamps.size > config.slidingWindowSize) {
            failureTimestamps.removeAt(0)
        }
        while (successTimestamps.size > config.slidingWindowSize) {
            successTimestamps.removeAt(0)
        }
    }
}

/**
 * Statistics about a circuit breaker.
 */
data class CircuitBreakerStats(
    val name: String,
    val state: CircuitBreaker.State,
    val failureCount: Int,
    val successCount: Int,
    val openedAt: Instant?,
    val config: CircuitBreakerConfig
) {
    /**
     * Time until the circuit breaker will attempt to close (if open).
     */
    fun timeUntilRetry(): Duration? {
        if (state != CircuitBreaker.State.OPEN || openedAt == null) return null
        val elapsed = Clock.System.now() - openedAt!!
        val remaining = config.resetTimeout - elapsed
        return if (remaining.isPositive()) remaining else Duration.ZERO
    }
}

/**
 * Registry for managing circuit breakers per upstream host.
 * Thread-safe singleton pattern.
 */
object CircuitBreakerRegistry {
    private val circuitBreakers = mutableMapOf<String, CircuitBreaker>()
    private val lock = reentrantLock()
    
    /**
     * Get or create a circuit breaker for the given host.
     */
    fun getOrCreate(host: String, config: CircuitBreakerConfig): CircuitBreaker {
        return lock.withLock {
            circuitBreakers.getOrPut(host) {
                CircuitBreaker(host, config)
            }
        }
    }
    
    /**
     * Get a circuit breaker by name if it exists.
     */
    fun get(host: String): CircuitBreaker? {
        return lock.withLock {
            circuitBreakers[host]
        }
    }
    
    /**
     * Get all circuit breakers.
     */
    fun getAll(): List<CircuitBreaker> {
        return lock.withLock {
            circuitBreakers.values.toList()
        }
    }
    
    /**
     * Get statistics for all circuit breakers.
     */
    fun getAllStats(): List<CircuitBreakerStats> {
        return getAll().map { it.getStats() }
    }
    
    /**
     * Reset a specific circuit breaker.
     */
    fun reset(host: String) {
        lock.withLock {
            circuitBreakers[host]?.reset()
        }
    }
    
    /**
     * Reset all circuit breakers.
     */
    fun resetAll() {
        lock.withLock {
            circuitBreakers.values.forEach { it.reset() }
        }
    }
    
    /**
     * Remove a circuit breaker.
     */
    fun remove(host: String) {
        lock.withLock {
            circuitBreakers.remove(host)
        }
    }
    
    /**
     * Clear all circuit breakers.
     */
    fun clear() {
        lock.withLock {
            circuitBreakers.clear()
        }
    }
}
