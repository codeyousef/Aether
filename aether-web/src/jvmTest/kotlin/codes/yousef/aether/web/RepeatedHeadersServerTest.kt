package codes.yousef.aether.web

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.core.pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.Socket
import java.io.DataInputStream
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder
import io.netty.handler.codec.http2.Http2HeadersEncoder
import kotlin.test.*

class RepeatedHeadersServerTest {
    @Test @Timeout(40)
    fun repeatedIdenticalAndCaseVariantHeadersSurviveBothListenersAndBodyModes() = runBlocking {
        val names = listOf("Authorization", "Origin", "X-CSRF-Token", "Idempotency-Key", "X-Device-Grant", "X-Multivalue")
        for (routerServer in listOf(false, true)) for (streaming in listOf(false, true)) {
            val handler: suspend (Exchange) -> Unit = { exchange ->
                val counts = names.joinToString(",") { exchange.request.headers.getAll(it).size.toString() }
                val body = exchange.request.bodyBytes().decodeToString()
                exchange.response.write("$counts:$body")
            }
            val core = if (routerServer) null else VertxServer(VertxServerConfig(host = "127.0.0.1", port = 0,
                streamRequestBodies = streaming), Pipeline(), handler)
            val web = if (!routerServer) null else AetherServer.create(AetherServerConfig(host = "127.0.0.1", port = 0,
                streamRequestBodies = streaming), router { get("/probe", handler); post("/probe", handler) })
            core?.start(); web?.start()
            try {
                val port = core?.actualPort ?: web!!.actualPort
                for (http2 in listOf(false, true)) for (different in listOf(false, true)) {
                    val values = names.flatMap { name ->
                        val value = if (name == "Cookie") "probe=safe-test-value" else "safe-test-value"
                        listOf(name to value, name.lowercase() to if (different) value.replace("safe", "second") else value)
                    }
                    val cookieHeaders = listOf("Cookie" to "probe=first", "cookie" to if (different) "probe=second" else "probe=first")
                    val rejected = if (http2) http2(port, cookieHeaders) else http1(port, cookieHeaders, 400)
                    assertTrue(rejected.contains("BAD_REQUEST"))
                    val received = if (http2) http2(port, values) else http1(port, values)
                    assertTrue(received.contains("2,2,2,2,2,2:payload"),
                        "Multiplicity lost: router=$routerServer streaming=$streaming HTTP2=$http2 different=$different: $received")
                }

            } finally { core?.close(); web?.close() }
        }
    }

    // Send real duplicate wire occurrences; client MultiMap encoders and Java Cookie handling can
    // themselves collapse/combine values before transmission, hiding the server regression.
    private fun http1(port: Int, headers: List<Pair<String, String>>, expectedStatus: Int = 200): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        val wire = buildString {
            append("POST /probe HTTP/1.1\r\nHost: localhost\r\nContent-Length: 7\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\npayload")
        }
        socket.getOutputStream().write(wire.toByteArray()); socket.getOutputStream().flush()
        socket.getInputStream().readBytes().decodeToString().also { assertTrue(it.startsWith("HTTP/1.1 $expectedStatus")) }
    }

    private fun http2(port: Int, headers: List<Pair<String, String>>): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        val output = socket.getOutputStream()
        fun frame(type: Int, flags: Int, stream: Int, payload: ByteArray = byteArrayOf()) {
            output.write(byteArrayOf((payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte(),
                type.toByte(), flags.toByte(), (stream ushr 24).toByte(), (stream ushr 16).toByte(), (stream ushr 8).toByte(), stream.toByte()))
            output.write(payload); output.flush()
        }
        output.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray())
        frame(4, 0, 0) // SETTINGS
        val fields = DefaultHttp2Headers().method("POST").scheme("http").authority("localhost").path("/probe")
        headers.forEach { (name, value) -> fields.add(name.lowercase(), value) }
        val encoded = Unpooled.buffer()
        val bytes = try {
            DefaultHttp2HeadersEncoder(Http2HeadersEncoder.NEVER_SENSITIVE).encodeHeaders(1, fields, encoded)
            ByteArray(encoded.readableBytes()).also { encoded.readBytes(it) }
        } finally { encoded.release() }
        frame(1, 4, 1, bytes) // HEADERS + END_HEADERS
        frame(0, 1, 1, "payload".toByteArray()) // DATA + END_STREAM
        val input = DataInputStream(socket.getInputStream())
        val body = java.io.ByteArrayOutputStream()
        while (true) {
            val length = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            val type = input.readUnsignedByte(); val flags = input.readUnsignedByte(); val stream = input.readInt() and 0x7fffffff
            val payload = ByteArray(length); input.readFully(payload)
            if (type == 4 && flags and 1 == 0) frame(4, 1, 0)
            if (type == 0 && stream == 1) body.write(payload)
            check(type != 3 && type != 7) { "HTTP/2 stream rejected" }
            if (stream == 1 && flags and 1 != 0) break
        }
        body.toByteArray().decodeToString()
    }
}
