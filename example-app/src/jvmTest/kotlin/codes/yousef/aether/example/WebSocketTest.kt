package codes.yousef.aether.example

import codes.yousef.aether.core.AetherDispatcher
import codes.yousef.aether.core.websocket.*
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.WebSocket

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for WebSocket Support functionality.
 * 
 * Note: These are unit tests for the WebSocket handler/session APIs,
 * rather than full E2E tests which would require additional server setup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketTest {

    @Test
    fun testWebSocketHandlerBuilder() {
        var connectCalled = false
        var textReceived: String? = null
        
        val handler = webSocketHandler {
            onConnect { session ->
                connectCalled = true
            }
            onText { session, text ->
                textReceived = text
            }
            onClose { session, code, reason ->
                // Handle close
            }
            onError { session, error ->
                // Handle error
            }
        }
        
        // Verify handler is created
        assertTrue(handler is LambdaWebSocketHandler)
    }

    @Test
    fun testWebSocketConfig() {
        val config = WebSocketConfig(
            maxFrameSize = 128000,
            maxMessageSize = 2 * 1024 * 1024,
            pingIntervalMs = 60000,
            pongTimeoutMs = 20000,
            allowedOrigins = setOf("http://localhost:3000"),
            subprotocols = listOf("graphql-ws")
        )
        
        assertEquals(128000, config.maxFrameSize)
        assertEquals(2 * 1024 * 1024, config.maxMessageSize)
        assertEquals(60000, config.pingIntervalMs)
        assertEquals(20000, config.pongTimeoutMs)
        assertTrue("http://localhost:3000" in config.allowedOrigins)
        assertTrue("graphql-ws" in config.subprotocols)
    }

    @Test
    fun testWebSocketMessageTypes() {
        // Test Text message
        val textMsg = WebSocketMessage.Text("Hello")
        assertEquals("Hello", textMsg.content)
        
        // Test Binary message
        val binaryData = byteArrayOf(1, 2, 3, 4, 5)
        val binaryMsg = WebSocketMessage.Binary(binaryData)
        assertTrue(binaryData.contentEquals(binaryMsg.data))
        
        // Test Close message
        val closeMsg = WebSocketMessage.Close(1000, "Normal closure")
        assertEquals(1000, closeMsg.code)
        assertEquals("Normal closure", closeMsg.reason)
        
        // Test Ping message
        val pingMsg = WebSocketMessage.Ping(byteArrayOf(1, 2))
        assertTrue(pingMsg.data.contentEquals(byteArrayOf(1, 2)))
        
        // Test Pong message
        val pongMsg = WebSocketMessage.Pong(byteArrayOf(3, 4))
        assertTrue(pongMsg.data.contentEquals(byteArrayOf(3, 4)))
    }

    @Test
    fun testWebSocketCloseCode() {
        assertEquals(1000, WebSocketCloseCode.NORMAL)
        assertEquals(1001, WebSocketCloseCode.GOING_AWAY)
        assertEquals(1002, WebSocketCloseCode.PROTOCOL_ERROR)
        assertEquals(1003, WebSocketCloseCode.UNSUPPORTED_DATA)
        assertEquals(1008, WebSocketCloseCode.POLICY_VIOLATION)
        assertEquals(1009, WebSocketCloseCode.MESSAGE_TOO_BIG)
        assertEquals(1011, WebSocketCloseCode.INTERNAL_ERROR)
    }

    @Test
    fun testDefaultWebSocketConfig() {
        val config = WebSocketConfig()
        
        assertEquals(65536, config.maxFrameSize)
        assertEquals(1024 * 1024, config.maxMessageSize)
        assertEquals(30000, config.pingIntervalMs)
        assertEquals(10000, config.pongTimeoutMs)
        assertTrue(config.allowedOrigins.isEmpty())
        assertTrue(config.subprotocols.isEmpty())
    }

    @Test
    fun testBinaryMessageEquality() {
        val data1 = byteArrayOf(1, 2, 3)
        val data2 = byteArrayOf(1, 2, 3)
        val data3 = byteArrayOf(4, 5, 6)
        
        val msg1 = WebSocketMessage.Binary(data1)
        val msg2 = WebSocketMessage.Binary(data2)
        val msg3 = WebSocketMessage.Binary(data3)
        
        // Same content should be equal
        assertEquals(msg1, msg2)
        assertEquals(msg1.hashCode(), msg2.hashCode())
        
        // Different content should not be equal
        assertTrue(msg1 != msg3)
    }
}
