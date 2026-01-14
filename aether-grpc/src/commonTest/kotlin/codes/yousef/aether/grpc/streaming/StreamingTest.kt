@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package codes.yousef.aether.grpc.streaming

import codes.yousef.aether.grpc.GrpcException
import codes.yousef.aether.grpc.GrpcStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TDD Tests for server streaming support.
 */
class ServerStreamingTest {

    @Test
    fun `server streaming handler emits multiple values`() = runTest {
        val handler = ServerStreamingHandler<String, Int> { request ->
            flow {
                val count = request.toIntOrNull() ?: 3
                for (i in 1..count) {
                    emit(i)
                }
            }
        }

        val results = handler.handle("5").toList()

        assertEquals(listOf(1, 2, 3, 4, 5), results)
    }

    @Test
    fun `server streaming handler supports empty flow`() = runTest {
        val handler = ServerStreamingHandler<String, Int> { _ ->
            emptyFlow()
        }

        val results = handler.handle("any").toList()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `server streaming handler propagates errors`() = runTest {
        val handler = ServerStreamingHandler<String, Int> { _ ->
            flow {
                emit(1)
                throw GrpcException.internal("Something went wrong")
            }
        }

        val exception = assertFailsWith<GrpcException> {
            handler.handle("test").toList()
        }

        assertEquals(GrpcStatus.INTERNAL, exception.status)
    }

    @Test
    fun `server streaming handler supports flow cancellation`() = runTest {
        var emittedCount = 0
        val handler = ServerStreamingHandler<String, Int> { _ ->
            flow {
                for (i in 1..100) {
                    emittedCount++
                    emit(i)
                }
            }
        }

        val results = handler.handle("test").take(3).toList()

        assertEquals(listOf(1, 2, 3), results)
        // Due to flow buffering, emittedCount might be slightly more than 3
        assertTrue(emittedCount >= 3)
    }
}

/**
 * TDD Tests for client streaming support.
 */
class ClientStreamingTest {

    @Test
    fun `client streaming handler collects all values`() = runTest {
        val handler = ClientStreamingHandler<Int, String> { requests ->
            val values = requests.toList()
            "Received: ${values.joinToString(", ")}"
        }

        val inputFlow = flowOf(1, 2, 3, 4, 5)
        val result = handler.handle(inputFlow)

        assertEquals("Received: 1, 2, 3, 4, 5", result)
    }

    @Test
    fun `client streaming handler handles empty input`() = runTest {
        val handler = ClientStreamingHandler<Int, String> { requests ->
            val values = requests.toList()
            "Count: ${values.size}"
        }

        val result = handler.handle(emptyFlow())

        assertEquals("Count: 0", result)
    }

    @Test
    fun `client streaming handler can aggregate values`() = runTest {
        val handler = ClientStreamingHandler<Int, Int> { requests ->
            requests.fold(0) { acc, value -> acc + value }
        }

        val inputFlow = flowOf(1, 2, 3, 4, 5)
        val result = handler.handle(inputFlow)

        assertEquals(15, result)
    }

    @Test
    fun `client streaming handler propagates input errors`() = runTest {
        val handler = ClientStreamingHandler<Int, String> { requests ->
            requests.toList().toString()
        }

        val inputFlow = flow {
            emit(1)
            throw IllegalStateException("Client error")
        }

        assertFailsWith<IllegalStateException> {
            handler.handle(inputFlow)
        }
    }
}

/**
 * TDD Tests for bidirectional streaming support.
 */
class BiDirectionalStreamingTest {

    @Test
    fun `bidi streaming echoes transformed values`() = runTest {
        val handler = BiDirectionalStreamingHandler<String, String> { requests ->
            requests.map { it.uppercase() }
        }

        val inputFlow = flowOf("hello", "world")
        val results = handler.handle(inputFlow).toList()

        assertEquals(listOf("HELLO", "WORLD"), results)
    }

    @Test
    fun `bidi streaming can produce more outputs than inputs`() = runTest {
        val handler = BiDirectionalStreamingHandler<Int, Int> { requests ->
            requests.flatMapConcat { value ->
                flow {
                    emit(value)
                    emit(value * 2)
                }
            }
        }

        val inputFlow = flowOf(1, 2, 3)
        val results = handler.handle(inputFlow).toList()

        assertEquals(listOf(1, 2, 2, 4, 3, 6), results)
    }

    @Test
    fun `bidi streaming can filter values`() = runTest {
        val handler = BiDirectionalStreamingHandler<Int, Int> { requests ->
            requests.filter { it % 2 == 0 }
        }

        val inputFlow = flowOf(1, 2, 3, 4, 5)
        val results = handler.handle(inputFlow).toList()

        assertEquals(listOf(2, 4), results)
    }

    @Test
    fun `bidi streaming propagates errors from handler`() = runTest {
        val handler = BiDirectionalStreamingHandler<Int, Int> { requests ->
            requests.map { value ->
                if (value == 3) throw GrpcException.invalidArgument("Value 3 not allowed")
                value
            }
        }

        val inputFlow = flowOf(1, 2, 3, 4)

        val exception = assertFailsWith<GrpcException> {
            handler.handle(inputFlow).toList()
        }

        assertEquals(GrpcStatus.INVALID_ARGUMENT, exception.status)
    }

    @Test
    fun `bidi streaming supports concurrent processing`() = runTest {
        val handler = BiDirectionalStreamingHandler<Int, Int> { requests ->
            requests.buffer().map { it * 2 }
        }

        val inputFlow = flowOf(1, 2, 3, 4, 5)
        val results = handler.handle(inputFlow).toList()

        assertEquals(listOf(2, 4, 6, 8, 10), results)
    }
}

/**
 * TDD Tests for streaming codecs.
 */
class StreamingCodecTest {

    @Test
    fun `LPM codec frames message with length prefix`() {
        val codec = LpmCodec()
        val message = "Hello, gRPC!".encodeToByteArray()

        val framed = codec.frame(message)

        // LPM format: 1 byte compression flag + 4 bytes length + message
        assertEquals(1 + 4 + message.size, framed.size)
        assertEquals(0.toByte(), framed[0]) // No compression
    }

    @Test
    fun `LPM codec extracts message from frame`() {
        val codec = LpmCodec()
        val original = "Hello, gRPC!".encodeToByteArray()
        val framed = codec.frame(original)

        val extracted = codec.unframe(framed)

        assertTrue(original.contentEquals(extracted))
    }

    @Test
    fun `LPM codec handles empty message`() {
        val codec = LpmCodec()
        val message = ByteArray(0)

        val framed = codec.frame(message)
        val extracted = codec.unframe(framed)

        assertTrue(extracted.isEmpty())
    }

    @Test
    fun `SSE codec formats event correctly`() {
        val codec = SseCodec()
        val data = """{"name":"test"}"""

        val event = codec.formatEvent(data)

        assertTrue(event.startsWith("data: "))
        assertTrue(event.contains(data))
        assertTrue(event.endsWith("\n\n"))
    }

    @Test
    fun `SSE codec supports event type`() {
        val codec = SseCodec()
        val data = """{"status":"ok"}"""

        val event = codec.formatEvent(data, eventType = "message")

        assertTrue(event.contains("event: message\n"))
        assertTrue(event.contains("data: $data"))
    }

    @Test
    fun `SSE codec supports event ID`() {
        val codec = SseCodec()
        val data = "test data"

        val event = codec.formatEvent(data, id = "123")

        assertTrue(event.contains("id: 123\n"))
    }
}
