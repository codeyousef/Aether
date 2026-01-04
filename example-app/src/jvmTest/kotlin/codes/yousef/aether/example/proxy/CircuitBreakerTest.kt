package codes.yousef.aether.example.proxy

import codes.yousef.aether.core.proxy.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the Circuit Breaker implementation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CircuitBreakerTest {

    @BeforeEach
    fun reset() {
        CircuitBreakerRegistry.clear()
    }

    @Test
    fun `circuit starts in CLOSED state`() {
        val config = CircuitBreakerConfig(failureThreshold = 3)
        val cb = CircuitBreaker("test-host", config)
        
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState)
        assertTrue(cb.allowRequest(), "Should allow requests when closed")
    }

    @Test
    fun `circuit opens after failure threshold`() {
        val config = CircuitBreakerConfig(
            failureThreshold = 3,
            slidingWindowDuration = 60.seconds
        )
        val cb = CircuitBreaker("test-host", config)
        
        // Record failures up to threshold
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState)
        assertTrue(cb.allowRequest())
        
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState)
        assertTrue(cb.allowRequest())
        
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState)
        assertFalse(cb.allowRequest(), "Should reject requests when open")
    }

    @Test
    fun `circuit ignores non-trigger exceptions`() {
        val config = CircuitBreakerConfig(
            failureThreshold = 2,
            triggerExceptions = setOf("ProxyConnectionException")  // Only connection exceptions
        )
        val cb = CircuitBreaker("test-host", config)
        
        // Record a timeout (not in trigger list)
        cb.recordFailure(ProxyTimeoutException("test", ProxyTimeoutException.TimeoutType.REQUEST))
        cb.recordFailure(ProxyTimeoutException("test", ProxyTimeoutException.TimeoutType.REQUEST))
        
        // Should still be closed because timeout is not in trigger list
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState)
        
        // Now record connection exceptions
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState)
    }

    @Test
    fun `circuit transitions to HALF_OPEN after reset timeout`() = runBlocking {
        val config = CircuitBreakerConfig(
            failureThreshold = 2,
            resetTimeout = 100.milliseconds,
            successThreshold = 1
        )
        val cb = CircuitBreaker("test-host", config)
        
        // Trip the circuit
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState)
        
        // Wait for reset timeout
        delay(150)
        
        // allowRequest should transition to half-open
        assertTrue(cb.allowRequest(), "Should allow probe request")
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.currentState)
    }

    @Test
    fun `circuit closes after success in HALF_OPEN state`() = runBlocking {
        val config = CircuitBreakerConfig(
            failureThreshold = 2,
            resetTimeout = 50.milliseconds,
            successThreshold = 2
        )
        val cb = CircuitBreaker("test-host", config)
        
        // Trip the circuit
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        
        // Wait for half-open
        delay(100)
        cb.allowRequest()
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.currentState)
        
        // Record successes
        cb.recordSuccess()
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.currentState)
        
        cb.recordSuccess()
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState)
    }

    @Test
    fun `circuit reopens on failure in HALF_OPEN state`() = runBlocking {
        val config = CircuitBreakerConfig(
            failureThreshold = 2,
            resetTimeout = 50.milliseconds,
            successThreshold = 2
        )
        val cb = CircuitBreaker("test-host", config)
        
        // Trip the circuit
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        
        // Wait for half-open
        delay(100)
        cb.allowRequest()
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.currentState)
        
        // Failure in half-open should reopen
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState)
    }

    @Test
    fun `manual reset closes the circuit`() {
        val config = CircuitBreakerConfig(failureThreshold = 2)
        val cb = CircuitBreaker("test-host", config)
        
        // Trip the circuit
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        assertEquals(CircuitBreaker.State.OPEN, cb.currentState)
        
        // Manual reset
        cb.reset()
        assertEquals(CircuitBreaker.State.CLOSED, cb.currentState)
        assertTrue(cb.allowRequest())
    }

    @Test
    fun `registry manages circuit breakers per host`() {
        val config = CircuitBreakerConfig(failureThreshold = 5)
        
        val cb1 = CircuitBreakerRegistry.getOrCreate("host1:8080", config)
        val cb2 = CircuitBreakerRegistry.getOrCreate("host2:8080", config)
        val cb1Again = CircuitBreakerRegistry.getOrCreate("host1:8080", config)
        
        // Should be same instance for same host
        assertTrue(cb1 === cb1Again, "Should return same circuit breaker for same host")
        assertTrue(cb1 !== cb2, "Should return different circuit breakers for different hosts")
        
        assertEquals("host1:8080", cb1.name)
        assertEquals("host2:8080", cb2.name)
    }

    @Test
    fun `registry getAllStats returns stats for all breakers`() {
        val config = CircuitBreakerConfig(failureThreshold = 3)
        
        val cb1 = CircuitBreakerRegistry.getOrCreate("host1", config)
        val cb2 = CircuitBreakerRegistry.getOrCreate("host2", config)
        
        cb1.recordFailure(ProxyConnectionException("test", "failed"))
        cb2.recordFailure(ProxyConnectionException("test", "failed"))
        cb2.recordFailure(ProxyConnectionException("test", "failed"))
        
        val stats = CircuitBreakerRegistry.getAllStats()
        
        assertEquals(2, stats.size)
        
        val host1Stats = stats.find { it.name == "host1" }
        val host2Stats = stats.find { it.name == "host2" }
        
        assertNotNull(host1Stats)
        assertNotNull(host2Stats)
        
        assertEquals(1, host1Stats!!.failureCount)
        assertEquals(2, host2Stats!!.failureCount)
    }

    @Test
    fun `stats includes time until retry when open`() = runBlocking {
        val config = CircuitBreakerConfig(
            failureThreshold = 2,
            resetTimeout = 1.seconds
        )
        val cb = CircuitBreaker("test-host", config)
        
        // Trip the circuit
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        cb.recordFailure(ProxyConnectionException("test", "failed"))
        
        val stats = cb.getStats()
        assertEquals(CircuitBreaker.State.OPEN, stats.state)
        
        val timeUntilRetry = stats.timeUntilRetry()
        assertNotNull(timeUntilRetry)
        assertTrue(timeUntilRetry!!.inWholeMilliseconds > 0)
        assertTrue(timeUntilRetry.inWholeMilliseconds <= 1000)
    }

    private fun assertNotNull(value: Any?) {
        kotlin.test.assertNotNull(value)
    }
}
