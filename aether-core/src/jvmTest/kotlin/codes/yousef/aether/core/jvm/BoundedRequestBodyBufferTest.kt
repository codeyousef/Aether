package codes.yousef.aether.core.jvm

import io.vertx.core.buffer.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertEquals

class BoundedRequestBodyBufferTest {
    @Test
    fun `retains a body only while it remains within the streaming limit`() {
        val body = BoundedRequestBodyBuffer(8)

        body.append(Buffer.buffer(byteArrayOf(1, 2, 3)))
        body.append(Buffer.buffer(byteArrayOf(4, 5)))

        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), body.finish())
    }

    @Test
    fun `discards an oversized body without appending later chunks`() {
        val body = BoundedRequestBodyBuffer(4)

        body.append(Buffer.buffer(byteArrayOf(1, 2, 3)))
        body.append(Buffer.buffer(byteArrayOf(4, 5)))
        body.append(Buffer.buffer(ByteArray(1_024)))

        assertNull(body.finish())
        assertEquals(0, body.retainedBytes)
    }


    @Test
    fun `requires a positive body limit`() {
        assertFailsWith<IllegalArgumentException> { BoundedRequestBodyBuffer(0) }
    }
}
