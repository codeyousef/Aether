package codes.yousef.aether.web

import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedRequestBodyServerTest {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    @Test
    @Timeout(30)
    fun `both servers enforce decoded body boundaries before dispatch and remain healthy`() = runBlocking {
        for (kind in ServerKind.entries) {
            val harness = startServer(kind, maximumBytes = 8, timeoutMillis = 500)
            try {
                assertEquals(200, post(harness.port, ByteArray(7) { 1 }).statusCode(), "$kind cap-1")
                assertEquals(200, post(harness.port, ByteArray(8) { 2 }).statusCode(), "$kind cap")
                assertEquals(413, post(harness.port, ByteArray(9) { 3 }).statusCode(), "$kind cap+1")
                assertEquals(2, harness.handlerCalls.get(), "$kind must reject before dispatch")

                val chunkedStatus = rawStatus(
                    harness.port,
                    "POST /body HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
                        "3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n"
                )
                assertEquals(200, chunkedStatus, "$kind chunked body")

                val compressed = gzip(ByteArray(64) { 'a'.code.toByte() })
                val amplified = post(harness.port, compressed, mapOf("Content-Encoding" to "gzip"))
                assertEquals(413, amplified.statusCode(), "$kind decompression amplification")

                val contradictory = rawResponse(
                    harness.port,
                    "POST /body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n" +
                        "Content-Length: 4\r\nX-Request-Id: invalid-frame-123\r\nConnection: close\r\n\r\nabc"
                )
                assertEquals(400, contradictory.status, "$kind contradictory framing")
                assertEquals("invalid-frame-123", contradictory.headers["x-request-id"])
                assertEquals("application/json; charset=utf-8", contradictory.headers["content-type"])
                assertEquals("no-store", contradictory.headers["cache-control"])
                assertTrue(contradictory.body.contains("\"code\":\"BAD_REQUEST\""))
                assertFalse(contradictory.body.contains("Exception"))
                assertFalse(contradictory.body.contains("abc"))

                assertEquals(200, post(harness.port, byteArrayOf(9)).statusCode(), "$kind later valid request")
                assertEquals(4, harness.handlerCalls.get(), "$kind rejected bodies must not mutate handler state")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    @Timeout(20)
    fun `both servers reject timed out and disconnected partial bodies without dispatch`() = runBlocking {
        for (kind in ServerKind.entries) {
            val harness = startServer(kind, maximumBytes = 64, timeoutMillis = 150)
            try {
                Socket("127.0.0.1", harness.port).use { socket ->
                    socket.soTimeout = 2_000
                    socket.getOutputStream().write(
                        (
                            "POST /body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20\r\n" +
                                "Connection: keep-alive\r\n\r\n{\"partial\":"
                            ).toByteArray()
                    )
                    socket.getOutputStream().flush()
                    assertEquals(408, readStatus(socket), "$kind timeout")
                }
                assertEquals(0, harness.handlerCalls.get(), "$kind timeout dispatch")

                Socket("127.0.0.1", harness.port).use { socket ->
                    socket.getOutputStream().write(
                        "POST /body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20\r\n\r\nshort"
                            .toByteArray()
                    )
                }
                delay(100)
                assertEquals(0, harness.handlerCalls.get(), "$kind disconnected body dispatch")
                assertEquals(200, post(harness.port, byteArrayOf(1)).statusCode(), "$kind request after rejection")
                assertEquals(1, harness.handlerCalls.get(), "$kind healthy dispatch")
            } finally {
                harness.close()
            }
        }
    }
    @Test
    @Timeout(20)
    fun `closing either server cancels an incomplete body without dispatch`() = runBlocking {
        for (kind in ServerKind.entries) {
            val harness = startServer(kind, maximumBytes = 64, timeoutMillis = 5_000)
            val socket = Socket("127.0.0.1", harness.port)
            try {
                socket.getOutputStream().write(
                    "POST /body HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20\r\n\r\npartial"
                        .toByteArray()
                )
                socket.getOutputStream().flush()
                val close = async { harness.close() }
                socket.close()
                withTimeout(3_000) { close.await() }
                assertEquals(0, harness.handlerCalls.get(), "$kind close dispatch")

                val replacement = startServer(kind, maximumBytes = 64, timeoutMillis = 500)
                try {
                    assertEquals(200, post(replacement.port, byteArrayOf(1)).statusCode(), "$kind after close")
                } finally {
                    replacement.close()
                }
            } finally {
                socket.close()
            }
        }
    }


    @Test
    fun `both server configurations reject invalid body limits`() {
        assertConfigurationFailure { VertxServerConfig(maxRequestBodySize = 0) }
        assertConfigurationFailure { VertxServerConfig(requestBodyTimeoutMillis = 0) }
        assertConfigurationFailure { AetherServerConfig(maxRequestBodySize = 0) }
        assertConfigurationFailure { AetherServerConfig(requestBodyTimeoutMillis = 0) }
    }

    private suspend fun startServer(kind: ServerKind, maximumBytes: Int, timeoutMillis: Long): Harness {
        val handlerCalls = AtomicInteger()
        return when (kind) {
            ServerKind.CORE -> {
                val server = VertxServer.create(
                    config = VertxServerConfig(
                        host = "127.0.0.1",
                        port = 0,
                        maxRequestBodySize = maximumBytes,
                        requestBodyTimeoutMillis = timeoutMillis
                    )
                ) { exchange ->
                    handlerCalls.incrementAndGet()
                    exchange.respond(200, "ok")
                }
                server.start()
                Harness(server.actualPort, handlerCalls) { server.close() }
            }

            ServerKind.ROUTER -> {
                val router = router {
                    post("/body") { exchange ->
                        handlerCalls.incrementAndGet()
                        exchange.respond(200, "ok")
                    }
                }
                val server = AetherServer.create(
                    config = AetherServerConfig(
                        host = "127.0.0.1",
                        port = 0,
                        maxRequestBodySize = maximumBytes,
                        requestBodyTimeoutMillis = timeoutMillis
                    ),
                    router = router
                )
                server.start()
                Harness(server.actualPort, handlerCalls) { server.close() }
            }
        }
    }

    private fun post(port: Int, body: ByteArray, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port/body"))
            .timeout(Duration.ofSeconds(3))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        headers.forEach(builder::header)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun rawStatus(port: Int, request: String): Int = rawResponse(port, request).status

    private fun rawResponse(port: Int, request: String): RawResponse = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 3_000
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val statusLine = reader.readLine()
        assertTrue(statusLine.startsWith("HTTP/1.1 "), "Unexpected response: $statusLine")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = reader.read(body, read, contentLength - read)
            if (count < 0) break
            read += count
        }
        RawResponse(
            status = statusLine.substringAfter(' ').substringBefore(' ').toInt(),
            headers = headers,
            body = body.concatToString(0, read)
        )
    }

    private fun readStatus(socket: Socket): Int {
        val statusLine = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
        assertTrue(statusLine.startsWith("HTTP/1.1 "), "Unexpected response: $statusLine")
        return statusLine.substringAfter(' ').substringBefore(' ').toInt()
    }

    private fun gzip(bytes: ByteArray): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
        output.toByteArray()
    }

    private fun assertConfigurationFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid server configuration to fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private enum class ServerKind { CORE, ROUTER }

    private data class RawResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: String
    )

    private data class Harness(
        val port: Int,
        val handlerCalls: AtomicInteger,
        val close: suspend () -> Unit
    )
}
