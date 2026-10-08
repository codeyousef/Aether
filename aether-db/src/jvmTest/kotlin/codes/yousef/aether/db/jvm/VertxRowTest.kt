package codes.yousef.aether.db.jvm

import codes.yousef.aether.db.DatabaseRowAccessException
import codes.yousef.aether.db.RowAccessFailure
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.buffer.Buffer
import io.vertx.sqlclient.Row
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class VertxRowTest {
    private val delegate = mockk<Row>()
    private val row = VertxRow(delegate)

    @Test
    fun `missing column and SQL NULL remain distinguishable`() {
        every { delegate.getColumnIndex("missing") } returns -1
        every { delegate.getColumnIndex("nullable") } returns 0
        every { delegate.getValue("nullable") } returns null
        every { delegate.getString("nullable") } returns null

        assertFalse(row.hasColumn("missing"))
        assertFalse(row.isNull("missing"))
        assertNull(row.getString("missing"))
        assertTrue(row.hasColumn("nullable"))
        assertTrue(row.isNull("nullable"))
        assertNull(row.getString("nullable"))
    }

    @Test
    fun `native PostgreSQL values retain UUID bytea and timestamptz types`() {
        val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val bytes = byteArrayOf(0, 1, -1)
        val timestamp = OffsetDateTime.parse("2026-10-08T12:34:56.123456Z")
        listOf("object_id", "ciphertext", "created_at").forEachIndexed { index, column ->
            every { delegate.getColumnIndex(column) } returns index
        }
        every { delegate.getUUID("object_id") } returns uuid
        every { delegate.getBuffer("ciphertext") } returns Buffer.buffer(bytes)
        every { delegate.getOffsetDateTime("created_at") } returns timestamp

        assertEquals(uuid.toString(), row.getUuid("object_id"))
        assertContentEquals(bytes, row.getBytes("ciphertext"))
        assertEquals(Instant.parse(timestamp.toInstant().toString()), row.getUtcTimestamp("created_at"))
    }

    @Test
    fun `invalid typed access is a stable public failure without private data`() {
        every { delegate.getColumnIndex("owner_id") } returns 0
        every { delegate.getInteger("owner_id") } throws ClassCastException("private-value")

        val failure = assertFailsWith<DatabaseRowAccessException> { row.getInt("owner_id") }

        assertEquals(RowAccessFailure.INVALID_TYPE, failure.failure)
        assertFalse(failure.message.orEmpty().contains("owner_id"))
        assertFalse(failure.message.orEmpty().contains("private-value"))
        assertNull(failure.cause)
    }
}
