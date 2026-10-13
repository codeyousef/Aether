package codes.yousef.aether.web

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.core.jvm.HttpsListenerConfig
import codes.yousef.aether.core.pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.Socket
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.*
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
        val temp = Files.createTempDirectory("aether-header-tls-")
        fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/tls/$name")).use { it.readBytes() }
        val certificate = resource("test-cert.pem")
        Files.write(temp.resolve("cert.pem"), certificate); Files.write(temp.resolve("key.pem"), resource("test-key.pem"))
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null); setCertificateEntry("test", CertificateFactory.getInstance("X.509").generateCertificate(certificate.inputStream()))
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        try {
            for (tls in listOf(false, true)) for (routerServer in listOf(false, true)) for (streaming in listOf(false, true)) {
                val handler: suspend (Exchange) -> Unit = { exchange ->
                    val counts = names.joinToString(",") { exchange.request.headers.getAll(it).size.toString() }
                    val body = exchange.request.bodyBytes().decodeToString()
                    exchange.response.write("$counts:$body")
                }
                val core = if (routerServer) null else VertxServer(VertxServerConfig(host = "127.0.0.1", port = 0,
                    streamRequestBodies = streaming, maxChunkSize = 16_384, tls = if (tls) HttpsListenerConfig(temp.resolve("cert.pem").toString(), temp.resolve("key.pem").toString(), http2Enabled = true) else null), Pipeline(), handler)
                val web = if (!routerServer) null else AetherServer.create(AetherServerConfig(host = "127.0.0.1", port = 0,
                    streamRequestBodies = streaming, maxChunkSize = 16_384, ssl = if (tls) SslConfig(keyPath = temp.resolve("key.pem").toString(), certPath = temp.resolve("cert.pem").toString(), http2Enabled = true) else null), router { get("/probe", handler); post("/probe", handler) })
                core?.start(); web?.start()
                try {
                    val port = core?.actualPort ?: web!!.actualPort
                    for (http2 in if (tls) listOf(false, true) else listOf(false)) for (different in listOf(false, true)) for (caseVariant in listOf(false, true)) {
                        val values = names.flatMap { name ->
                            val value = if (name == "Cookie") "probe=safe-test-value" else "safe-test-value"
                            listOf(name to value, (if (caseVariant) name.lowercase() else name) to if (different) value.replace("safe", "second") else value)
                        }
                        val cookieHeaders = listOf("Cookie" to "probe=first", "cookie" to if (different) "probe=second" else "probe=first")
                        val rejected = if (http2) http2(port, cookieHeaders, ssl) else http1(port, cookieHeaders, 400, if (tls) ssl else null)
                        assertTrue(rejected.contains("BAD_REQUEST"))
                        val received = if (http2) http2(port, values, ssl) else http1(port, values, ssl = if (tls) ssl else null)
                        assertTrue(received.contains("2,2,2,2,2,2:payload"),
                            "Multiplicity lost: router=$routerServer streaming=$streaming HTTP2=$http2 different=$different caseVariant=$caseVariant: $received")
                    }

                    // The exact consumer regression: duplicate identical fields on the first Java
                    // HTTP/2 negotiation request. Plaintext safely falls back; TLS negotiates HTTP/2.
                    val client = HttpClient.newBuilder().sslContext(ssl).build()
                    val request = HttpRequest.newBuilder(URI("${if (tls) "https" else "http"}://localhost:$port/probe"))
                        .POST(HttpRequest.BodyPublishers.ofString("payload"))
                    names.forEach { request.header(it, "fixture").header(it, "fixture") }
                    val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(if (tls) HttpClient.Version.HTTP_2 else HttpClient.Version.HTTP_1_1, response.version())
                    assertEquals("2,2,2,2,2,2:payload", response.body())

                } finally { core?.close(); web?.close() }
            }
        } finally { temp.toFile().deleteRecursively() }
    }

    private fun socket(port: Int, ssl: SSLContext?, http2: Boolean = false): Socket {
        val socket = ssl?.socketFactory?.createSocket("localhost", port) ?: Socket("127.0.0.1", port)
        socket.soTimeout = 5000
        if (socket is SSLSocket) {
            socket.sslParameters = socket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                if (http2) applicationProtocols = arrayOf("h2")
            }
            socket.startHandshake()
            if (http2) assertEquals("h2", socket.applicationProtocol)
        }
        return socket
    }

    // Send real duplicate wire occurrences; client MultiMap encoders and Java Cookie handling can
    // themselves collapse/combine values before transmission, hiding the server regression.
    private fun http1(port: Int, headers: List<Pair<String, String>>, expectedStatus: Int = 200, ssl: SSLContext? = null): String = socket(port, ssl).use { socket ->
        socket.soTimeout = 5000
        val wire = buildString {
            append("POST /probe HTTP/1.1\r\nHost: localhost\r\nContent-Length: 7\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\npayload")
        }
        socket.getOutputStream().write(wire.toByteArray()); socket.getOutputStream().flush()
        socket.getInputStream().readBytes().decodeToString().also { assertTrue(it.startsWith("HTTP/1.1 $expectedStatus")) }
    }

    private fun http2(port: Int, headers: List<Pair<String, String>>, ssl: SSLContext): String = socket(port, ssl, http2 = true).use { socket ->
        socket.soTimeout = 5000
        val output = socket.getOutputStream()
        fun frame(type: Int, flags: Int, stream: Int, payload: ByteArray = byteArrayOf()) {
            output.write(byteArrayOf((payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte(),
                type.toByte(), flags.toByte(), (stream ushr 24).toByte(), (stream ushr 16).toByte(), (stream ushr 8).toByte(), stream.toByte()))
            output.write(payload); output.flush()
        }
        output.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray())
        frame(4, 0, 0) // SETTINGS
        val fields = DefaultHttp2Headers().method("POST").scheme("https").authority("localhost").path("/probe")
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
