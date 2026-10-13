package codes.yousef.aether.core.jvm

import codes.yousef.aether.core.RequestBodyStreamLimits
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.*

class HttpsListenerTest {
    private val cert = Path.of(javaClass.getResource("/tls/test-cert.pem")!!.toURI())
    private val key = Path.of(javaClass.getResource("/tls/test-key.pem")!!.toURI())
    private val tls = HttpsListenerConfig(cert.toString(), key.toString(), HttpsMinimumProtocol.TLS_1_3)
    private val trust = SSLContext.getInstance("TLS").apply {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            Files.newInputStream(cert).use {
                setCertificateEntry("test-only", CertificateFactory.getInstance("X.509").generateCertificate(it))
            }
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        init(null, managers.trustManagers, null)
    }
    private val client = HttpClient.newBuilder().sslContext(trust).connectTimeout(Duration.ofSeconds(2)).build()

    @Test @Timeout(20)
    fun `trusted HTTPS enforces body bounds and streams incrementally`() = runBlocking {
        for (http2 in listOf(false, true)) for (streaming in listOf(false, true)) {
            val server = VertxServer(VertxServerConfig(host = "127.0.0.1", port = 0, tls = HttpsListenerConfig(cert.toString(), key.toString(), http2Enabled = http2),
                streamRequestBodies = streaming, maxRequestBodySize = 64 * 1024, maxChunkSize = if (http2) 16_384 else 1024,
                requestBodyTimeoutMillis = 500)) { exchange ->
                if (streaming) {
                    val stream = exchange.request.openBodyStream(RequestBodyStreamLimits(maximumChunkBytes = if (http2) 16_384 else 1024))!!
                    var total = 0
                    stream.consume { total += it.size }
                    assertTrue(stream.highWaterBytes <= if (http2) 32_768 else 2048)
                    exchange.response.write(total.toString())
                } else exchange.response.write(exchange.request.bodyBytes().size.toString())
            }
            server.start()
            try {
                val body = ByteArray(32 * 1024) { 3 }
                val response = send(server.actualPort, body)
                assertEquals(if (http2) HttpClient.Version.HTTP_2 else HttpClient.Version.HTTP_1_1, response.version())
                assertEquals(200, response.statusCode())
                assertEquals(body.size.toString(), response.body())
                assertEquals(413, send(server.actualPort, ByteArray(65 * 1024)).statusCode())
                trust.socketFactory.createSocket("localhost", server.actualPort).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20\r\nConnection: close\r\n\r\nx".toByteArray())
                    socket.getOutputStream().flush()
                    assertTrue(socket.getInputStream().bufferedReader().readLine().contains("408"))
                }
                assertEquals(200, send(server.actualPort, byteArrayOf(1)).statusCode())
            } finally { server.close(); server.close() }
        }
    }

    @Test
    fun `HTTP2 refuses an undersized transport chunk bound`() = runBlocking {
        val server = VertxServer(VertxServerConfig(port = 0,
            tls = HttpsListenerConfig(cert.toString(), key.toString(), http2Enabled = true), maxChunkSize = 1024)) {
            fail("Unsafe HTTP/2 listener must not dispatch")
        }
        try { assertFailsWith<HttpsListenerException> { server.start() } } finally { server.close() }
    }

    @Test @Timeout(20)
    fun `untrusted hostname protocol and plaintext cannot reach handler`() = runBlocking {
        val calls = AtomicInteger()
        val server = VertxServer(VertxServerConfig(host = "127.0.0.1", port = 0, tls = tls)) {
            calls.incrementAndGet(); it.response.write("ok")
        }
        server.start()
        try {
            val request = HttpRequest.newBuilder(URI("https://localhost:${server.actualPort}/")).timeout(Duration.ofSeconds(2)).build()
            assertFails { HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()) }
            assertFails { client.send(HttpRequest.newBuilder(URI("https://127.0.0.1:${server.actualPort}/")).build(), HttpResponse.BodyHandlers.ofString()) }
            trust.socketFactory.createSocket("localhost", server.actualPort).use { raw ->
                val socket = raw as javax.net.ssl.SSLSocket
                socket.soTimeout = 2000
                socket.enabledProtocols = arrayOf("TLSv1.2")
                assertFails { socket.startHandshake() }
            }
            Socket("127.0.0.1", server.actualPort).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                assertEquals(-1, socket.getInputStream().read())
            }
            assertEquals(0, calls.get())
            assertEquals(200, send(server.actualPort, ByteArray(0)).statusCode())
        } finally { server.close() }
    }

    @Test @Timeout(20)
    fun `TLS disconnect and active shutdown cancel streaming children within their bound`() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
        val server = VertxServer(VertxServerConfig(host = "127.0.0.1", port = 0, tls = tls,
            streamRequestBodies = true, requestBodyTimeoutMillis = 10_000, shutdownGraceMillis = 100)) { exchange ->
            entered.complete(Unit)
            try { exchange.request.openBodyStream()!!.consume {} } finally { finished.complete(Unit) }
        }
        server.start()
        val socket = trust.socketFactory.createSocket("localhost", server.actualPort)
        try {
            socket.getOutputStream().write("POST /active HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20\r\n\r\nx".toByteArray())
            socket.getOutputStream().flush()
            kotlinx.coroutines.withTimeout(2000) { entered.await() }
            val elapsed = kotlin.system.measureTimeMillis { server.close() }
            assertTrue(elapsed < 2000)
            kotlinx.coroutines.withTimeout(2000) { finished.await() }
        } finally { socket.close(); server.close() }
    }

    @Test @Timeout(20)
    fun `missing malformed mismatched material fails safely`() = runBlocking {
        val bad = Files.createTempFile("PRIVATE_PATH_MARKER", ".pem")
        val otherKey = Files.createTempFile("other-test-key", ".pem")
        val generated = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        Files.writeString(otherKey, "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder().encodeToString(generated.private.encoded) + "\n-----END PRIVATE KEY-----")
        Files.writeString(bad, "PRIVATE_MATERIAL_MARKER")
        try {
            for (invalid in listOf(HttpsListenerConfig(bad.toString(), key.toString()),
                HttpsListenerConfig(cert.toString(), bad.toString()),
                HttpsListenerConfig(cert.toString(), otherKey.toString()),
                HttpsListenerConfig(cert.toString(), bad.toString() + "missing"))) {
                val server = VertxServer(VertxServerConfig(port = 0, tls = invalid)) { error("unexpected handler") }
                val failure = assertFailsWith<HttpsListenerException> { server.start() }
                assertNull(failure.cause)
                assertFalse(failure.toString().contains("PRIVATE"))
                assertFalse(invalid.toString().contains("PRIVATE"))
                assertFails { server.actualPort }
                server.close()
            }
        } finally { Files.deleteIfExists(bad); Files.deleteIfExists(otherKey) }
    }

    private fun send(port: Int, body: ByteArray): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI("https://localhost:$port/health")).timeout(Duration.ofSeconds(3))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString())
}
