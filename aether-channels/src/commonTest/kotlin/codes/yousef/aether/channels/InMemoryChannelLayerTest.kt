package codes.yousef.aether.channels

import codes.yousef.aether.core.websocket.WebSocketSession
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.*

class InMemoryChannelLayerTest {
    
    private lateinit var layer: InMemoryChannelLayer
    
    @BeforeTest
    fun setup() {
        layer = InMemoryChannelLayer()
    }
    
    @Test
    fun testGroupAdd() = runTest {
        val session = MockWebSocketSession("session1")
        
        layer.groupAdd("test-group", session)
        
        assertTrue(layer.isInGroup("test-group", session))
        assertEquals(1, layer.groupSize("test-group"))
    }
    
    @Test
    fun testGroupDiscard() = runTest {
        val session = MockWebSocketSession("session1")
        
        layer.groupAdd("test-group", session)
        layer.groupDiscard("test-group", session)
        
        assertFalse(layer.isInGroup("test-group", session))
        assertEquals(0, layer.groupSize("test-group"))
    }
    
    @Test
    fun testDiscardAll() = runTest {
        val session = MockWebSocketSession("session1")
        
        layer.groupAdd("group1", session)
        layer.groupAdd("group2", session)
        layer.groupAdd("group3", session)
        
        assertEquals(3, layer.getSessionGroups(session).size)
        
        layer.discardAll(session)
        
        assertEquals(0, layer.getSessionGroups(session).size)
        assertFalse(layer.isInGroup("group1", session))
        assertFalse(layer.isInGroup("group2", session))
        assertFalse(layer.isInGroup("group3", session))
    }
    
    @Test
    fun testGroupSend() = runTest {
        val session1 = MockWebSocketSession("session1")
        val session2 = MockWebSocketSession("session2")
        
        layer.groupAdd("chat", session1)
        layer.groupAdd("chat", session2)
        
        val result = layer.groupSend("chat", "Hello!")
        
        assertEquals(2, result.sentTo)
        assertEquals(0, result.failed)
        assertEquals("Hello!", session1.lastMessage)
        assertEquals("Hello!", session2.lastMessage)
    }
    
    @Test
    fun testGroupSendToEmpty() = runTest {
        val result = layer.groupSend("empty-group", "Hello!")
        
        assertEquals(0, result.sentTo)
        assertEquals(0, result.failed)
    }
    
    @Test
    fun testMultipleSessions() = runTest {
        val sessions = (1..10).map { MockWebSocketSession("session$it") }
        
        for (session in sessions) {
            layer.groupAdd("large-group", session)
        }
        
        assertEquals(10, layer.groupSize("large-group"))
        
        val result = layer.groupSend("large-group", "Broadcast!")
        assertEquals(10, result.sentTo)
    }
    
    @Test
    fun testGetAllGroups() = runTest {
        val session = MockWebSocketSession("session1")
        
        layer.groupAdd("group-a", session)
        layer.groupAdd("group-b", session)
        layer.groupAdd("group-c", session)
        
        val groups = layer.getAllGroups()
        
        assertEquals(3, groups.size)
        assertTrue(groups.contains("group-a"))
        assertTrue(groups.contains("group-b"))
        assertTrue(groups.contains("group-c"))
    }
    
    @Test
    fun testClosedSessionSkipped() = runTest {
        val openSession = MockWebSocketSession("open", isOpen = true)
        val closedSession = MockWebSocketSession("closed", isOpen = false)
        
        layer.groupAdd("test", openSession)
        layer.groupAdd("test", closedSession)
        
        val result = layer.groupSend("test", "Message")
        
        assertEquals(1, result.sentTo)
        assertEquals(1, result.failed)
    }
}

/**
 * Mock WebSocket session for testing.
 */
private class MockWebSocketSession(
    override val id: String,
    override val isOpen: Boolean = true
) : WebSocketSession {
    override val path: String = "/test"
    override val queryParameters: Map<String, List<String>> = emptyMap()
    override val headers: Map<String, String> = emptyMap()
    override val attributes: MutableMap<String, Any?> = mutableMapOf()
    
    var lastMessage: String? = null
    var lastBinaryMessage: ByteArray? = null
    
    override suspend fun sendText(text: String) {
        if (!isOpen) throw IllegalStateException("Session closed")
        lastMessage = text
    }
    
    override suspend fun sendBinary(data: ByteArray) {
        if (!isOpen) throw IllegalStateException("Session closed")
        lastBinaryMessage = data
    }
    
    override suspend fun sendPing(data: ByteArray) {}
    override suspend fun sendPong(data: ByteArray) {}
    override suspend fun close(code: Int, reason: String) {}
    override fun incoming(): Flow<codes.yousef.aether.core.websocket.WebSocketMessage> = emptyFlow()
}
