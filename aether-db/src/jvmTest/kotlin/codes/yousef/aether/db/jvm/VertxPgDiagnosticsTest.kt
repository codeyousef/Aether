package codes.yousef.aether.db.jvm

import codes.yousef.aether.core.pipeline.DiagnosticsProfile
import codes.yousef.aether.db.DatabaseFailureCategory
import codes.yousef.aether.db.DatabaseException
import codes.yousef.aether.db.DatabaseOperation
import io.vertx.pgclient.PgException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class VertxPgDiagnosticsTest {
    @Test
    fun `production failure exposes only operation and validated SQLSTATE`() {
        val marker = "PRIVATE_SQL_MARKER"
        val cause = PgException(marker, "ERROR", "23505", marker)

        val failure = mapDatabaseFailure(DatabaseOperation.UPDATE, cause)

        assertEquals(DatabaseOperation.UPDATE, failure.operation)
        assertEquals("23505", failure.sqlState)
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains(marker))
        assertEquals("Database update failed (SQLSTATE 23505)", failure.message)
    }

    @Test
    fun `constraint and concurrency SQLSTATE values map to stable categories`() {
        val expected = mapOf(
            "23505" to DatabaseFailureCategory.UNIQUE_VIOLATION,
            "23503" to DatabaseFailureCategory.FOREIGN_KEY_VIOLATION,
            "23514" to DatabaseFailureCategory.CHECK_VIOLATION,
            "40001" to DatabaseFailureCategory.SERIALIZATION_CONFLICT,
            "40P01" to DatabaseFailureCategory.DEADLOCK
        )

        expected.forEach { (sqlState, category) ->
            val failure = mapDatabaseFailure(
                DatabaseOperation.UPDATE,
                PgException("private", "ERROR", sqlState, "private")
            )
            assertEquals(category, failure.category)
        }
    }

    @Test
    fun `development profile may retain separately controlled cause`() {
        val cause = IllegalStateException("development detail")
        val failure = mapDatabaseFailure(
            DatabaseOperation.QUERY,
            cause,
            DiagnosticsProfile.DEVELOPMENT
        )

        assertSame(cause, failure.cause)
        assertEquals("Database query failed", failure.message)
    }

    @Test
    fun `nested cancellation is never converted to database failure`() {
        val cancellation = CancellationException("cancel")
        val wrapped = IllegalStateException("wrapper", cancellation)

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                mapDatabaseFailure(DatabaseOperation.QUERY, wrapped)
            }
        )
    }

    @Test
    fun `cause traversal is bounded`() {
        var cause: Throwable = PgException("hidden", "ERROR", "23505", "hidden")
        repeat(9) { cause = IllegalStateException("wrapper", cause) }

        val failure = mapDatabaseFailure(DatabaseOperation.QUERY, cause)

        assertNull(failure.sqlState)
        assertNull(failure.cause)
    }

    @Test
    fun `invalid connection URL does not echo credentials`() {
        val marker = "postgresql://PRIVATE_USER:PRIVATE_PASSWORD@invalid"

        val failure = assertFailsWith<DatabaseException> {
            VertxPgDriver.create(connectionUrl = marker)
        }

        assertEquals("Invalid PostgreSQL connection URL", failure.message)
        assertFalse(failure.message.orEmpty().contains("PRIVATE"))
    }
}
