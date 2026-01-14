package codes.yousef.aether.core.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TDD Tests for AuthStrategy - unified authentication for HTTP and gRPC.
 */
class AuthStrategyTest {

    @Test
    fun `BearerTokenStrategy validates token and returns principal`() = runTest {
        val strategy = BearerTokenStrategy { token ->
            if (token == "valid-token") {
                UserPrincipal("user1", "ValidUser", setOf("user"))
            } else null
        }

        val result = strategy.authenticate("valid-token")
        assertTrue(result is AuthResult.Success)
        assertEquals("ValidUser", (result as AuthResult.Success).principal.name)
    }

    @Test
    fun `BearerTokenStrategy returns failure for invalid token`() = runTest {
        val strategy = BearerTokenStrategy { null }

        val result = strategy.authenticate("invalid-token")
        assertTrue(result is AuthResult.Failure)
    }

    @Test
    fun `BearerTokenStrategy extracts token from Authorization header`() = runTest {
        val strategy = BearerTokenStrategy { token ->
            if (token == "my-jwt-token") {
                UserPrincipal("user1", "User", emptySet())
            } else null
        }

        val token = strategy.extractToken("Bearer my-jwt-token")
        assertEquals("my-jwt-token", token)
    }

    @Test
    fun `BearerTokenStrategy returns null for non-Bearer header`() = runTest {
        val strategy = BearerTokenStrategy { null }

        val token = strategy.extractToken("Basic dXNlcjpwYXNz")
        assertNull(token)
    }

    @Test
    fun `BearerTokenStrategy extracts token case-insensitively`() = runTest {
        val strategy = BearerTokenStrategy { null }

        assertEquals("token123", strategy.extractToken("bearer token123"))
        assertEquals("token123", strategy.extractToken("BEARER token123"))
        assertEquals("token123", strategy.extractToken("Bearer token123"))
    }

    @Test
    fun `BearerTokenStrategy returns null for empty token`() = runTest {
        val strategy = BearerTokenStrategy { null }

        assertNull(strategy.extractToken("Bearer "))
        assertNull(strategy.extractToken("Bearer"))
    }

    @Test
    fun `ApiKeyStrategy validates key and returns principal`() = runTest {
        val strategy = ApiKeyStrategy { key ->
            if (key == "valid-api-key") {
                UserPrincipal("api-user", "API User", setOf("api"))
            } else null
        }

        val result = strategy.authenticate("valid-api-key")
        assertTrue(result is AuthResult.Success)
        assertEquals("api-user", (result as AuthResult.Success).principal.id)
    }

    @Test
    fun `ApiKeyStrategy returns failure for invalid key`() = runTest {
        val strategy = ApiKeyStrategy { null }

        val result = strategy.authenticate("invalid-key")
        assertTrue(result is AuthResult.Failure)
    }

    @Test
    fun `CompositeStrategy tries strategies in order`() = runTest {
        val bearerStrategy = BearerTokenStrategy { token ->
            if (token == "bearer-token") UserPrincipal("bearer-user", "Bearer User", emptySet())
            else null
        }
        val apiKeyStrategy = ApiKeyStrategy { key ->
            if (key == "api-key") UserPrincipal("api-user", "API User", emptySet())
            else null
        }

        val composite = CompositeAuthStrategy(listOf(bearerStrategy, apiKeyStrategy))

        // Test bearer token
        val bearerResult = composite.authenticateWithHeader("Bearer bearer-token")
        assertTrue(bearerResult is AuthResult.Success)
        assertEquals("bearer-user", (bearerResult as AuthResult.Success).principal.id)

        // Test API key (when bearer fails)
        val apiResult = composite.authenticateWithApiKey("api-key")
        assertTrue(apiResult is AuthResult.Success)
        assertEquals("api-user", (apiResult as AuthResult.Success).principal.id)
    }

    @Test
    fun `AuthStrategy can be used with custom token extraction`() = runTest {
        val strategy = BearerTokenStrategy { token ->
            UserPrincipal(token, "User $token", emptySet())
        }

        // Custom header parsing
        val customHeader = "X-Custom-Auth: my-custom-token"
        val token = customHeader.substringAfter(": ")

        val result = strategy.authenticate(token)
        assertTrue(result is AuthResult.Success)
        assertEquals("my-custom-token", (result as AuthResult.Success).principal.id)
    }

    @Test
    fun `AuthStrategy authenticate returns NoCredentials for null input`() = runTest {
        val strategy = BearerTokenStrategy { UserPrincipal("user", "User", emptySet()) }

        val result = strategy.authenticateOrNoCredentials(null)
        assertTrue(result is AuthResult.NoCredentials)
    }

    @Test
    fun `AuthStrategy authenticate returns result for non-null input`() = runTest {
        val strategy = BearerTokenStrategy { UserPrincipal("user", "User", emptySet()) }

        val result = strategy.authenticateOrNoCredentials("valid-token")
        assertTrue(result is AuthResult.Success)
    }
}
