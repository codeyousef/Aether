package codes.yousef.aether.web

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.RequestBodyStreamException
import codes.yousef.aether.core.RequestBodyStreamFailure
import codes.yousef.aether.core.RequestBodyStreamLimits
import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.core.pipeline.installRecovery
import codes.yousef.aether.core.upload.StreamingMultipartSink
import codes.yousef.aether.core.upload.StreamingMultipartPart
import codes.yousef.aether.core.upload.UploadConfig
import codes.yousef.aether.core.upload.streamMultipart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StreamingRequestBodyServerTest {
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    @Test
    @Timeout(30)
    fun `both servers stream a large slow upload with bounded high water`() = runBlocking {
        for (kind in ServerKind.entries) {
            val highWater = AtomicLong(-1)
            val harness = startServer(kind, maximumBytes = 3 * 1024 * 1024) { exchange ->
                val stream = exchange.request.openBodyStream(
                    RequestBodyStreamLimits(
                        maximumTotalBytes = 3L * 1024 * 1024,
                        maximumChunkBytes = 1024,
                        deadline = 10.seconds
                    )
                ) ?: error("$kind streaming capability missing")
                var total = 0L
                stream.consume { chunk ->
                    delay(1)
                    total += chunk.size
                }
                highWater.set(stream.highWaterBytes)
                assertFailsWith<RequestBodyStreamException> {
                    exchange.request.openBodyStream()
                }.also { assertEquals(RequestBodyStreamFailure.ALREADY_CONSUMED, it.failure) }
                exchange.response.write(total.toString())
            }
            try {
                val body = ByteArray(2 * 1024 * 1024) { (it and 0xff).toByte() }
                val response = post(harness.port, "/stream", body)

                assertEquals(200, response.statusCode(), kind.name)
                assertEquals(body.size.toString(), response.body(), kind.name)
                assertTrue(highWater.get() in 1..(2L * 1024), "$kind high-water=${highWater.get()}")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    @Timeout(20)
    fun `deadline and disconnect cancel only their request child`() = runBlocking {
        for (kind in ServerKind.entries) {
            val disconnected = CompletableDeferred<Unit>()
            val harness = startServer(kind, maximumBytes = 64 * 1024, timeoutMillis = 150) { exchange ->
                try {
                    val stream = exchange.request.openBodyStream() ?: error("stream unavailable")
                    stream.consume { }
                    exchange.response.write("complete")
                } finally {
                    if (exchange.request.path == "/disconnect") disconnected.complete(Unit)
                }
            }
            try {
                val timedOutStatus = Socket("127.0.0.1", harness.port).use { socket ->
                    socket.soTimeout = 2_000
                    socket.getOutputStream().write(
                        "POST /timeout HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20\r\nConnection: close\r\n\r\nx"
                            .toByteArray()
                    )
                    socket.getOutputStream().flush()
                    readStatus(socket)
                }
                assertEquals(408, timedOutStatus, "$kind stream deadline")

                Socket("127.0.0.1", harness.port).use { socket ->
                    socket.getOutputStream().write(
                        "POST /disconnect HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20\r\n\r\nx"
                            .toByteArray()
                    )
                    socket.getOutputStream().flush()
                }
                withTimeout(2_000) { disconnected.await() }

                val healthy = post(harness.port, "/healthy", byteArrayOf(1, 2, 3))
                assertEquals(200, healthy.statusCode(), "$kind sibling request")
                assertEquals("complete", healthy.body(), "$kind sibling response")
            } finally {
                harness.close()
            }
        }
    }

    @Test
    @Timeout(30)
    fun `response writer backpressures slow readers and releases cancelled downloads`() = runBlocking {
        for (kind in ServerKind.entries) {
            val cancelledFinished = CompletableDeferred<Unit>()
            val block = ByteArray(4 * 1024) { 9 }
            val harness = startServer(kind, maximumBytes = 64) { exchange ->
                try {
                    val count = if (exchange.request.path == "/cancel-download") 16 * 1024 else 2 * 1024
                    repeat(count) { exchange.response.write(block) }
                } finally {
                    if (exchange.request.path == "/cancel-download") cancelledFinished.complete(Unit)
                }
            }
            try {
                val slow = client.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:${harness.port}/slow-download"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream()
                )
                var total = 0L
                slow.body().use { input ->
                    val readBuffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(readBuffer)
                        if (read < 0) break
                        total += read
                        delay(1)
                    }
                }
                assertEquals(8L * 1024 * 1024, total, kind.name)

                val cancelled = client.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:${harness.port}/cancel-download"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream()
                )
                cancelled.body().use { it.read() }
                withTimeout(3_000) { cancelledFinished.await() }

                val healthy = client.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:${harness.port}/slow-download"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding()
                )
                assertEquals(200, healthy.statusCode(), kind.name)
            } finally {
                harness.close()
            }
        }
    }

    @Test
    @Timeout(20)
    fun `stream limits count decoded compressed bytes before handler completion`() = runBlocking {
        val compressed = gzip(ByteArray(32 * 1024) { 'a'.code.toByte() })
        for (kind in ServerKind.entries) {
            val calls = AtomicInteger(0)
            val harness = startServer(kind, maximumBytes = 64) { exchange ->
                exchange.request.openBodyStream()!!.consume { }
                calls.incrementAndGet()
            }
            try {
                val rejected = post(
                    harness.port,
                    "/compressed",
                    compressed,
                    mapOf("Content-Encoding" to "gzip")
                )
                assertEquals(413, rejected.statusCode(), kind.name)
                assertEquals("close", rejected.headers().firstValue("Connection").orElse(null), kind.name)
                assertEquals(0, calls.get(), kind.name)

                val healthy = post(harness.port, "/healthy", byteArrayOf(1))
                assertEquals(200, healthy.statusCode(), kind.name)
                assertEquals(1, calls.get(), kind.name)
            } finally {
                harness.close()
            }
        }
    }

    @Test
    @Timeout(30)
    fun `multipart boundaries stream incrementally into a slow destination`() = runBlocking {
        val boundary = "aether-boundary-03"
        val file = ByteArray(1024 * 1024) { 7 }
        val prefix = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"title\"\r\n\r\n" +
                "private\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"blob\"; filename=\"cipher.bin\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).encodeToByteArray()
        val suffix = "\r\n--$boundary--\r\n".encodeToByteArray()
        val body = prefix + file + suffix

        for (kind in ServerKind.entries) {
            val harness = startServer(kind, maximumBytes = body.size + 1, maxChunkSize = 17) { exchange ->
                var current: StreamingMultipartPart? = null
                var fileBytes = 0L
                var checksum = 0L
                val result = exchange.streamMultipart(
                    config = UploadConfig(
                        maxFileSize = file.size.toLong(),
                        maxRequestSize = body.size.toLong(),
                        maxFiles = 1
                    ),
                    maximumChunkBytes = 1024,
                    sink = object : StreamingMultipartSink {
                        override suspend fun onPartBegin(part: StreamingMultipartPart) {
                            current = part
                        }

                        override suspend fun onPartData(bytes: ByteArray, offset: Int, length: Int) {
                            delay(1)
                            if (current?.filename != null) {
                                fileBytes += length
                                for (index in offset until offset + length) checksum += bytes[index].toUByte().toLong()
                            }
                        }

                        override suspend fun onPartEnd() {
                            current = null
                        }
                    }
                )
                exchange.response.write(
                    "${result.partCount},${result.fileCount},${result.transportHighWaterBytes},$fileBytes,$checksum"
                )
            }
            try {
                val response = post(
                    harness.port,
                    "/multipart",
                    body,
                    mapOf("Content-Type" to "multipart/form-data; boundary=$boundary")
                )
                assertEquals(200, response.statusCode(), kind.name)
                val values = response.body().split(',').map(String::toLong)
                assertEquals(listOf(2L, 1L), values.take(2), kind.name)
                assertTrue(values[2] in 1..34, "$kind multipart high-water=${values[2]}")
                assertEquals(file.size.toLong(), values[3], kind.name)
                assertEquals(file.size.toLong() * 7, values[4], kind.name)
            } finally {
                harness.close()
            }
        }
    }

    @Test
    @Timeout(20)
    fun `close drains then cancels active work within its bound and is idempotent`() = runBlocking {
        for (kind in ServerKind.entries) {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val harness = startServer(
                kind = kind,
                maximumBytes = 64 * 1024,
                timeoutMillis = 10_000,
                shutdownGraceMillis = 100
            ) { exchange ->
                entered.complete(Unit)
                try {
                    exchange.request.openBodyStream()!!.consume { }
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val socket = Socket("127.0.0.1", harness.port)
            try {
                socket.getOutputStream().write(
                    "POST /active HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20\r\n\r\nx".toByteArray()
                )
                socket.getOutputStream().flush()
                withTimeout(2_000) { entered.await() }

                val elapsed = measureTimeMillis { harness.close() }
                assertTrue(elapsed < 2_000, "$kind shutdown took ${elapsed}ms")
                withTimeout(2_000) { cancelled.await() }
                harness.close()
            } finally {
                socket.close()
            }
        }
    }

    private suspend fun startServer(
        kind: ServerKind,
        maximumBytes: Int,
        timeoutMillis: Long = 5_000,
        shutdownGraceMillis: Long = 1_000,
        maxChunkSize: Int = 1024,
        responseWriteQueueBytes: Int = 16 * 1024,
        handler: suspend (Exchange) -> Unit
    ): Harness {
        val pipeline = Pipeline().apply { installRecovery() }
        return when (kind) {
            ServerKind.CORE -> {
                val server = VertxServer(
                    config = VertxServerConfig(
                        host = "127.0.0.1",
                        port = 0,
                        maxChunkSize = maxChunkSize,
                        maxRequestBodySize = maximumBytes,
                        requestBodyTimeoutMillis = timeoutMillis,
                        streamRequestBodies = true,
                        streamBufferChunks = 2,
                        shutdownGraceMillis = shutdownGraceMillis,
                        responseWriteQueueBytes = responseWriteQueueBytes
                    ),
                    pipeline = pipeline,
                    handler = handler
                )
                server.start()
                Harness(server.actualPort, server::close)
            }
            ServerKind.ROUTER -> {
                val server = AetherServer.create(
                    config = AetherServerConfig(
                        host = "127.0.0.1",
                        port = 0,
                        maxChunkSize = maxChunkSize,
                        maxRequestBodySize = maximumBytes,
                        requestBodyTimeoutMillis = timeoutMillis,
                        streamRequestBodies = true,
                        streamBufferChunks = 2,
                        shutdownGraceMillis = shutdownGraceMillis,
                        responseWriteQueueBytes = responseWriteQueueBytes
                    ),
                    router = router {
                        post("/:operation", handler)
                        get("/:operation", handler)
                    },
                    pipeline = pipeline
                )
                server.start()
                Harness(server.actualPort, server::close)
            }
        }
    }

    private fun post(
        port: Int,
        path: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        headers.forEach(builder::header)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun readStatus(socket: Socket): Int {
        val line = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
        assertTrue(line.startsWith("HTTP/1.1 "), line)
        return line.substringAfter(' ').substringBefore(' ').toInt()
    }

    private enum class ServerKind { CORE, ROUTER }

    private data class Harness(val port: Int, val close: suspend () -> Unit)
}
