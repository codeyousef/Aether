package codes.yousef.aether.example

import codes.yousef.aether.core.auth.*
import codes.yousef.aether.core.HttpMethod
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Unit tests for Authentication functionality.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationTest {

    @Test
    fun testUserPrincipal() {
        val principal = UserPrincipal(
            id = "user-123",
            name = "John Doe",
            roles = setOf("admin", "user")
        )
        
        assertEquals("user-123", principal.id)
        assertEquals("John Doe", principal.name)
        assertTrue(principal.hasRole("admin"))
        assertTrue(principal.hasRole("user"))
        assertFalse(principal.hasRole("guest"))
    }

    @Test
    fun testPrincipalRoleChecks() {
        val principal = UserPrincipal(
            id = "user-456",
            name = "Jane Smith",
            roles = setOf("admin", "editor", "viewer")
        )
        
        // Test hasAnyRole
        assertTrue(principal.hasAnyRole("admin", "guest"))
        assertTrue(principal.hasAnyRole("editor"))
        assertFalse(principal.hasAnyRole("guest", "moderator"))
        
        // Test hasAllRoles
        assertTrue(principal.hasAllRoles("admin", "editor"))
        assertTrue(principal.hasAllRoles("viewer"))
        assertFalse(principal.hasAllRoles("admin", "guest"))
    }

    @Test
    fun testAuthResultSuccess() {
        val principal = UserPrincipal("id", "name", emptySet())
        val result = AuthResult.Success(principal)
        
        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
        assertEquals(principal, result.principalOrNull())
    }

    @Test
    fun testAuthResultFailure() {
        val result = AuthResult.Failure("Invalid credentials")
        
        assertFalse(result.isSuccess)
        assertTrue(result.isFailure)
        assertNull(result.principalOrNull())
    }

    @Test
    fun testAuthResultNoCredentials() {
        val result = AuthResult.NoCredentials
        
        assertFalse(result.isSuccess)
        assertFalse(result.isFailure)
        assertNull(result.principalOrNull())
    }

    @Test
    fun testCredentialsTypes() {
        // Test UsernamePassword
        val userPass = Credentials.UsernamePassword("admin", "secret")
        assertEquals("admin", userPass.username)
        assertEquals("secret", userPass.password)
        
        // Test BearerToken
        val bearer = Credentials.BearerToken("jwt-token-here")
        assertEquals("jwt-token-here", bearer.token)
        
        // Test Basic
        val basic = Credentials.Basic("user", "pass")
        assertEquals("user", basic.username)
        assertEquals("pass", basic.password)
        
        // Test ApiKey
        val apiKey = Credentials.ApiKey("api-key-123", ApiKeySource.HEADER)
        assertEquals("api-key-123", apiKey.key)
        assertEquals(ApiKeySource.HEADER, apiKey.source)
    }

    @Test
    fun testAuthConfig() {
        val config = AuthConfig(
            realm = "MyApp",
            required = true,
            excludedPaths = setOf("/public", "/health"),
            excludedPathPrefixes = setOf("/api/v1/public")
        )
        
        assertEquals("MyApp", config.realm)
        assertTrue(config.required)
        assertTrue("/public" in config.excludedPaths)
        assertTrue("/api/v1/public" in config.excludedPathPrefixes)
    }

    @Test
    fun testJwtConfig() {
        val config = JwtConfig(
            secret = "my-secret-key",
            issuer = "my-app",
            audience = "api-users",
            leewaySeconds = 60
        )
        
        assertEquals("my-secret-key", config.secret)
        assertEquals("my-app", config.issuer)
        assertEquals("api-users", config.audience)
        assertEquals(60, config.leewaySeconds)
    }

    @Test
    fun testApiKeyConfig() {
        val config = ApiKeyConfig(
            headerName = "X-API-Key",
            queryParamName = "api_key",
            sources = setOf(ApiKeySource.HEADER, ApiKeySource.QUERY_PARAM)
        )
        
        assertEquals("X-API-Key", config.headerName)
        assertEquals("api_key", config.queryParamName)
        assertTrue(ApiKeySource.HEADER in config.sources)
        assertTrue(ApiKeySource.QUERY_PARAM in config.sources)
        assertFalse(ApiKeySource.COOKIE in config.sources)
    }

    @Test
    fun testPrincipalAttributes() {
        val principal = UserPrincipal(
            id = "user-789",
            name = "Bob",
            roles = setOf("user"),
            _attributes = mapOf("department" to "engineering", "level" to "senior")
        )
        
        assertEquals("engineering", principal.attributes["department"])
        assertEquals("senior", principal.attributes["level"])
    }
}
