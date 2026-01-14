package codes.yousef.aether.core.context

import codes.yousef.aether.core.auth.UserPrincipal
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

/**
 * TDD Tests for UserContext - coroutine context-based authentication.
 * These tests are written first, before implementation.
 */
class UserContextTest {

    @Test
    fun `UserContext can be added to coroutine context`() = runTest {
        val principal = UserPrincipal("123", "TestUser", setOf("admin"))

        withContext(UserContext(principal)) {
            val retrieved = coroutineContext[UserContext]
            assertNotNull(retrieved)
            assertEquals("123", retrieved.principal.id)
            assertEquals("TestUser", retrieved.principal.name)
        }
    }

    @Test
    fun `currentUser returns principal from context`() = runTest {
        val principal = UserPrincipal("456", "Bob", setOf("user"))

        withContext(UserContext(principal)) {
            val user = currentUser()
            assertNotNull(user)
            assertEquals("Bob", user.name)
            assertEquals("456", user.id)
        }
    }

    @Test
    fun `currentUser returns null when no context`() = runTest {
        val user = currentUser()
        assertNull(user)
    }

    @Test
    fun `requireUser returns principal when context exists`() = runTest {
        val principal = UserPrincipal("789", "Alice", setOf("admin"))

        withContext(UserContext(principal)) {
            val user = requireUser()
            assertEquals("Alice", user.name)
        }
    }

    @Test
    fun `requireUser throws when no context`() = runTest {
        assertFailsWith<IllegalStateException> {
            requireUser()
        }
    }

    @Test
    fun `nested context overrides outer context`() = runTest {
        val outer = UserPrincipal("outer", "Outer User", emptySet())
        val inner = UserPrincipal("inner", "Inner User", emptySet())

        withContext(UserContext(outer)) {
            assertEquals("outer", currentUser()?.id)

            withContext(UserContext(inner)) {
                assertEquals("inner", currentUser()?.id)
            }

            // After inner completes, outer context is restored
            assertEquals("outer", currentUser()?.id)
        }
    }

    @Test
    fun `currentUser preserves roles from principal`() = runTest {
        val principal = UserPrincipal("user1", "User One", setOf("admin", "moderator"))

        withContext(UserContext(principal)) {
            val user = currentUser()
            assertNotNull(user)
            assertEquals(setOf("admin", "moderator"), user.roles)
        }
    }

    @Test
    fun `currentUser preserves attributes from principal`() = runTest {
        val principal = UserPrincipal(
            id = "user1",
            name = "User One",
            roles = emptySet(),
            permissions = emptySet(),
            _attributes = mapOf("email" to "user@example.com")
        )

        withContext(UserContext(principal)) {
            val user = currentUser()
            assertNotNull(user)
            assertEquals("user@example.com", user.attributes["email"])
        }
    }

    @Test
    fun `isAuthenticated returns true when context exists`() = runTest {
        val principal = UserPrincipal("user1", "User", emptySet())

        withContext(UserContext(principal)) {
            assertEquals(true, isAuthenticated())
        }
    }

    @Test
    fun `isAuthenticated returns false when no context`() = runTest {
        assertEquals(false, isAuthenticated())
    }

    @Test
    fun `hasRole checks role in current context`() = runTest {
        val principal = UserPrincipal("user1", "User", setOf("admin", "user"))

        withContext(UserContext(principal)) {
            assertEquals(true, hasRole("admin"))
            assertEquals(true, hasRole("user"))
            assertEquals(false, hasRole("superadmin"))
        }
    }

    @Test
    fun `hasRole returns false when no context`() = runTest {
        assertEquals(false, hasRole("admin"))
    }

    @Test
    fun `hasAnyRole checks any role in current context`() = runTest {
        val principal = UserPrincipal("user1", "User", setOf("editor"))

        withContext(UserContext(principal)) {
            assertEquals(true, hasAnyRole("admin", "editor"))
            assertEquals(false, hasAnyRole("admin", "superadmin"))
        }
    }

    @Test
    fun `hasAllRoles checks all roles in current context`() = runTest {
        val principal = UserPrincipal("user1", "User", setOf("admin", "editor"))

        withContext(UserContext(principal)) {
            assertEquals(true, hasAllRoles("admin", "editor"))
            assertEquals(false, hasAllRoles("admin", "superadmin"))
        }
    }
}
