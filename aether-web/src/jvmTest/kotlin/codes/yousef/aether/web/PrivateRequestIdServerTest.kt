package codes.yousef.aether.web

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.jvm.VertxServer
import codes.yousef.aether.core.jvm.VertxServerConfig
import codes.yousef.aether.core.pipeline.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.Socket
import java.net.URI
import java.net.http.*
import java.time.Duration
import kotlin.test.*

class PrivateRequestIdServerTest {
    @Test @Timeout(30)
    fun `private ids cover both listeners and predispatch failures without caller reflection`() = runBlocking {
        for (routerServer in listOf(false, true)) {
            val logs = mutableListOf<String>()
            val diagnostics = RequestDiagnosticsPolicy(requestIdSource = RequestIdSource.SERVER_GENERATED_ONLY)
            val pipeline = Pipeline().apply { installCallLogging(messageSink = logs::add); installRecovery() }
            val handler: suspend (Exchange) -> Unit = { exchange ->
                when (exchange.request.path) {
                    "/fail" -> throw ApiException(ApiErrorKind.GONE)
                    "/missing" -> respondWithApiError(exchange, ApiErrorKind.NOT_FOUND)
                    "/stream" -> { exchange.response.write("prefix"); throw ApiException(ApiErrorKind.CONFLICT) }
                    else -> exchange.response.write("healthy")
                }
            }
            val core = if (routerServer) null else VertxServer(VertxServerConfig(host = "127.0.0.1", port = 0,
                diagnostics = diagnostics, maxRequestBodySize = 32, requestBodyTimeoutMillis = 100), pipeline, handler)
            val web = if (!routerServer) null else AetherServer.create(AetherServerConfig(host = "127.0.0.1", port = 0,
                diagnostics = diagnostics, maxRequestBodySize = 32, requestBodyTimeoutMillis = 100),
                router { post("/:action", handler) }, pipeline)
            core?.start(); web?.start()
            val port = core?.actualPort ?: web!!.actualPort
            try {
                val client = HttpClient.newHttpClient()
                val ids = mutableSetOf<String>()
                for (marker in listOf("PRIVATE_ACCEPTED_SHAPE_SECRET", "de305d54-75b4-431b-adb2-eb6b9e546014", "bad/id", "x".repeat(512))) {
                    for ((path, body, expected) in listOf(Triple("/healthy", "", 200), Triple("/missing", "", 404),
                        Triple("/fail", "", 410), Triple("/stream", "", 200), Triple("/fail", "x".repeat(64), 413))) {
                        val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                            .timeout(Duration.ofSeconds(2)).header("X-Request-Id", marker).header("X-Request-Id", marker)
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
                        assertEquals(expected, response.statusCode())
                        val id = response.headers().firstValue("X-Request-Id").orElseThrow()
                        assertTrue(id.matches(Regex("[a-f0-9]{32}")))
                        assertTrue(ids.add(id))
                        assertFalse(response.headers().toString().contains(marker))
                        assertFalse(response.body().contains(marker))
                        if (expected != 200) assertTrue(response.body().contains(id))
                    }
                }
                for (wire in listOf(
                    "POST /healthy HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20\r\nX-Request-Id: PRIVATE_TIMEOUT_SECRET\r\nConnection: close\r\n\r\nx",
                    "GET / HTTP/INVALID\r\nHost: localhost\r\nX-Request-Id: PRIVATE_PARSER_SECRET\r\nConnection: close\r\n\r\n")) {
                    Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 2000
                        socket.getOutputStream().write(wire.toByteArray()); socket.getOutputStream().flush()
                        val reader = socket.getInputStream().bufferedReader()
                        val text = buildString {
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                appendLine(line)
                            }
                        }
                        assertFalse(text.contains("PRIVATE"))
                        assertTrue(text.contains("X-Request-Id", ignoreCase = true))
                    }
                }
                assertTrue(logs.isNotEmpty())
                assertFalse(logs.any { it.contains("PRIVATE") || it.contains("de305d54") || it.contains("bad/id") })
            } finally { core?.close(); web?.close() }
        }
    }
}
