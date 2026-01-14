package codes.yousef.aether.grpc

import kotlin.test.*

/**
 * TDD Tests for core gRPC types.
 * These tests are written first, before implementation.
 */
class GrpcStatusTest {

    @Test
    fun `OK status has code 0`() {
        assertEquals(0, GrpcStatus.OK.code)
    }

    @Test
    fun `CANCELLED status has code 1`() {
        assertEquals(1, GrpcStatus.CANCELLED.code)
    }

    @Test
    fun `UNKNOWN status has code 2`() {
        assertEquals(2, GrpcStatus.UNKNOWN.code)
    }

    @Test
    fun `INVALID_ARGUMENT status has code 3`() {
        assertEquals(3, GrpcStatus.INVALID_ARGUMENT.code)
    }

    @Test
    fun `DEADLINE_EXCEEDED status has code 4`() {
        assertEquals(4, GrpcStatus.DEADLINE_EXCEEDED.code)
    }

    @Test
    fun `NOT_FOUND status has code 5`() {
        assertEquals(5, GrpcStatus.NOT_FOUND.code)
    }

    @Test
    fun `ALREADY_EXISTS status has code 6`() {
        assertEquals(6, GrpcStatus.ALREADY_EXISTS.code)
    }

    @Test
    fun `PERMISSION_DENIED status has code 7`() {
        assertEquals(7, GrpcStatus.PERMISSION_DENIED.code)
    }

    @Test
    fun `RESOURCE_EXHAUSTED status has code 8`() {
        assertEquals(8, GrpcStatus.RESOURCE_EXHAUSTED.code)
    }

    @Test
    fun `FAILED_PRECONDITION status has code 9`() {
        assertEquals(9, GrpcStatus.FAILED_PRECONDITION.code)
    }

    @Test
    fun `ABORTED status has code 10`() {
        assertEquals(10, GrpcStatus.ABORTED.code)
    }

    @Test
    fun `OUT_OF_RANGE status has code 11`() {
        assertEquals(11, GrpcStatus.OUT_OF_RANGE.code)
    }

    @Test
    fun `UNIMPLEMENTED status has code 12`() {
        assertEquals(12, GrpcStatus.UNIMPLEMENTED.code)
    }

    @Test
    fun `INTERNAL status has code 13`() {
        assertEquals(13, GrpcStatus.INTERNAL.code)
    }

    @Test
    fun `UNAVAILABLE status has code 14`() {
        assertEquals(14, GrpcStatus.UNAVAILABLE.code)
    }

    @Test
    fun `DATA_LOSS status has code 15`() {
        assertEquals(15, GrpcStatus.DATA_LOSS.code)
    }

    @Test
    fun `UNAUTHENTICATED status has code 16`() {
        assertEquals(16, GrpcStatus.UNAUTHENTICATED.code)
    }

    @Test
    fun `fromCode returns correct status for valid codes`() {
        assertEquals(GrpcStatus.OK, GrpcStatus.fromCode(0))
        assertEquals(GrpcStatus.NOT_FOUND, GrpcStatus.fromCode(5))
        assertEquals(GrpcStatus.INTERNAL, GrpcStatus.fromCode(13))
        assertEquals(GrpcStatus.UNAUTHENTICATED, GrpcStatus.fromCode(16))
    }

    @Test
    fun `fromCode returns UNKNOWN for invalid codes`() {
        assertEquals(GrpcStatus.UNKNOWN, GrpcStatus.fromCode(-1))
        assertEquals(GrpcStatus.UNKNOWN, GrpcStatus.fromCode(17))
        assertEquals(GrpcStatus.UNKNOWN, GrpcStatus.fromCode(100))
    }

    @Test
    fun `isOk returns true only for OK status`() {
        assertTrue(GrpcStatus.OK.isOk)
        assertFalse(GrpcStatus.CANCELLED.isOk)
        assertFalse(GrpcStatus.INTERNAL.isOk)
    }

    @Test
    fun `isError returns false only for OK status`() {
        assertFalse(GrpcStatus.OK.isError)
        assertTrue(GrpcStatus.CANCELLED.isError)
        assertTrue(GrpcStatus.INTERNAL.isError)
    }
}

class GrpcExceptionTest {

    @Test
    fun `exception contains status and message`() {
        val ex = GrpcException(GrpcStatus.NOT_FOUND, "User not found")
        assertEquals(GrpcStatus.NOT_FOUND, ex.status)
        assertEquals("User not found", ex.message)
    }

    @Test
    fun `exception with default message uses status description`() {
        val ex = GrpcException(GrpcStatus.INTERNAL)
        assertEquals(GrpcStatus.INTERNAL, ex.status)
        assertEquals("INTERNAL", ex.message)
    }

