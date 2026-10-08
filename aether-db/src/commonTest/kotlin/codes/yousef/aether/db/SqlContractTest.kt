package codes.yousef.aether.db

import codes.yousef.aether.db.firestore.FirestoreTranslator
import codes.yousef.aether.db.supabase.SupabaseTranslator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class SqlContractTest {
    @Test
    fun `PostgreSQL translation retains native value types as bound parameters`() {
        val uuid = SqlValue.UuidValue("123e4567-e89b-12d3-a456-426614174000")
        val bytes = SqlValue.ByteArrayValue(byteArrayOf(0, 1, -1))
        val timestamp = SqlValue.UtcTimestampValue(Instant.parse("2026-10-08T12:34:56.123456Z"))

        val translated = SqlTranslator.translate(
            InsertQuery(
                table = "private_objects",
                columns = listOf("object_id", "ciphertext", "created_at"),
                values = listOf(uuid, bytes, timestamp).map(Expression::Literal),
                returning = listOf("object_id")
            )
        )

        assertEquals(
            "INSERT INTO private_objects (object_id, ciphertext, created_at) VALUES ($1, $2, $3) RETURNING object_id",
            translated.sql
        )
        assertEquals(uuid, translated.params[0])
        assertEquals(bytes, translated.params[1])
        assertEquals(timestamp, translated.params[2])
    }

    @Test
    fun `tenant account queue device object and envelope values never enter translated SQL`() {
        val privateValues = listOf(
            "tenant' OR true; --",
            "account-private",
            "queue-private",
            "device-private",
            "object-private",
            "envelope-private"
        )
        val translated = SqlTranslator.translate(
            InsertQuery(
                table = "private_objects",
                columns = listOf(
                    "tenant_id", "account_id", "queue_id", "device_id", "object_id", "envelope"
                ),
                values = privateValues.map { Expression.Literal(SqlValue.StringValue(it)) },
                returning = listOf("object_id")
            )
        )

        assertEquals(
            "INSERT INTO private_objects (tenant_id, account_id, queue_id, device_id, object_id, envelope) " +
                "VALUES ($1, $2, $3, $4, $5, $6) RETURNING object_id",
            translated.sql
        )
        assertEquals(privateValues, translated.params)
        privateValues.forEach { kotlin.test.assertFalse(translated.sql.contains(it)) }
    }

    @Test
    fun `SQL identifiers cannot carry values or statement fragments`() {
        assertFailsWith<DatabaseException> {
            SqlTranslator.translate(SelectQuery(columns = emptyList(), from = "objects WHERE owner = 'other'"))
        }
        assertFailsWith<DatabaseException> {
            SqlTranslator.translate(
                InsertQuery(
                    table = "objects",
                    columns = listOf("owner_id) VALUES ('other'); --"),
                    values = listOf(Expression.Literal(SqlValue.StringValue("owner")))
                )
            )
        }
    }

    @Test
    fun `existing SqlValue serialization discriminators remain compatible`() {
        val fixtures = listOf(
            SqlValue.StringValue("value") to
                "{\"type\":\"codes.yousef.aether.db.SqlValue.StringValue\",\"value\":\"value\"}",
            SqlValue.IntValue(7) to
                "{\"type\":\"codes.yousef.aether.db.SqlValue.IntValue\",\"value\":7}",
            SqlValue.LongValue(8) to
                "{\"type\":\"codes.yousef.aether.db.SqlValue.LongValue\",\"value\":8}",
            SqlValue.DoubleValue(1.5) to
                "{\"type\":\"codes.yousef.aether.db.SqlValue.DoubleValue\",\"value\":1.5}",
            SqlValue.BooleanValue(true) to
                "{\"type\":\"codes.yousef.aether.db.SqlValue.BooleanValue\",\"value\":true}",
            SqlValue.NullValue to
                "{\"type\":\"codes.yousef.aether.db.SqlValue.NullValue\"}"
        )

        fixtures.forEach { (value, encoded) ->
            assertEquals(encoded, Json.encodeToString<SqlValue>(value))
            assertEquals(value, Json.decodeFromString<SqlValue>(encoded))
        }
    }

    @Test
    fun `native SqlValue variants serialize losslessly`() {
        val uuid = SqlValue.UuidValue("123e4567-e89b-12d3-a456-426614174000")
        val bytes = SqlValue.ByteArrayValue(byteArrayOf(0, 1, -1))
        val timestamp = SqlValue.UtcTimestampValue(Instant.parse("2026-10-08T12:34:56.123456Z"))

        assertEquals(uuid, Json.decodeFromString<SqlValue>(Json.encodeToString<SqlValue>(uuid)))
        val decodedBytes = Json.decodeFromString<SqlValue>(Json.encodeToString<SqlValue>(bytes))
            as SqlValue.ByteArrayValue
        kotlin.test.assertContentEquals(bytes.value, decodedBytes.value)
        assertEquals(
            timestamp,
            Json.decodeFromString<SqlValue>(Json.encodeToString<SqlValue>(timestamp))
        )
    }

    @Test
    fun `UUID values reject noncanonical input at the public boundary`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SqlValue.UuidValue("private-not-a-uuid")
        }
        kotlin.test.assertFalse(failure.message.orEmpty().contains("private-not-a-uuid"))
    }

    @Test
    fun `remote adapters reject native values instead of coercing them`() {
        val nativeValues = listOf(
            SqlValue.UuidValue("123e4567-e89b-12d3-a456-426614174000") to DatabaseFeature.NATIVE_UUID,
            SqlValue.ByteArrayValue(byteArrayOf(0, 1, -1)) to DatabaseFeature.NATIVE_BYTES,
            SqlValue.UtcTimestampValue(Instant.parse("2026-10-08T12:34:56Z")) to
                DatabaseFeature.NATIVE_TIMESTAMP
        )

        nativeValues.forEach { (value, feature) ->
            val query = InsertQuery(
                table = "objects",
                columns = listOf("native_value"),
                values = listOf(Expression.Literal(value))
            )

            val firestore = assertFailsWith<DatabaseFeatureUnsupportedException> {
                FirestoreTranslator.translate(query)
            }
            assertEquals(feature, firestore.feature)

            val supabase = assertFailsWith<DatabaseFeatureUnsupportedException> {
                SupabaseTranslator.translate(query)
            }
            assertEquals(feature, supabase.feature)
        }
    }
}
