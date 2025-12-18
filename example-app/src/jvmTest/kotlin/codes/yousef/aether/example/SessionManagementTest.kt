package codes.yousef.aether.example

import codes.yousef.aether.core.session.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Unit tests for Session Management functionality.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionManagementTest {

    private lateinit var sessionStore: InMemorySessionStore

    @BeforeEach
    fun setup() {
        sessionStore = InMemorySessionStore()
    }

    @Test
    fun testSessionCreation() = runBlocking {
        val session = DefaultSession(
            id = "test-session-123",
            createdAt = System.currentTimeMillis()
        )
        
        assertEquals("test-session-123", session.id)
        assertTrue(session.isNew)
        assertFalse(session.isInvalidated)
    }

    @Test
    fun testSessionDataStorage() = runBlocking {
        val session = DefaultSession(
            id = "test-session-456",
            createdAt = System.currentTimeMillis()
        )
        
        // Test setting and getting values
        session.set("user", "testuser")
        session.set("role", "admin")
        session.set("count", 42)
        
        assertEquals("testuser", session.getString("user"))
        assertEquals("admin", session.getString("role"))
        assertEquals(42, session.getInt("count"))
    }

    @Test
    fun testSessionInvalidation() = runBlocking {
        val session = DefaultSession(
            id = "test-session-789",
            createdAt = System.currentTimeMillis()
        )
        
        session.set("data", "important")
        assertFalse(session.isInvalidated)
        
        session.invalidate()
        assertTrue(session.isInvalidated)
    }

    @Test
    fun testSessionStore() = runBlocking {
        val session = DefaultSession(
            id = "stored-session",
            createdAt = System.currentTimeMillis()
        )
        session.set("key", "value")
        
        // Save session
        sessionStore.save(session)
        
        // Retrieve session
        val retrieved = sessionStore.get("stored-session")
        assertNotNull(retrieved)
        assertEquals("stored-session", retrieved.id)
        assertEquals("value", retrieved.getString("key"))
    }

    @Test
    fun testSessionStoreDelete() = runBlocking {
        val session = DefaultSession(
            id = "to-delete",
            createdAt = System.currentTimeMillis()
        )
        
        sessionStore.save(session)
        assertNotNull(sessionStore.get("to-delete"))
        
        sessionStore.delete("to-delete")
        assertNull(sessionStore.get("to-delete"))
    }

    @Test
    fun testSessionKeys() = runBlocking {
        val session = DefaultSession(
            id = "keys-test",
            createdAt = System.currentTimeMillis()
        )
        
        session.set("key1", "value1")
        session.set("key2", "value2")
        session.set("key3", "value3")
        
        val keys = session.keys()
        assertEquals(3, keys.size)
        assertTrue("key1" in keys)
        assertTrue("key2" in keys)
        assertTrue("key3" in keys)
    }

    @Test
    fun testSessionRemove() = runBlocking {
        val session = DefaultSession(
            id = "remove-test",
            createdAt = System.currentTimeMillis()
        )
        
        session.set("toRemove", "data")
        assertNotNull(session.get("toRemove"))
        
        val removed = session.remove("toRemove")
        assertEquals("data", removed)
        assertNull(session.get("toRemove"))
    }

    @Test
    fun testSessionClear() = runBlocking {
        val session = DefaultSession(
            id = "clear-test",
            createdAt = System.currentTimeMillis()
        )
        
        session.set("key1", "value1")
        session.set("key2", "value2")
        
        assertEquals(2, session.keys().size)
        
        session.clear()
        
        assertEquals(0, session.keys().size)
    }

    @Test
    fun testSessionConfig() {
        val config = SessionConfig(
            cookieName = "MY_SESSION",
            maxAge = 7200L,
            secure = true,
            httpOnly = true,
            sameSite = SameSitePolicy.STRICT
        )
        
        assertEquals("MY_SESSION", config.cookieName)
        assertEquals(7200L, config.maxAge)
        assertTrue(config.secure)
        assertTrue(config.httpOnly)
        assertEquals(SameSitePolicy.STRICT, config.sameSite)
    }

    @Test
    fun testTypedSessionValues() = runBlocking {
        val session = DefaultSession(
            id = "typed-test",
            createdAt = System.currentTimeMillis()
        )
        
        session.set("stringVal", "hello")
        session.set("intVal", 42)
        session.set("longVal", 1000000L)
        session.set("boolVal", true)
        session.set("doubleVal", 3.14)
        
        assertEquals("hello", session.getString("stringVal"))
        assertEquals(42, session.getInt("intVal"))
        assertEquals(1000000L, session.getLong("longVal"))
        assertEquals(true, session.getBoolean("boolVal"))
        assertEquals(3.14, session.getDouble("doubleVal"))
    }
}
