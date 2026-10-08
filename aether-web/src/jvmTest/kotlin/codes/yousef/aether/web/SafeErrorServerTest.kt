package codes.yousef.aether.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import codes.yousef.aether.core.pipeline.ApiErrorKind
import codes.yousef.aether.core.pipeline.ApiException
import codes.yousef.aether.core.pipeline.Pipeline
import codes.yousef.aether.core.pipeline.installCallLogging
import codes.yousef.aether.core.pipeline.installRecovery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Collections
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SafeErrorServerTest {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    @Test
    @Timeout(20)
    fun `real server responses and logs never expose request controlled diagnostics`() = runBlocking {
        val marker = "PRIVATE_HTTP_MARKER"
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val logAppender = ListAppender<ILoggingEvent>().apply { start() }
        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        rootLogger.addAppender(logAppender)
        val pipeline = Pipeline().apply {
            installCallLogging(messageSink = logs::add)
            installRecovery()
        }
        val server = AetherServer.create(
            config = AetherServerConfig(
                host = "127.0.0.1",
                port = 0,
                maxRequestBodySize = 32,
                requestBodyTimeoutMillis = 500
            ),
            router = router {
                post("/fail/:accountId") {
                    throw IllegalStateException(marker, IllegalArgumentException(marker))
                }
                post("/gone/:accountId") {
                    throw ApiException(ApiErrorKind.GONE)
                }
                post("/stream/:accountId") { exchange ->
                    exchange.response.write("stream-prefix")
                    throw ApiException(ApiErrorKind.CONFLICT)
                }
            },
            pipeline = pipeline
        )
        server.start()
        try {
            val failed = send(
                server.actualPort,
                "/fail/$marker?token=$marker",
                marker,
                "safe-request-123",
                marker
            )
            assertEquals(500, failed.statusCode())
            assertEquals("safe-request-123", failed.headers().firstValue("X-Request-Id").orElse(null))
            assertTrue(failed.body().contains("\"code\":\"INTERNAL\""))
            assertFalse(failed.body().contains(marker))
            assertFalse(failed.body().contains("Exception"))

            val gone = send(server.actualPort, "/gone/$marker", "{}", "safe-request-456", marker)
            assertEquals(410, gone.statusCode())
            assertTrue(gone.body().contains("\"code\":\"GONE\""))
            assertFalse(gone.body().contains(marker))

            val missing = send(server.actualPort, "/missing/$marker?token=$marker", "{}", "safe-request-789", marker)
            assertEquals(404, missing.statusCode())
            assertTrue(missing.body().contains("\"code\":\"NOT_FOUND\""))
            assertFalse(missing.body().contains(marker))

            val malformedId = send(server.actualPort, "/gone/account", "{}", "bad/id", marker)
            assertEquals(410, malformedId.statusCode())
            val replacementId = malformedId.headers().firstValue("X-Request-Id").orElseThrow()
            assertNotEquals("bad/id", replacementId)
            assertTrue(replacementId.length in 8..64)

            val streamed = send(server.actualPort, "/stream/account", "{}", "safe-request-stream", marker)
            assertEquals(200, streamed.statusCode())
            assertEquals("stream-prefix", streamed.body())

            val oversized = send(
                server.actualPort,
                "/fail/$marker",
                marker.repeat(4),
                "safe-request-large",
                marker
            )
            assertEquals(413, oversized.statusCode())
            assertEquals("safe-request-large", oversized.headers().firstValue("X-Request-Id").orElse(null))
            assertTrue(oversized.body().contains("\"code\":\"PAYLOAD_TOO_LARGE\""))
            assertFalse(oversized.body().contains(marker))

            assertTrue(logs.any { it.contains("route=/fail/:accountId") && it.contains("category=INTERNAL") })
            assertTrue(logs.any { it.contains("route=/gone/:accountId") && it.contains("category=GONE") })
            assertTrue(logs.any { it.contains("route=<unmatched>") && it.contains("status=404") })
            (logs + logAppender.list.map(ILoggingEvent::getFormattedMessage)).forEach { message ->
                assertFalse(message.contains(marker), message)
                assertFalse(message.contains("token="), message)
                assertFalse(message.contains("Cookie"), message)
            }
        } finally {
            server.close()
            rootLogger.detachAppender(logAppender)
            logAppender.stop()
        }
    }

    private fun send(
        port: Int,
        path: String,
        body: String,
        requestId: String,
        marker: String
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(3))
            .header("X-Request-Id", requestId)
            .header("X-Synthetic-Secret", marker)
            .header("Cookie", "session=$marker")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
