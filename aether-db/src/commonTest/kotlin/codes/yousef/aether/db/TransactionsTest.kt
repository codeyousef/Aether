package codes.yousef.aether.db

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO

class TransactionsTest {
    @Test
    fun `unsupported driver rejects before entering block`() = runTest {
        var entered = false

        val failure = assertFailsWith<DatabaseTransactionException> {
            Driver().withTransaction {
                entered = true
            }
        }

        assertEquals(TransactionFailure.UNSUPPORTED, failure.failure)
        assertFalse(entered)
    }

    @Test
    fun `compare and set accepts exactly one affected row`() = runTest {
        val driver = Driver(affectedRows = 1)

        driver.executeCompareAndSet(
            "UPDATE object SET revision = revision + 1 WHERE owner_account_id = $1 AND id = $2 AND revision = $3",
            listOf(SqlValue.StringValue("owner"), SqlValue.StringValue("object"), SqlValue.LongValue(4))
        )

        assertEquals(1, driver.executions)
    }

    @Test
    fun `compare and set hides missing versus stale state`() = runTest {
        val failure = assertFailsWith<DatabaseTransactionException> {
            Driver(affectedRows = 0).executeCompareAndSet("UPDATE object", emptyList())
        }

        assertEquals(TransactionFailure.REVISION_CONFLICT, failure.failure)
        assertTrue(failure.retryable)
    }

    @Test
    fun `compare and set rejects a query that mutates multiple rows`() = runTest {
        val failure = assertFailsWith<DatabaseTransactionException> {
            Driver(affectedRows = 2).executeCompareAndSet("UPDATE object", emptyList())
        }

        assertEquals(TransactionFailure.INVALID_ROW_COUNT, failure.failure)
        assertFalse(failure.retryable)
    }

    @Test
    fun `transaction resource bounds must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            TransactionOptions(acquireTimeout = ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            TransactionOptions(statementTimeout = ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            TransactionOptions(transactionTimeout = ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            TransactionOptions(cleanupTimeout = ZERO)
        }
    }

    private class Driver(
        private val affectedRows: Int = 0
    ) : DatabaseDriver {
        var executions = 0
            private set

        override suspend fun executeQuery(query: QueryAST): List<Row> = emptyList()
        override suspend fun executeQueryRaw(sql: String): List<Row> = emptyList()
        override suspend fun executeUpdate(query: QueryAST): Int = affectedRows
        override suspend fun executeDDL(query: QueryAST) = Unit
        override suspend fun getTables(): List<String> = emptyList()
        override suspend fun getColumns(table: String): List<ColumnDefinition> = emptyList()

        override suspend fun execute(sql: String, params: List<SqlValue>): Int {
            executions++
            return affectedRows
        }

        override suspend fun close() = Unit
    }
}
