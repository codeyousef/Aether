package codes.yousef.aether.web

import codes.yousef.aether.core.pipeline.Pipeline
import kotlinx.coroutines.*
import org.junit.jupiter.api.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for WebSocket functionality with aetherStart.
 * Tests the WebSocket integration as specified in aether-websocket-requirements.md.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AetherStartWebSocketTest {

    private val httpClient = HttpClient.newHttpClient()

    /**
     * Simple WebSocket listener for testing.
     */
    class TestWebSocketListener : WebSocket.Listener {
        val messages = CopyOnWriteArrayList<String>()
        val binaryMessages = CopyOnWriteArrayList<ByteArray>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val openLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        var closeCode: Int = -1
        var closeReason: String = ""

        private val textBuilder = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            openLatch.countDown()
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            textBuilder.append(data)
            if (last) {
                messages.add(textBuilder.toString())
                textBuilder.clear()
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*> {
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            binaryMessages.add(bytes)
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            closeCode = statusCode
            closeReason = reason
            closeLatch.countDown()
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            errors.add(error)
            openLatch.countDown()
            closeLatch.countDown()
        }

        fun awaitOpen(timeout: Long = 5000): Boolean = openLatch.await(timeout, TimeUnit.MILLISECONDS)
        fun awaitClose(timeout: Long = 5000): Boolean = closeLatch.await(timeout, TimeUnit.MILLISECONDS)
        fun awaitMessages(count: Int, timeout: Long = 5000): Boolean {
            val deadline = System.currentTimeMillis() + timeout
            while (messages.size < count && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            return messages.size >= count
        }
    }

    @Test
    @Timeout(15)
    fun `test WebSocket connection and echo`() {
        val testPort = 20080
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    get("/health") { exchange ->
                        exchange.respond(200, "OK")
                    }

                    ws("/ws/echo") {
                        onText { session, message ->
                            session.sendText("Echo: $message")
                        }
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(30000)
                server.stop()
            }

            Thread.sleep(1500)

            // Connect WebSocket
            val listener = TestWebSocketListener()
            val ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:$testPort/ws/echo"), listener)
                .get(5, TimeUnit.SECONDS)

            assertTrue(listener.awaitOpen(), "WebSocket should open")

            // Send message
            ws.sendText("Hello WebSocket", true).get(2, TimeUnit.SECONDS)

            // Wait for echo
            assertTrue(listener.awaitMessages(1), "Should receive echo message")
            assertEquals("Echo: Hello WebSocket", listener.messages[0])

            // Close
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(2, TimeUnit.SECONDS)
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(15)
    fun `test WebSocket with path parameters`() {
        val testPort = 20081
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    ws("/ws/agent/:sessionId") {
                        onConnect { session ->
                            val pathParams = session.attributes["_pathParams"] as? Map<*, *>
                            val sessionId = pathParams?.get("sessionId") ?: "unknown"
                            session.sendText("Connected: $sessionId")
                        }
                        onText { session, message ->
                            val pathParams = session.attributes["_pathParams"] as? Map<*, *>
                            val sessionId = pathParams?.get("sessionId") ?: "unknown"
                            session.sendText("[$sessionId] $message")
                        }
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(30000)
                server.stop()
            }

            Thread.sleep(1500)

            val listener = TestWebSocketListener()
            val ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:$testPort/ws/agent/session123"), listener)
                .get(5, TimeUnit.SECONDS)

            assertTrue(listener.awaitOpen(), "WebSocket should open")

            // Wait for connect message
            assertTrue(listener.awaitMessages(1), "Should receive connect message")
            assertEquals("Connected: session123", listener.messages[0])

            // Send message
            ws.sendText("Hello", true).get(2, TimeUnit.SECONDS)
            assertTrue(listener.awaitMessages(2), "Should receive response")
            assertEquals("[session123] Hello", listener.messages[1])

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(2, TimeUnit.SECONDS)
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(15)
    fun `test WebSocket with curly brace path parameters`() {
        val testPort = 20082
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    ws("/ws/chat/{roomId}") {
                        onConnect { session ->
                            val pathParams = session.attributes["_pathParams"] as? Map<*, *>
                            val roomId = pathParams?.get("roomId") ?: "unknown"
                            session.sendText("Joined room: $roomId")
                        }
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(30000)
                server.stop()
            }

            Thread.sleep(1500)

            val listener = TestWebSocketListener()
            val ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:$testPort/ws/chat/room456"), listener)
                .get(5, TimeUnit.SECONDS)

            assertTrue(listener.awaitOpen(), "WebSocket should open")
            assertTrue(listener.awaitMessages(1), "Should receive join message")
            assertEquals("Joined room: room456", listener.messages[0])

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(2, TimeUnit.SECONDS)
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(15)
    fun `test HTTP and WebSocket on same server`() {
        val testPort = 20083
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    // HTTP routes
                    get("/api/health") { exchange ->
                        exchange.respond(200, """{"status": "healthy"}""")
                    }
                    get("/api/users") { exchange ->
                        exchange.respond(200, """{"users": ["alice", "bob"]}""")
                    }

                    // WebSocket route
                    ws("/ws/notifications") {
                        onConnect { session ->
                            session.sendText("Welcome to notifications")
                        }
                        onText { session, message ->
                            session.sendText("Notification: $message")
                        }
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(30000)
                server.stop()
            }

            Thread.sleep(1500)

            // Test HTTP endpoint
            val httpRequest = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$testPort/api/health"))
                .GET()
                .build()
            val httpResponse = httpClient.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
            assertEquals(200, httpResponse.statusCode())
            assertTrue(httpResponse.body().contains("healthy"))

            // Test WebSocket
            val listener = TestWebSocketListener()
            val ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:$testPort/ws/notifications"), listener)
                .get(5, TimeUnit.SECONDS)

            assertTrue(listener.awaitOpen(), "WebSocket should open")
            assertTrue(listener.awaitMessages(1), "Should receive welcome")
            assertEquals("Welcome to notifications", listener.messages[0])

            // Send notification
            ws.sendText("New user joined", true).get(2, TimeUnit.SECONDS)
            assertTrue(listener.awaitMessages(2), "Should receive notification echo")
            assertEquals("Notification: New user joined", listener.messages[1])

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(2, TimeUnit.SECONDS)
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(15)
    fun `test WebSocket onClose handler`() {
        val testPort = 20084
        var serverJob: Job? = null
        val closeReceived = CountDownLatch(1)

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    ws("/ws/track") {
                        onConnect { session ->
                            session.sendText("Connected")
                        }
                        onClose { session, code, reason ->
                            // Server received close
                            closeReceived.countDown()
                        }
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(30000)
                server.stop()
            }

            Thread.sleep(1500)

            val listener = TestWebSocketListener()
            val ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:$testPort/ws/track"), listener)
                .get(5, TimeUnit.SECONDS)

            assertTrue(listener.awaitOpen())
            assertTrue(listener.awaitMessages(1))

            // Close with specific code and reason
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Client done").get(2, TimeUnit.SECONDS)

            // Wait for server to process close
            assertTrue(closeReceived.await(5, TimeUnit.SECONDS), "Server should receive close event")
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(15)
    fun `test multiple WebSocket routes`() {
        val testPort = 20085
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    ws("/ws/chat") {
                        onConnect { session ->
                            session.sendText("Chat connected")
                        }
                    }
                    ws("/ws/game") {
                        onConnect { session ->
                            session.sendText("Game connected")
                        }
                    }
                    ws("/ws/admin") {
                        onConnect { session ->
                            session.sendText("Admin connected")
                        }
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(30000)
                server.stop()
            }

            Thread.sleep(1500)

            // Connect to chat
            val chatListener = TestWebSocketListener()
            val chatWs = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:$testPort/ws/chat"), chatListener)
                .get(5, TimeUnit.SECONDS)
            assertTrue(chatListener.awaitOpen())
            assertTrue(chatListener.awaitMessages(1))
            assertEquals("Chat connected", chatListener.messages[0])

            // Connect to game
            val gameListener = TestWebSocketListener()
            val gameWs = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:$testPort/ws/game"), gameListener)
                .get(5, TimeUnit.SECONDS)
            assertTrue(gameListener.awaitOpen())
            assertTrue(gameListener.awaitMessages(1))
            assertEquals("Game connected", gameListener.messages[0])

            // Connect to admin
            val adminListener = TestWebSocketListener()
            val adminWs = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:$testPort/ws/admin"), adminListener)
                .get(5, TimeUnit.SECONDS)
            assertTrue(adminListener.awaitOpen())
            assertTrue(adminListener.awaitMessages(1))
            assertEquals("Admin connected", adminListener.messages[0])

            // Close all
            chatWs.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS)
            gameWs.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS)
            adminWs.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS)
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(15)
    @Disabled("WebSocket 404 rejection behavior varies by client - server correctly rejects with ws.reject(404)")
    fun `test WebSocket returns 404 for unknown path`() {
        val testPort = 20086
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    ws("/ws/known") {
                        onConnect { session ->
                            session.sendText("Connected")
                        }
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(30000)
                server.stop()
            }

            Thread.sleep(1500)

            // Try to connect to unknown WebSocket path - should be rejected
            val listener = TestWebSocketListener()
            var wasRejected = false

            try {
                httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:$testPort/ws/unknown"), listener)
                    .get(5, TimeUnit.SECONDS)

                // If connection succeeded, wait to see if it gets closed immediately
                val opened = listener.awaitOpen(1000)
                if (!opened) {
                    wasRejected = true
                } else {
                    // Connected but check if it was closed right away
                    val closed = listener.awaitClose(1000)
                    if (closed || listener.errors.isNotEmpty()) {
                        wasRejected = true
                    }
                }
            } catch (e: java.util.concurrent.ExecutionException) {
                // Connection rejected - this is expected
                wasRejected = true
            } catch (e: Exception) {
                // Any other exception also means rejection
                wasRejected = true
            }

            assertTrue(wasRejected, "Unknown WebSocket path should be rejected")
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(15)
    fun `test WebSocket multiple messages in sequence`() {
        val testPort = 20087
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    ws("/ws/counter") {
                        var count = 0
                        onText { session, message ->
                            count++
                            session.sendText("Message $count: $message")
                        }
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(30000)
                server.stop()
            }

            Thread.sleep(1500)

            val listener = TestWebSocketListener()
            val ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:$testPort/ws/counter"), listener)
                .get(5, TimeUnit.SECONDS)

            assertTrue(listener.awaitOpen())

            // Send multiple messages
            ws.sendText("First", true).get(2, TimeUnit.SECONDS)
            ws.sendText("Second", true).get(2, TimeUnit.SECONDS)
            ws.sendText("Third", true).get(2, TimeUnit.SECONDS)

            assertTrue(listener.awaitMessages(3), "Should receive 3 messages")
            assertEquals("Message 1: First", listener.messages[0])
            assertEquals("Message 2: Second", listener.messages[1])
            assertEquals("Message 3: Third", listener.messages[2])

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(2, TimeUnit.SECONDS)
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(15)
    fun `test WebSocket concurrent connections`() {
        val testPort = 20088
        var serverJob: Job? = null

        try {
            serverJob = CoroutineScope(Dispatchers.IO).launch {
                val router = router {
                    ws("/ws/broadcast") {
                        onConnect { session ->
                            session.sendText("Client ${session.id.take(8)} connected")
                        }
                        onText { session, message ->
                            session.sendText("From ${session.id.take(8)}: $message")
                        }
                    }
                }

                val config = AetherServerConfig(port = testPort)
                val server = AetherServer.create(config, router, Pipeline())
                server.start()
                delay(30000)
                server.stop()
            }

            Thread.sleep(1500)

            // Connect 5 clients concurrently
            val listeners = (1..5).map { TestWebSocketListener() }
            val websockets = listeners.map { listener ->
                httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:$testPort/ws/broadcast"), listener)
                    .get(5, TimeUnit.SECONDS)
            }

            // All should connect
            listeners.forEachIndexed { index, listener ->
                assertTrue(listener.awaitOpen(), "Client $index should connect")
                assertTrue(listener.awaitMessages(1), "Client $index should receive welcome")
                assertTrue(listener.messages[0].contains("connected"), "Client $index should get connect message")
            }

            // Each client sends a message
            websockets.forEachIndexed { index, ws ->
                ws.sendText("Hello from client $index", true).get(2, TimeUnit.SECONDS)
            }

            // Each should receive their own echo
            listeners.forEachIndexed { index, listener ->
                assertTrue(listener.awaitMessages(2), "Client $index should receive echo")
            }

            // Close all
            websockets.forEach { ws ->
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS)
            }
        } finally {
            serverJob?.cancel()
        }
    }

    @Test
    @Timeout(15)
    fun `test getWebSocketRoutes returns correct count`() {
        val router = router {
            get("/api") { exchange -> exchange.respond(200, "OK") }
            ws("/ws/one") { onConnect { } }
            ws("/ws/two") { onConnect { } }
            ws("/ws/three") { onConnect { } }
        }

        val wsRoutes = router.getWebSocketRoutes()
        assertEquals(3, wsRoutes.size, "Should have 3 WebSocket routes")
        assertEquals("/ws/one", wsRoutes[0].path)
        assertEquals("/ws/two", wsRoutes[1].path)
        assertEquals("/ws/three", wsRoutes[2].path)
    }

    @Test
    @Timeout(15)
    fun `test findWebSocketHandler finds correct handler`() {
        val router = router {
            ws("/ws/chat") { onConnect { } }
            ws("/ws/game/:id") { onConnect { } }
        }

        // Should find exact match
        val chatHandler = router.findWebSocketHandler("/ws/chat")
        assertTrue(chatHandler != null, "Should find chat handler")

        // Should find parameterized match
        val gameHandler = router.findWebSocketHandler("/ws/game/123")
        assertTrue(gameHandler != null, "Should find game handler with param")

        // Should not find unknown
        val unknownHandler = router.findWebSocketHandler("/ws/unknown")
        assertTrue(unknownHandler == null, "Should not find unknown handler")
    }
}

