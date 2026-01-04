package codes.yousef.aether.example

import codes.yousef.aether.channels.*
import codes.yousef.aether.core.websocket.WebSocketMessage
import codes.yousef.aether.core.websocket.WebSocketSession
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mock WebSocket session for testing channels.
 */
class TestWebSocketSession(
    override val id: String,
    override val path: String = "/test",
    override val queryParameters: Map<String, List<String>> = emptyMap(),
    override val headers: Map<String, String> = emptyMap(),
    override val attributes: MutableMap<String, Any?> = mutableMapOf(),
    override var isOpen: Boolean = true
) : WebSocketSession {
    val receivedTextMessages = mutableListOf<String>()
    val receivedBinaryMessages = mutableListOf<ByteArray>()
    
    override suspend fun sendText(text: String) {
        receivedTextMessages.add(text)
    }
    
    override suspend fun sendBinary(data: ByteArray) {
        receivedBinaryMessages.add(data)
    }
    
    override suspend fun sendPing(data: ByteArray) {}
    override suspend fun sendPong(data: ByteArray) {}
    override suspend fun close(code: Int, reason: String) {
        isOpen = false
    }
    override fun incoming(): Flow<WebSocketMessage> = emptyFlow()
}

/**
 * E2E tests for Aether Channels - WebSocket pub/sub layer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChannelsE2ETest {
    
    private lateinit var channelLayer: InMemoryChannelLayer
    
    @BeforeEach
    fun setup() {
        channelLayer = InMemoryChannelLayer()
    }
    
    @Test
    fun `test groupAdd adds session to group`() = runBlocking {
        val session = TestWebSocketSession("session-1")
        
        // Add a session to a group
        channelLayer.groupAdd("chat-room-1", session)
        
        // Verify by checking group size
        val size = channelLayer.groupSize("chat-room-1")
        assertEquals(1, size)
        
        // Verify by sending a message
        val result = channelLayer.groupSend("chat-room-1", "Hello!")
        assertEquals(1, result.sentTo)
        assertEquals(1, session.receivedTextMessages.size)
        assertEquals("Hello!", session.receivedTextMessages[0])
    }
    
    @Test
    fun `test groupDiscard removes session from group`() = runBlocking {
        val session1 = TestWebSocketSession("session-1")
        val session2 = TestWebSocketSession("session-2")
        
        // Add sessions to a group
        channelLayer.groupAdd("room-1", session1)
        channelLayer.groupAdd("room-1", session2)
        assertEquals(2, channelLayer.groupSize("room-1"))
        
        // Remove one session
        channelLayer.groupDiscard("room-1", session1)
        assertEquals(1, channelLayer.groupSize("room-1"))
        
        // Send message - should only reach session2
        val result = channelLayer.groupSend("room-1", "After discard")
        assertEquals(1, result.sentTo)
        assertEquals(0, session1.receivedTextMessages.size)
        assertEquals(1, session2.receivedTextMessages.size)
    }
    
    @Test
    fun `test groupSend delivers message to all group members`() = runBlocking {
        val session1 = TestWebSocketSession("session-1")
        val session2 = TestWebSocketSession("session-2")
        val session3 = TestWebSocketSession("session-3")
        
        // Add multiple sessions to a group
        channelLayer.groupAdd("broadcast-room", session1)
        channelLayer.groupAdd("broadcast-room", session2)
        channelLayer.groupAdd("broadcast-room", session3)
        
        // Send message to the group
        val result = channelLayer.groupSend("broadcast-room", "Broadcast message")
        
        // Verify result
        assertEquals(3, result.sentTo)
        assertEquals(0, result.failed)
        assertTrue(session1.receivedTextMessages.contains("Broadcast message"))
        assertTrue(session2.receivedTextMessages.contains("Broadcast message"))
        assertTrue(session3.receivedTextMessages.contains("Broadcast message"))
    }
    
    @Test
    fun `test send to empty group succeeds with zero recipients`() = runBlocking {
        // Send to a group with no members
        val result = channelLayer.groupSend("empty-room", "Hello empty room")
        
        assertEquals(0, result.sentTo)
        assertEquals(0, result.failed)
    }
    
    @Test
    fun `test multiple groups isolation`() = runBlocking {
        val sessionA1 = TestWebSocketSession("session-a-1")
        val sessionA2 = TestWebSocketSession("session-a-2")
        val sessionB = TestWebSocketSession("session-b")
        
        // Add sessions to different groups
        channelLayer.groupAdd("room-a", sessionA1)
        channelLayer.groupAdd("room-a", sessionA2)
        channelLayer.groupAdd("room-b", sessionB)
        
        // Send to room-a - should only reach 2 sessions
        val resultA = channelLayer.groupSend("room-a", "Message for A")
        val resultB = channelLayer.groupSend("room-b", "Message for B")
        
        assertEquals(2, resultA.sentTo)
        assertEquals(1, resultB.sentTo)
        assertEquals(1, sessionA1.receivedTextMessages.size)
        assertEquals(0, sessionB.receivedTextMessages.filter { it == "Message for A" }.size)
    }
    
    @Test
    fun `test Channels singleton convenience API`() = runBlocking {
        val testLayer = InMemoryChannelLayer()
        Channels.initialize(testLayer)
        
        val session1 = TestWebSocketSession("session-1")
        val session2 = TestWebSocketSession("session-2")
        
        // Use the Group API
        Channels.group("global-room").add(session1)
        Channels.group("global-room").add(session2)
        
        val result = Channels.group("global-room").broadcast("global message")
        assertEquals(2, result.sentTo)
        assertEquals(1, session1.receivedTextMessages.size)
        assertEquals(1, session2.receivedTextMessages.size)
    }
    
    @Test
    fun `test discardAll removes session from all groups`() = runBlocking {
        val session = TestWebSocketSession("session-x")
        val otherSession = TestWebSocketSession("other")
        
        // Add session to multiple groups
        channelLayer.groupAdd("room-1", session)
        channelLayer.groupAdd("room-1", otherSession)
        channelLayer.groupAdd("room-2", session)
        channelLayer.groupAdd("room-3", session)
        
        // Discard from all
        channelLayer.discardAll(session)
        
        // Verify session is removed from all groups
        assertEquals(1, channelLayer.groupSize("room-1"))
        assertEquals(0, channelLayer.groupSize("room-2"))
        assertEquals(0, channelLayer.groupSize("room-3"))
        
        // Only otherSession should be in room-1
        val result = channelLayer.groupSend("room-1", "test")
        assertEquals(1, result.sentTo)
        assertEquals(0, session.receivedTextMessages.size)
        assertEquals(1, otherSession.receivedTextMessages.size)
    }
    
    @Test
    fun `test ChannelMessage data class`() = runBlocking {
        val message = ChannelMessage(
            type = "notification",
            payload = null,
            sender = "system",
            target = "user-123",
            timestamp = System.currentTimeMillis()
        )
        
        // Verify message properties
        assertEquals("notification", message.type)
        assertEquals("system", message.sender)
        assertEquals("user-123", message.target)
        assertTrue(message.timestamp > 0)
    }
    
    @Test
    fun `test concurrent group operations`() = runBlocking {
        val sessions = (1..100).map { TestWebSocketSession("session-$it") }
        
        // Perform many concurrent add operations
        val jobs = sessions.map { session ->
            async {
                channelLayer.groupAdd("stress-room", session)
            }
        }
        jobs.awaitAll()
        
        // All sessions should be added
        assertEquals(100, channelLayer.groupSize("stress-room"))
        val result = channelLayer.groupSend("stress-room", "stress-test")
        assertEquals(100, result.sentTo)
    }
    
    @Test
    fun `test group with special characters in name`() = runBlocking {
        val session1 = TestWebSocketSession("session-1")
        val session2 = TestWebSocketSession("session-2")
        
        // Group names with special characters
        channelLayer.groupAdd("chat:user:123", session1)
        channelLayer.groupAdd("chat:user:456", session2)
        
        val result = channelLayer.groupSend("chat:user:123", "private message")
        assertEquals(1, result.sentTo)
        assertTrue(session1.receivedTextMessages.contains("private message"))
        assertEquals(0, session2.receivedTextMessages.size)
    }
    
    @Test
    fun `test SendResult properties`() = runBlocking {
        val session = TestWebSocketSession("s1")
        
        // Test empty group result
        val emptyResult = channelLayer.groupSend("no-group", "test")
        assertEquals(0, emptyResult.sentTo)
        assertEquals(0, emptyResult.failed)
        
        // Test populated group result
        channelLayer.groupAdd("test-group", session)
        val populatedResult = channelLayer.groupSend("test-group", "test")
        assertEquals(1, populatedResult.sentTo)
        assertEquals(0, populatedResult.failed)
    }
    
    @Test
    fun `test high throughput message delivery`() = runBlocking {
        val session = TestWebSocketSession("session-1")
        
        // Add session
        channelLayer.groupAdd("throughput-test", session)
        
        // Send many messages
        val results = (1..1000).map { i ->
            async {
                channelLayer.groupSend("throughput-test", "msg-$i")
            }
        }.awaitAll()
        
        // All messages should succeed
        assertTrue(results.all { it.sentTo == 1 })
        assertEquals(1000, session.receivedTextMessages.size)
    }
    
    @Test
    fun `test binary message delivery`() = runBlocking {
        val session = TestWebSocketSession("session-1")
        channelLayer.groupAdd("binary-room", session)
        
        // Send binary message
        val binaryData = byteArrayOf(1, 2, 3, 4, 5)
        val result = channelLayer.groupSendBinary("binary-room", binaryData)
        
        assertEquals(1, result.sentTo)
        assertEquals(1, session.receivedBinaryMessages.size)
        assertTrue(session.receivedBinaryMessages[0].contentEquals(binaryData))
    }
    
    @Test
    fun `test getSessionGroups returns all groups for session`() = runBlocking {
        val session = TestWebSocketSession("session-1")
        
        // Add to multiple groups
        channelLayer.groupAdd("group-a", session)
        channelLayer.groupAdd("group-b", session)
        channelLayer.groupAdd("group-c", session)
        
        val groups = channelLayer.getSessionGroups(session)
        assertEquals(3, groups.size)
        assertTrue(groups.contains("group-a"))
        assertTrue(groups.contains("group-b"))
        assertTrue(groups.contains("group-c"))
    }
    
    @Test
    fun `test isInGroup returns correct result`() = runBlocking {
        val session1 = TestWebSocketSession("session-1")
        val session2 = TestWebSocketSession("session-2")
        
        channelLayer.groupAdd("members-group", session1)
        
        assertTrue(channelLayer.isInGroup("members-group", session1))
        assertTrue(!channelLayer.isInGroup("members-group", session2))
    }
    
    @Test
    fun `test getAllGroups returns all active groups`() = runBlocking {
        val session = TestWebSocketSession("session-1")
        
        channelLayer.groupAdd("active-1", session)
        channelLayer.groupAdd("active-2", session)
        channelLayer.groupAdd("active-3", session)
        
        val groups = channelLayer.getAllGroups()
        assertEquals(3, groups.size)
        assertTrue(groups.containsAll(listOf("active-1", "active-2", "active-3")))
    }
    
    @Test
    fun `test closed session is counted as failed`() = runBlocking {
        val openSession = TestWebSocketSession("open-session", isOpen = true)
        val closedSession = TestWebSocketSession("closed-session", isOpen = false)
        
        channelLayer.groupAdd("mixed-room", openSession)
        channelLayer.groupAdd("mixed-room", closedSession)
        
        val result = channelLayer.groupSend("mixed-room", "test")
        assertEquals(1, result.sentTo)
        assertEquals(1, result.failed)
    }
}