    @Test
    fun `exception can have a cause`() {
        val cause = RuntimeException("Root cause")
        val ex = GrpcException(GrpcStatus.INTERNAL, "Wrapped error", cause)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun `exception statusCode returns the numeric code`() {
        val ex = GrpcException(GrpcStatus.PERMISSION_DENIED, "Access denied")
        assertEquals(7, ex.statusCode)
    }

    @Test
    fun `notFound helper creates NOT_FOUND exception`() {
        val ex = GrpcException.notFound("Resource missing")
        assertEquals(GrpcStatus.NOT_FOUND, ex.status)
        assertEquals("Resource missing", ex.message)
    }

    @Test
    fun `invalidArgument helper creates INVALID_ARGUMENT exception`() {
        val ex = GrpcException.invalidArgument("Bad input")
        assertEquals(GrpcStatus.INVALID_ARGUMENT, ex.status)
        assertEquals("Bad input", ex.message)
    }

    @Test
    fun `internal helper creates INTERNAL exception`() {
        val ex = GrpcException.internal("Server error")
        assertEquals(GrpcStatus.INTERNAL, ex.status)
        assertEquals("Server error", ex.message)
    }

    @Test
    fun `unauthenticated helper creates UNAUTHENTICATED exception`() {
        val ex = GrpcException.unauthenticated("Invalid token")
        assertEquals(GrpcStatus.UNAUTHENTICATED, ex.status)
        assertEquals("Invalid token", ex.message)
    }

    @Test
    fun `permissionDenied helper creates PERMISSION_DENIED exception`() {
        val ex = GrpcException.permissionDenied("Not allowed")
        assertEquals(GrpcStatus.PERMISSION_DENIED, ex.status)
        assertEquals("Not allowed", ex.message)
    }

    @Test
    fun `unimplemented helper creates UNIMPLEMENTED exception`() {
        val ex = GrpcException.unimplemented("Method not available")
        assertEquals(GrpcStatus.UNIMPLEMENTED, ex.status)
        assertEquals("Method not available", ex.message)
    }
}

class GrpcMetadataTest {

    @Test
    fun `empty metadata has no entries`() {
        val metadata = GrpcMetadata()
        assertTrue(metadata.isEmpty())
        assertEquals(0, metadata.size)
    }

    @Test
    fun `put and get string value`() {
        val metadata = GrpcMetadata()
        metadata.put("authorization", "Bearer token123")
        assertEquals("Bearer token123", metadata.get("authorization"))
    }

    @Test
    fun `get returns null for missing key`() {
        val metadata = GrpcMetadata()
        assertNull(metadata.get("missing"))
    }

    @Test
    fun `keys are case-insensitive`() {
        val metadata = GrpcMetadata()
        metadata.put("Authorization", "Bearer token")
        assertEquals("Bearer token", metadata.get("authorization"))
        assertEquals("Bearer token", metadata.get("AUTHORIZATION"))
        assertEquals("Bearer token", metadata.get("Authorization"))
    }

    @Test
    fun `put overwrites existing value`() {
        val metadata = GrpcMetadata()
        metadata.put("key", "value1")
        metadata.put("key", "value2")
        assertEquals("value2", metadata.get("key"))
    }

    @Test
    fun `contains returns true for existing key`() {
        val metadata = GrpcMetadata()
        metadata.put("exists", "value")
        assertTrue(metadata.contains("exists"))
        assertTrue(metadata.contains("EXISTS"))
    }

    @Test
    fun `contains returns false for missing key`() {
        val metadata = GrpcMetadata()
        assertFalse(metadata.contains("missing"))
    }

    @Test
    fun `remove removes entry`() {
        val metadata = GrpcMetadata()
        metadata.put("key", "value")
        metadata.remove("key")
        assertNull(metadata.get("key"))
        assertFalse(metadata.contains("key"))
    }

    @Test
    fun `remove is case-insensitive`() {
        val metadata = GrpcMetadata()
        metadata.put("Key", "value")
        metadata.remove("KEY")
        assertNull(metadata.get("key"))
    }

    @Test
    fun `keys returns all key names`() {
        val metadata = GrpcMetadata()
        metadata.put("key1", "value1")
        metadata.put("key2", "value2")
        val keys = metadata.keys()
        assertEquals(2, keys.size)
        assertTrue(keys.any { it.equals("key1", ignoreCase = true) })
        assertTrue(keys.any { it.equals("key2", ignoreCase = true) })
    }

    @Test
    fun `getAll returns all values for multi-value key`() {
        val metadata = GrpcMetadata()
        metadata.add("accept", "application/grpc")
        metadata.add("accept", "application/json")
        val values = metadata.getAll("accept")
        assertEquals(2, values.size)
        assertTrue(values.contains("application/grpc"))
        assertTrue(values.contains("application/json"))
    }

    @Test
    fun `add appends value without overwriting`() {
        val metadata = GrpcMetadata()
        metadata.add("multi", "first")
        metadata.add("multi", "second")
        assertEquals("first", metadata.get("multi")) // get returns first
        assertEquals(listOf("first", "second"), metadata.getAll("multi"))
    }

    @Test
    fun `putAll copies entries from another metadata`() {
        val source = GrpcMetadata()
        source.put("key1", "value1")
        source.put("key2", "value2")

        val target = GrpcMetadata()
        target.putAll(source)

        assertEquals("value1", target.get("key1"))
        assertEquals("value2", target.get("key2"))
    }

    @Test
    fun `toMap returns immutable map copy`() {
        val metadata = GrpcMetadata()
        metadata.put("key", "value")
        val map = metadata.toMap()
        assertEquals(mapOf("key" to listOf("value")), map)
    }

    @Test
    fun `companion of creates metadata from pairs`() {
        val metadata = GrpcMetadata.of(
            "key1" to "value1",
            "key2" to "value2"
        )
        assertEquals("value1", metadata.get("key1"))
        assertEquals("value2", metadata.get("key2"))
    }

    @Test
    fun `clear removes all entries`() {
        val metadata = GrpcMetadata()
        metadata.put("key1", "value1")
        metadata.put("key2", "value2")
        metadata.clear()
        assertTrue(metadata.isEmpty())
        assertEquals(0, metadata.size)
    }
}
