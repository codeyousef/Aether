package codes.yousef.aether.example

import codes.yousef.aether.core.middleware.*
import codes.yousef.aether.core.pipeline.Pipeline
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E tests for Rate Limit Middleware - quota-based request limiting.
 * 
 * These tests focus on the QuotaProvider and InMemoryQuotaProvider APIs
 * which are the core components of the Rate Limit system.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RateLimitMiddlewareE2ETest {
    
    private lateinit var quotaProvider: InMemoryQuotaProvider
    
    @BeforeEach
    fun setup() {
        quotaProvider = InMemoryQuotaProvider(
            limit = 10,
            windowMillis = 60_000 // 1 minute
        )
    }
    
    @Test
    fun `test QuotaUsage calculation for new user`() = runBlocking {
        val usage = quotaProvider.getUsage("user-1")
        
        assertEquals(0, usage.used)
        assertEquals(10, usage.limit)
        assertEquals(10, usage.remaining)
        assertTrue(!usage.isExhausted)
        assertEquals(0.0, usage.percentUsed)
    }
    
    @Test
    fun `test recordUsage decrements remaining`() = runBlocking {
        // Record some usage
        quotaProvider.recordUsage("user-1", 3)
        
        val usage = quotaProvider.getUsage("user-1")
        assertEquals(3, usage.used)
        assertEquals(7, usage.remaining)
        assertEquals(30.0, usage.percentUsed)
    }
    
    @Test
    fun `test quota exhaustion`() = runBlocking {
        // Use all quota
        quotaProvider.recordUsage("user-1", 10)
        
        val usage = quotaProvider.getUsage("user-1")
        assertTrue(usage.isExhausted)
        assertEquals(0, usage.remaining)
        assertEquals(100.0, usage.percentUsed)
        
        // isExhausted check
        assertTrue(quotaProvider.isExhausted("user-1"))
    }
    
    @Test
    fun `test multiple users have separate quotas`() = runBlocking {
        quotaProvider.recordUsage("user-1", 5)
        quotaProvider.recordUsage("user-2", 3)
        
        val usage1 = quotaProvider.getUsage("user-1")
        val usage2 = quotaProvider.getUsage("user-2")
        
        assertEquals(5, usage1.used)
        assertEquals(3, usage2.used)
        assertEquals(5, usage1.remaining)
        assertEquals(7, usage2.remaining)
    }
    
    @Test
    fun `test RateLimitConfig defaults`() {
        val config = RateLimitConfig()
        
        assertEquals(429, config.statusCode)
        assertEquals("Rate limit exceeded", config.errorMessage)
        assertTrue(config.includeHeaders)
        assertTrue(config.excludedPaths.isEmpty())
        assertTrue(config.excludedPathPrefixes.isEmpty())
        assertNotNull(config.quotaProvider)
    }
    
    @Test
    fun `test RateLimitConfig custom settings`() {
        val customProvider = InMemoryQuotaProvider(100, 3600_000)
        
        val config = RateLimitConfig(
            quotaProvider = customProvider,
            statusCode = 503,
            errorMessage = "Service unavailable",
            includeHeaders = false,
            excludedPaths = setOf("/health", "/metrics"),
            excludedPathPrefixes = setOf("/public/", "/static/")
        )
        
        assertEquals(503, config.statusCode)
        assertEquals("Service unavailable", config.errorMessage)
        assertTrue(!config.includeHeaders)
        assertTrue(config.excludedPaths.contains("/health"))
        assertTrue(config.excludedPathPrefixes.contains("/public/"))
    }
    
    @Test
    fun `test InMemoryQuotaProvider clear`() = runBlocking {
        quotaProvider.recordUsage("user-1", 5)
        quotaProvider.recordUsage("user-2", 3)
        
        quotaProvider.clear()
        
        val usage1 = quotaProvider.getUsage("user-1")
        val usage2 = quotaProvider.getUsage("user-2")
        
        assertEquals(0, usage1.used)
        assertEquals(0, usage2.used)
    }
    
    @Test
    fun `test usage accumulates across requests`() = runBlocking {
        quotaProvider.recordUsage("user-1", 1)
        quotaProvider.recordUsage("user-1", 1)
        quotaProvider.recordUsage("user-1", 1)
        
        val usage = quotaProvider.getUsage("user-1")
        assertEquals(3, usage.used)
        assertEquals(7, usage.remaining)
    }
    
    @Test
    fun `test over-limit usage clamps remaining to zero`() = runBlocking {
        quotaProvider.recordUsage("user-1", 15)  // Over limit
        
        val usage = quotaProvider.getUsage("user-1")
        assertEquals(15, usage.used)
        assertEquals(0, usage.remaining)  // Should be clamped to 0
        assertTrue(usage.isExhausted)
    }
    
    @Test
    fun `test QuotaUsage resetsAt is in future`() = runBlocking {
        val now = System.currentTimeMillis()
        val usage = quotaProvider.recordUsage("user-1", 1)
        
        assertTrue(usage.resetsAt > now)
        assertTrue(usage.resetsAt <= now + 60_000)
    }
    
    @Test
    fun `test custom QuotaProvider implementation`() = runBlocking {
        // Implement a custom provider with fixed limits per user type
        val customProvider = object : QuotaProvider {
            private val usageMap = mutableMapOf<String, Long>()
            
            override suspend fun getUsage(key: String): QuotaUsage {
                val limit = if (key.startsWith("premium_")) 1000L else 100L
                val used = usageMap[key] ?: 0L
                return QuotaUsage(
                    used = used,
                    limit = limit,
                    remaining = maxOf(0, limit - used),
                    resetsAt = System.currentTimeMillis() + 3600_000
                )
            }
            
            override suspend fun recordUsage(key: String, amount: Long): QuotaUsage {
                usageMap[key] = (usageMap[key] ?: 0L) + amount
                return getUsage(key)
            }
            
            override suspend fun isExhausted(key: String): Boolean {
                return getUsage(key).isExhausted
            }
        }
        
        // Test premium user
        val premiumUsage = customProvider.recordUsage("premium_user1", 500)
        assertEquals(1000, premiumUsage.limit)
        assertEquals(500, premiumUsage.remaining)
        
        // Test regular user
        val regularUsage = customProvider.recordUsage("regular_user1", 50)
        assertEquals(100, regularUsage.limit)
        assertEquals(50, regularUsage.remaining)
    }
    
    @Test
    fun `test QuotaUsageAttributeKey`() {
        val key = QuotaUsageAttributeKey
        
        assertEquals("aether.ratelimit.usage", key.name)
        assertEquals(QuotaUsage::class, key.type)
    }
    
    @Test
    fun `test rate limit headers config`() {
        val configWithHeaders = RateLimitConfig(includeHeaders = true)
        val configWithoutHeaders = RateLimitConfig(includeHeaders = false)
        
        assertTrue(configWithHeaders.includeHeaders)
        assertTrue(!configWithoutHeaders.includeHeaders)
    }
    
    @Test
    fun `test concurrent usage recording`() = runBlocking {
        val jobs = (1..100).map {
            async {
                quotaProvider.recordUsage("concurrent-user", 1)
            }
        }
        jobs.awaitAll()
        
        val usage = quotaProvider.getUsage("concurrent-user")
        assertEquals(100, usage.used)  // All 100 increments should be recorded
    }
    
    @Test
    fun `test QuotaUsage percentUsed edge cases`() = runBlocking {
        // 0% used
        val emptyUsage = QuotaUsage(used = 0, limit = 100, remaining = 100, resetsAt = 0)
        assertEquals(0.0, emptyUsage.percentUsed)
        
        // 50% used
        val halfUsage = QuotaUsage(used = 50, limit = 100, remaining = 50, resetsAt = 0)
        assertEquals(50.0, halfUsage.percentUsed)
        
        // 100% used
        val fullUsage = QuotaUsage(used = 100, limit = 100, remaining = 0, resetsAt = 0)
        assertEquals(100.0, fullUsage.percentUsed)
        
        // Over 100% (if allowed to go over)
        val overUsage = QuotaUsage(used = 150, limit = 100, remaining = 0, resetsAt = 0)
        assertEquals(150.0, overUsage.percentUsed)
        
        // Zero limit edge case
        val zeroLimitUsage = QuotaUsage(used = 0, limit = 0, remaining = 0, resetsAt = 0)
        assertEquals(0.0, zeroLimitUsage.percentUsed)
    }
    
    @Test
    fun `test QuotaUsage isExhausted`() {
        val notExhausted = QuotaUsage(used = 5, limit = 10, remaining = 5, resetsAt = 0)
        assertTrue(!notExhausted.isExhausted)
        
        val exhausted = QuotaUsage(used = 10, limit = 10, remaining = 0, resetsAt = 0)
        assertTrue(exhausted.isExhausted)
        
        val overExhausted = QuotaUsage(used = 15, limit = 10, remaining = -5, resetsAt = 0)
        assertTrue(overExhausted.isExhausted)
    }
    
    @Test
    fun `test Pipeline installRateLimit extension`() {
        // Just verify the extension function exists and can be called
        val pipeline = Pipeline()
        val result = pipeline.installRateLimit {
            quotaProvider = InMemoryQuotaProvider(100, 60_000)
            statusCode = 429
            errorMessage = "Too many requests"
        }
        
        // Should return the pipeline for chaining
        assertNotNull(result)
    }
    
    @Test
    fun `test different window sizes`() = runBlocking {
        val shortWindow = InMemoryQuotaProvider(limit = 10, windowMillis = 1000)
        val longWindow = InMemoryQuotaProvider(limit = 10, windowMillis = 3600_000)
        
        shortWindow.recordUsage("user", 5)
        longWindow.recordUsage("user", 5)
        
        val shortUsage = shortWindow.getUsage("user")
        val longUsage = longWindow.getUsage("user")
        
        // Both should have same usage
        assertEquals(5, shortUsage.used)
        assertEquals(5, longUsage.used)
        
        // But different reset times
        assertTrue(longUsage.resetsAt > shortUsage.resetsAt)
    }
    
    @Test
    fun `test different rate limits`() = runBlocking {
        val lowLimit = InMemoryQuotaProvider(limit = 5, windowMillis = 60_000)
        val highLimit = InMemoryQuotaProvider(limit = 100, windowMillis = 60_000)
        
        // Use 5 requests
        repeat(5) {
            lowLimit.recordUsage("user", 1)
            highLimit.recordUsage("user", 1)
        }
        
        val lowUsage = lowLimit.getUsage("user")
        val highUsage = highLimit.getUsage("user")
        
        // Low limit should be exhausted
        assertTrue(lowUsage.isExhausted)
        assertEquals(0, lowUsage.remaining)
        
        // High limit should still have capacity
        assertTrue(!highUsage.isExhausted)
        assertEquals(95, highUsage.remaining)
    }
    
    @Test
    fun `test RateLimitConfig excluded paths`() {
        val config = RateLimitConfig(
            excludedPaths = setOf("/health", "/ready", "/metrics"),
            excludedPathPrefixes = setOf("/static/", "/public/", "/api/v1/open/")
        )
        
        assertEquals(3, config.excludedPaths.size)
        assertEquals(3, config.excludedPathPrefixes.size)
        
        // Check specific paths
        assertTrue("/health" in config.excludedPaths)
        assertTrue("/api/v1/open/" in config.excludedPathPrefixes)
    }
    
    @Test
    fun `test isExhausted without recording`() = runBlocking {
        // Initial check - not exhausted
        assertTrue(!quotaProvider.isExhausted("new-user"))
        
        // Record to exhaustion
        quotaProvider.recordUsage("new-user", 10)
        
        // Now exhausted
        assertTrue(quotaProvider.isExhausted("new-user"))
    }
}
