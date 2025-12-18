package codes.yousef.aether.example

import codes.yousef.aether.core.HttpMethod
import codes.yousef.aether.core.security.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * Unit tests for CSRF Protection functionality.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CsrfProtectionTest {

    @Test
    fun testCsrfConfig() {
        val config = CsrfConfig(
            sessionKey = "_my_csrf",
            headerName = "X-CSRF-Token",
            formFieldName = "_csrf",
            queryParamName = "csrf_token",
            tokenLength = 64
        )
        
        assertEquals("_my_csrf", config.sessionKey)
        assertEquals("X-CSRF-Token", config.headerName)
        assertEquals("_csrf", config.formFieldName)
        assertEquals("csrf_token", config.queryParamName)
        assertEquals(64, config.tokenLength)
    }

    @Test
    fun testDefaultCsrfConfig() {
        val config = CsrfConfig()
        
        assertEquals("_csrf_token", config.sessionKey)
        assertEquals("X-CSRF-Token", config.headerName)
        assertEquals("_csrf", config.formFieldName)
        assertEquals(32, config.tokenLength)
        assertEquals(403, config.errorStatusCode)
    }

    @Test
    fun testProtectedMethods() {
        val config = CsrfConfig()
        
        // Protected methods
        assertTrue(HttpMethod.POST in config.protectedMethods)
        assertTrue(HttpMethod.PUT in config.protectedMethods)
        assertTrue(HttpMethod.DELETE in config.protectedMethods)
        assertTrue(HttpMethod.PATCH in config.protectedMethods)
        
        // Safe methods
        assertFalse(HttpMethod.GET in config.protectedMethods)
        assertFalse(HttpMethod.HEAD in config.protectedMethods)
        assertFalse(HttpMethod.OPTIONS in config.protectedMethods)
    }

    @Test
    fun testExcludedPaths() {
        val config = CsrfConfig(
            excludedPaths = setOf("/api/webhook", "/api/public"),
            excludedPathPrefixes = setOf("/api/external/")
        )
        
        assertTrue("/api/webhook" in config.excludedPaths)
        assertTrue("/api/public" in config.excludedPaths)
        assertTrue("/api/external/" in config.excludedPathPrefixes)
    }

    @Test
    fun testTokenRotation() {
        val withRotation = CsrfConfig(rotateTokenOnRequest = true)
        val withoutRotation = CsrfConfig(rotateTokenOnRequest = false)
        
        assertTrue(withRotation.rotateTokenOnRequest)
        assertFalse(withoutRotation.rotateTokenOnRequest)
    }

    @Test
    fun testErrorConfiguration() {
        val config = CsrfConfig(
            errorMessage = "Custom CSRF error",
            errorStatusCode = 400
        )
        
        assertEquals("Custom CSRF error", config.errorMessage)
        assertEquals(400, config.errorStatusCode)
    }
}
