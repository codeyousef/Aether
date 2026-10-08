package codes.yousef.aether.db

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** PostgreSQL isolation levels supported by Aether's transactional driver capability. */
enum class TransactionIsolation {
    READ_COMMITTED,
    SERIALIZABLE
}

/** Explicit resource bounds for one database transaction. */
data class TransactionOptions(
    val isolation: TransactionIsolation = TransactionIsolation.READ_COMMITTED,
    val acquireTimeout: Duration = 5.seconds,
    val statementTimeout: Duration = 30.seconds,
    val transactionTimeout: Duration = 30.seconds,
    val cleanupTimeout: Duration = 2.seconds
) {
    init {
        require(acquireTimeout.isPositive()) { "Transaction acquire timeout must be positive" }
        require(statementTimeout.isPositive()) { "Transaction statement timeout must be positive" }
        require(transactionTimeout.isPositive()) { "Transaction timeout must be positive" }
        require(cleanupTimeout.isPositive()) { "Transaction cleanup timeout must be positive" }
    }
}

/** Additive capability implemented by drivers that can provide connection-bound transactions. */
interface TransactionalDatabaseDriver : DatabaseDriver {
    /**
     * Runs [block] on one exclusively owned connection and commits only after successful return.
     * The supplied driver is valid only for the dynamic extent of [block]. The block is never
     * retried automatically because it may contain effects outside the database.
     */
    suspend fun <T> withTransaction(
        options: TransactionOptions = TransactionOptions(),
        block: suspend (DatabaseDriver) -> T
    ): T
}

enum class TransactionFailure {
    UNSUPPORTED,
    NESTED_TRANSACTION,
    SCOPE_CLOSED,
    ACQUIRE_TIMEOUT,
    STATEMENT_TIMEOUT,
    CONNECTION_CLOSE_FORBIDDEN,
    TRANSACTION_TIMEOUT,
    SERIALIZATION_CONFLICT,
    DEADLOCK,
    COMMIT_OUTCOME_UNKNOWN,
    ROLLBACK_FAILED,
    RELEASE_FAILED,
    REVISION_CONFLICT,
    INVALID_ROW_COUNT
}

/** Safe, typed transaction failure. SQL and parameter values are never included. */
open class DatabaseTransactionException(
    val failure: TransactionFailure,
    val retryable: Boolean = false,
    val sqlState: String? = null,
    cause: Throwable? = null
) : DatabaseException(
    buildString {
        append("Database transaction failed (")
        append(failure.name)
        append(')')
        if (sqlState != null) {
            append(" (SQLSTATE ")
            append(sqlState)
            append(')')
        }
    },
    cause
)

/** Cancellation while commit acknowledgement is pending; persisted receipts determine outcome. */
class TransactionCommitCancellationException :
    CancellationException("Database commit outcome is unknown; resolve through a persisted receipt") {
    val failure: TransactionFailure = TransactionFailure.COMMIT_OUTCOME_UNKNOWN
}

/**
 * Executes a transaction through an additive capability. Unsupported drivers reject the call
 * before [block] is entered.
 */
suspend fun <T> DatabaseDriver.withTransaction(
    options: TransactionOptions = TransactionOptions(),
    block: suspend (DatabaseDriver) -> T
): T {
    val transactional = this as? TransactionalDatabaseDriver
        ?: throw DatabaseTransactionException(TransactionFailure.UNSUPPORTED)
    return transactional.withTransaction(options, block)
}

/**
 * Executes a caller-supplied tenant/revision-qualified CAS statement and requires one changed row.
 * A zero-row result intentionally does not distinguish missing state from stale revision state.
 */
suspend fun DatabaseDriver.executeCompareAndSet(
    sql: String,
    params: List<SqlValue>
) {
    when (execute(sql, params)) {
        1 -> Unit
        0 -> throw DatabaseTransactionException(
            failure = TransactionFailure.REVISION_CONFLICT,
            retryable = true
        )
        else -> throw DatabaseTransactionException(TransactionFailure.INVALID_ROW_COUNT)
    }
}
