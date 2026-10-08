package codes.yousef.aether.core.proxy

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StreamingProxyClientTest {
    @Test
    @Timeout(30)
    fun `slow consumer retains only configured response chunks`() = runBlocking {
        withUpstream { baseUrl, _ ->
            val client = StreamingProxyClient(
                ProxyConfig(
                    streamBufferSize = 4 * 1024,
                    streamBufferChunks = 2,
                    requestTimeout = 10.seconds
                )
            )
            try {
                val result = client.execute(request("$baseUrl/large"))
                var total = 0L
                result.bodyFlow.collect { chunk ->
                    delay(1)
                    total += chunk.size
                }

                assertEquals(2L * 1024 * 1024, total)
                assertTrue(result.metrics.highWaterBytes in 1..(8L * 1024), result.metrics.highWaterBytes.toString())
            } finally {
                client.close()
                client.close()
            }
        }
    }

    @Test
    @Timeout(20)
    fun `cancelled download resets upstream without poisoning later requests`() = runBlocking {
        withUpstream { baseUrl, disconnected ->
            val client = StreamingProxyClient(
                ProxyConfig(streamBufferSize = 4 * 1024, streamBufferChunks = 1)
            )
            try {
                val cancelled = client.execute(request("$baseUrl/large"))
                assertTrue(cancelled.bodyFlow.first().isNotEmpty())
                withTimeout(3_000) { disconnected.await() }

                val healthy = client.execute(request("$baseUrl/health"))
                var body = ""
                healthy.bodyFlow.collect { body += it.decodeToString() }
                assertEquals("healthy", body)
            } finally {
                client.close()
            }
        }
    }

    @Test
    @Timeout(20)
    fun `cancellation before response headers closes only that upstream request`() = runBlocking {
        withUpstream { baseUrl, _ ->
            val client = StreamingProxyClient(ProxyConfig())
            try {
                val pending = async { client.execute(request("$baseUrl/delayed")) }
                delay(100)
                pending.cancelAndJoin()

                val healthy = client.execute(request("$baseUrl/health"))
                var body = ""
                healthy.bodyFlow.collect { body += it.decodeToString() }
                assertEquals("healthy", body)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `upload totals and chunks fail before exceeding configured memory`() = runBlocking {
        withUpstream { baseUrl, _ ->
            val client = StreamingProxyClient(
                ProxyConfig(maxRequestBodySize = 8, streamBufferSize = 4, streamBufferChunks = 1)
            )
            try {
                assertFailsWith<ProxyPayloadTooLargeException> {
                    client.execute(
                        StreamingProxyRequest(
                            method = "POST",
                            url = "$baseUrl/upload",
                            headers = emptyMap(),
                            bodyFlow = flow { emit(ByteArray(5)) },
                            bodySize = null,
                            timeout = 1.seconds
                        )
                    )
                }
                assertFailsWith<ProxyPayloadTooLargeException> {
                    client.execute(
                        StreamingProxyRequest(
                            method = "POST",
                            url = "$baseUrl/upload",
                            headers = emptyMap(),
                            bodyFlow = flow { emit(ByteArray(4)); emit(ByteArray(4)); emit(ByteArray(1)) },
                            bodySize = null,
                            timeout = 1.seconds
                        )
                    )
                }
                val healthy = client.execute(request("$baseUrl/health"))
                var body = ""
                healthy.bodyFlow.collect { body += it.decodeToString() }
                assertEquals("healthy", body)
            } finally {
                client.close()
            }
        }
    }

    private fun request(url: String) = StreamingProxyRequest(
        method = "GET",
        url = url,
        headers = emptyMap(),
        bodyFlow = null,
        bodySize = null,
        timeout = 10.seconds
    )

    private suspend fun withUpstream(
        block: suspend (baseUrl: String, disconnected: CompletableDeferred<Unit>) -> Unit
    ) {
        val vertx = Vertx.vertx()
        val scope = CoroutineScope(Dispatchers.Default)
        val disconnected = CompletableDeferred<Unit>()
        val server = vertx.createHttpServer()
            .requestHandler { request ->
                request.connection().closeHandler { disconnected.complete(Unit) }
                when (request.path()) {
                    "/health" -> request.response().end("healthy")
                    "/delayed" -> scope.launch {
                        delay(5_000)
                        runCatching { request.response().end("late").coAwait() }
                    }
                    else -> scope.launch {
                        try {
                            request.response().isChunked = true
                            repeat(512) {
                                request.response().write(Buffer.buffer(ByteArray(4 * 1024) { 7 })).coAwait()
                            }
                            if (!request.response().ended()) request.response().end().coAwait()
                        } catch (_: Exception) {
                            // Expected when the consumer cancels the response stream.
                        }
                    }
                }
            }
            .listen(0, "127.0.0.1")
            .coAwait()
        try {
            block("http://127.0.0.1:${server.actualPort()}", disconnected)
        } finally {
            server.close().coAwait()
            vertx.close().coAwait()
        }
    }
}
