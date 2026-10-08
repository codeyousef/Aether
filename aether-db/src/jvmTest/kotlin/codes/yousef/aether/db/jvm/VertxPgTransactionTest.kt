package codes.yousef.aether.db.jvm

import codes.yousef.aether.db.DatabaseDriver
import codes.yousef.aether.db.DatabaseTransactionException
import codes.yousef.aether.db.SqlValue
import codes.yousef.aether.db.TransactionFailure
import codes.yousef.aether.db.TransactionOptions
import codes.yousef.aether.db.withTransaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.vertx.core.Future
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Query
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Transaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class VertxPgTransactionTest {
    @Test
    fun `successful block commits and releases exactly once`() = runBlocking {
        val fixture = Fixture()

        val result = fixture.driver.withTransaction { tx ->
            tx.execute("UPDATE object SET revision = revision + 1", emptyList())
            "committed"
        }

        assertEquals("committed", result)
        verify(exactly = 1) { fixture.transaction.commit() }
        verify(exactly = 0) { fixture.transaction.rollback() }
        verify(exactly = 1) { fixture.connection.close() }
        verify(exactly = 0) { fixture.pool.close() }
    }

    @Test
    fun `block failure rolls back and releases while preserving failure`() = runBlocking {
        val fixture = Fixture()
        val marker = SyntheticFailure()

        val failure = assertFailsWith<SyntheticFailure> {
            fixture.driver.withTransaction { throw marker }
        }

        assertEquals(marker, failure)
        verify(exactly = 0) { fixture.transaction.commit() }
        verify(exactly = 1) { fixture.transaction.rollback() }
        verify(exactly = 1) { fixture.connection.close() }
    }

    @Test
    fun `bound driver cannot nest close or escape`() = runBlocking {
        val fixture = Fixture()
        lateinit var escaped: DatabaseDriver

        fixture.driver.withTransaction { tx ->
            escaped = tx
            val nested = assertFailsWith<DatabaseTransactionException> {
                tx.withTransaction { error("must not run") }
            }
            assertEquals(TransactionFailure.NESTED_TRANSACTION, nested.failure)

            val close = assertFailsWith<DatabaseTransactionException> { tx.close() }
            assertEquals(TransactionFailure.CONNECTION_CLOSE_FORBIDDEN, close.failure)
        }

        val stale = assertFailsWith<DatabaseTransactionException> {
            escaped.executeQueryRaw("SELECT 1")
        }
        assertEquals(TransactionFailure.SCOPE_CLOSED, stale.failure)
        verify(exactly = 1) { fixture.transaction.commit() }
        verify(exactly = 1) { fixture.connection.close() }
    }

    @Test
    fun `serialization and deadlock failures are typed retryable and rolled back`() = runBlocking {
        for ((sqlState, expected) in listOf(
            "40001" to TransactionFailure.SERIALIZATION_CONFLICT,
            "40P01" to TransactionFailure.DEADLOCK
        )) {
            val fixture = Fixture(
                updateFailure = PgException("private", "ERROR", sqlState, "private")
            )

            val failure = assertFailsWith<DatabaseTransactionException> {
                fixture.driver.withTransaction { tx ->
                    tx.execute("UPDATE object SET revision = revision + 1", emptyList())
                }
            }

            assertEquals(expected, failure.failure)
            assertEquals(sqlState, failure.sqlState)
            assertEquals(true, failure.retryable)
            verify(exactly = 1) { fixture.transaction.rollback() }
            verify(exactly = 1) { fixture.connection.close() }
        }
    }

    @Test
    fun `acquire timeout rejects before entering block`() = runBlocking {
        val pool = mockk<Pool>()
        every { pool.connection } returns Future.future { }
        val driver = VertxPgDriver(pool)
        var entered = false

        val failure = assertFailsWith<DatabaseTransactionException> {
            driver.withTransaction(
                TransactionOptions(acquireTimeout = 20.milliseconds)
            ) {
                entered = true
            }
        }

        assertEquals(TransactionFailure.ACQUIRE_TIMEOUT, failure.failure)
        assertEquals(false, entered)
    }

    @Test
    fun `statement timeout rolls back and leaves pool usable`() = runBlocking {
        val fixture = Fixture(updateFuture = Future.future { })

        val failure = assertFailsWith<DatabaseTransactionException> {
            fixture.driver.withTransaction(
                TransactionOptions(statementTimeout = 20.milliseconds)
            ) { tx ->
                tx.execute("UPDATE object SET revision = revision + 1", emptyList())
            }
        }

        assertEquals(TransactionFailure.STATEMENT_TIMEOUT, failure.failure)
        verify(exactly = 1) { fixture.transaction.rollback() }
        verify(exactly = 1) { fixture.connection.close() }
    }

    @Test
    fun `whole transaction timeout rolls back`() = runBlocking {
        val fixture = Fixture()

        val failure = assertFailsWith<DatabaseTransactionException> {
            fixture.driver.withTransaction(
                TransactionOptions(transactionTimeout = 20.milliseconds)
            ) {
                delay(100)
            }
        }

        assertEquals(TransactionFailure.TRANSACTION_TIMEOUT, failure.failure)
        verify(exactly = 1) { fixture.transaction.rollback() }
        verify(exactly = 1) { fixture.connection.close() }
    }


    @Test
    fun `commit failure reports unknown outcome and does not claim rollback`() = runBlocking {
        val fixture = Fixture(
            commitFailure = PgException("private", "ERROR", "08006", "private")
        )

        val failure = assertFailsWith<DatabaseTransactionException> {
            fixture.driver.withTransaction { "result" }
        }

        assertEquals(TransactionFailure.COMMIT_OUTCOME_UNKNOWN, failure.failure)
        assertEquals("08006", failure.sqlState)
        verify(exactly = 0) { fixture.transaction.rollback() }
        verify(exactly = 1) { fixture.connection.close() }
    }

    @Test
    fun `non-pool client rejects transaction before entering block`() = runBlocking {
        val connection = mockk<SqlConnection>(relaxed = true)
        val driver = VertxPgDriver(connection)
        var entered = false

        val failure = assertFailsWith<DatabaseTransactionException> {
            driver.withTransaction { entered = true }
        }

        assertEquals(TransactionFailure.UNSUPPORTED, failure.failure)
        assertEquals(false, entered)
    }

    private class Fixture(
        updateFailure: Throwable? = null,
        updateFuture: Future<RowSet<Row>>? = null,
        commitFailure: Throwable? = null
    ) {
        val pool = mockk<Pool>()
        val connection = mockk<SqlConnection>()
        val transaction = mockk<Transaction>()
        private val isolationQuery = mockk<Query<RowSet<Row>>>()
        private val updateQuery = mockk<io.vertx.sqlclient.PreparedQuery<RowSet<Row>>>()
        private val rows = mockk<RowSet<Row>>()
        val driver = VertxPgDriver(pool)

        init {
            every { pool.connection } returns Future.succeededFuture(connection)
            every { pool.close() } returns Future.succeededFuture()
            every { connection.begin() } returns Future.succeededFuture(transaction)
            every { connection.query(any()) } returns isolationQuery
            every { isolationQuery.execute() } returns Future.succeededFuture(rows)
            every { connection.preparedQuery(any()) } returns updateQuery
            every { rows.rowCount() } returns 1
            every { updateQuery.execute(any<io.vertx.sqlclient.Tuple>()) } returns when {
                updateFuture != null -> updateFuture
                updateFailure != null -> Future.failedFuture(updateFailure)
                else -> Future.succeededFuture(rows)
            }
            every { transaction.commit() } returns if (commitFailure == null) {
                Future.succeededFuture()
            } else {
                Future.failedFuture(commitFailure)
            }
            every { transaction.rollback() } returns Future.succeededFuture()
            every { connection.close() } returns Future.succeededFuture()
        }
    }

    private class SyntheticFailure : RuntimeException()
}
