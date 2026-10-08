package codes.yousef.aether.db.jvm

import codes.yousef.aether.core.pipeline.DiagnosticsProfile
import codes.yousef.aether.core.pipeline.QueryLogContext
import codes.yousef.aether.core.pipeline.QueryLogEntry
import codes.yousef.aether.db.*
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.pgclient.PgBuilder
import io.vertx.pgclient.PgConnectOptions
import io.vertx.pgclient.PgException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Transaction
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Instant
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * JVM implementation of DatabaseDriver using Vert.x Reactive PostgreSQL Client.
 * Bridges Vert.x Future API to Kotlin Coroutines.
 */
class VertxPgDriver(
    private val client: SqlClient,
    private val diagnosticsProfile: DiagnosticsProfile = DiagnosticsProfile.PRIVATE_PRODUCTION
) : TransactionalDatabaseDriver {

    override suspend fun executeQuery(query: QueryAST): List<Row> {
        val translated = SqlTranslator.translate(query)
        val tuple = Tuple.from(translated.params.map(::translatedParameter))
        val start = Clock.System.now()

        try {
            val rowSet = client.preparedQuery(translated.sql)
                .execute(tuple)
                .coAwait()

            val duration = Clock.System.now().toEpochMilliseconds() - start.toEpochMilliseconds()
            logQuery(translated.sql, duration)

            return rowSet.map { vertxRow ->
                VertxRow(vertxRow)
            }
        } catch (e: Exception) {
            throw databaseFailure(DatabaseOperation.QUERY, e)
        }
    }

    override suspend fun executeQuery(sql: String, params: List<SqlValue>): List<Row> {
        val start = Clock.System.now()
        try {
            val rowSet = client.preparedQuery(sql)
                .execute(Tuple.from(params.map(::sqlParameter)))
                .coAwait()
            val duration = Clock.System.now().toEpochMilliseconds() - start.toEpochMilliseconds()
            logQuery(sql, duration)
            return rowSet.map(::VertxRow)
        } catch (e: Exception) {
            throw databaseFailure(DatabaseOperation.QUERY, e)
        }
    }

    @Deprecated("Raw SQL cannot bind untrusted values; use executeQuery(sql, params)")
    override suspend fun executeQueryRaw(sql: String): List<Row> {
        val start = Clock.System.now()
        try {
            val rowSet = client.query(sql)
                .execute()
                .coAwait()

            val duration = Clock.System.now().toEpochMilliseconds() - start.toEpochMilliseconds()
            logQuery(sql, duration)

            return rowSet.map { vertxRow ->
                VertxRow(vertxRow)
            }
        } catch (e: Exception) {
            throw databaseFailure(DatabaseOperation.QUERY, e)
        }
    }

    override suspend fun executeUpdate(query: QueryAST): Int {
        val translated = SqlTranslator.translate(query)
        val tuple = Tuple.from(translated.params.map(::translatedParameter))
        val start = Clock.System.now()

        try {
            val rowSet = client.preparedQuery(translated.sql)
                .execute(tuple)
                .coAwait()

            val duration = Clock.System.now().toEpochMilliseconds() - start.toEpochMilliseconds()
            logQuery(translated.sql, duration)

            return rowSet.rowCount()
        } catch (e: Exception) {
            throw databaseFailure(DatabaseOperation.UPDATE, e)
        }
    }

    private suspend fun logQuery(sql: String, duration: Long) {
        coroutineContext[QueryLogContext]?.record(QueryLogEntry(sql, duration))
    }

    private fun databaseFailure(
        operation: DatabaseOperation,
        throwable: Throwable
    ): DatabaseOperationException = mapDatabaseFailure(operation, throwable, diagnosticsProfile)

    override suspend fun executeDDL(query: QueryAST) {
        val translated = SqlTranslator.translate(query)

        try {
            client.query(translated.sql)
                .execute()
                .coAwait()
        } catch (e: Exception) {
            throw databaseFailure(DatabaseOperation.DDL, e)
        }
    }

    override suspend fun getTables(): List<String> {
        val sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
        try {
            val rowSet = client.query(sql).execute().coAwait()
            return rowSet.map { it.getString("table_name") }
        } catch (e: Exception) {
            throw databaseFailure(DatabaseOperation.METADATA, e)
        }
    }

    override suspend fun getColumns(table: String): List<ColumnDefinition> {
        val sql = """
            SELECT column_name, data_type, is_nullable, column_default, is_identity 
            FROM information_schema.columns 
            WHERE table_name = $1 AND table_schema = 'public'
        """.trimIndent()
        
        try {
            val rowSet = client.preparedQuery(sql).execute(Tuple.of(table)).coAwait()
            return rowSet.map { row ->
                val name = row.getString("column_name")
                val dataType = row.getString("data_type")
                val isNullable = row.getString("is_nullable") == "YES"
                val defaultVal = row.getString("column_default")
                val isIdentity = row.getString("is_identity") == "YES"
                
                // Map Postgres types to our types (simplified)
                // This is a lossy conversion but sufficient for basic diffing
                val type = when (dataType.uppercase()) {
                    "CHARACTER VARYING", "VARCHAR" -> "VARCHAR(255)" // Length check needed?
                    "TEXT" -> "TEXT"
                    "INTEGER", "INT" -> "INTEGER"
                    "BIGINT" -> "BIGINT"
                    "BOOLEAN" -> "BOOLEAN"
                    "DOUBLE PRECISION" -> "DOUBLE PRECISION"
                    else -> dataType.uppercase()
                }

                ColumnDefinition(
                    name = name,
                    type = type,
                    nullable = isNullable,
                    primaryKey = false, // Need another query for PKs
                    unique = false, // Need another query for Unique constraints
                    defaultValue = null, // Parsing default value is complex
                    autoIncrement = isIdentity || defaultVal?.contains("nextval") == true
                )
            }
        } catch (e: Exception) {
            throw databaseFailure(DatabaseOperation.METADATA, e)
        }
    }

    override suspend fun execute(sql: String, params: List<SqlValue>): Int {
        val tuple = Tuple.from(params.map(::sqlParameter))
        try {
            val rowSet = client.preparedQuery(sql)
                .execute(tuple)
                .coAwait()
            return rowSet.rowCount()
        } catch (e: Exception) {
            throw databaseFailure(DatabaseOperation.UPDATE, e)
        }
    }
    override suspend fun <T> withTransaction(
        options: TransactionOptions,
        block: suspend (DatabaseDriver) -> T
    ): T {
        val pool = client as? Pool
            ?: throw DatabaseTransactionException(TransactionFailure.UNSUPPORTED)
        val connection = try {
            withTimeout(options.acquireTimeout.timeoutMillis()) {
                acquireConnection(pool)
            }
        } catch (_: TimeoutCancellationException) {
            throw DatabaseTransactionException(
                failure = TransactionFailure.ACQUIRE_TIMEOUT,
                retryable = true
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw databaseFailure(DatabaseOperation.QUERY, failure)
        }

        var transaction: Transaction? = null
        var commitStarted = false
        var primaryFailure: Throwable? = null
        try {
            transaction = withStatementTimeout(options.statementTimeout) {
                connection.begin().coAwait()
            }
            withStatementTimeout(options.statementTimeout) {
                connection.query(options.isolation.statementSql()).execute().coAwait()
            }

            val scope = TransactionScope(options.statementTimeout)
            val boundDriver = TransactionBoundDriver(
                delegate = VertxPgDriver(connection, diagnosticsProfile),
                scope = scope
            )
            val result = try {
                withTimeoutOrNull(options.transactionTimeout.timeoutMillis()) {
                    coroutineScope {
                        TransactionBlockResult(block(boundDriver))
                    }
                }?.value ?: throw DatabaseTransactionException(
                    failure = TransactionFailure.TRANSACTION_TIMEOUT,
                    retryable = true
                )
            } finally {
                scope.close()
            }

            commitStarted = true
            try {
                withStatementTimeout(options.statementTimeout) {
                    transaction.commit().coAwait()
                }
            } catch (_: CancellationException) {
                throw TransactionCommitCancellationException()
            } catch (failure: Throwable) {
                throw mapCommitFailure(failure, diagnosticsProfile)
            }
            return result
        } catch (failure: Throwable) {
            primaryFailure = failure
            if (!commitStarted) {
                transaction?.let { activeTransaction ->
                    rollbackFailure(activeTransaction, options.cleanupTimeout)?.let {
                        failure.addSuppressed(it)
                    }
                }
            }
            throw failure
        } finally {
            val releaseFailure = releaseFailure(connection, options.cleanupTimeout)
            if (primaryFailure == null && releaseFailure != null) {
                throw releaseFailure
            }
        }
    }

    override suspend fun close() {
        try {
            client.close().coAwait()
        } catch (e: Exception) {
            throw databaseFailure(DatabaseOperation.CLOSE, e)
        }
    }

    companion object {
        /**
         * Creates a VertxPgDriver with the given connection options.
         */
        fun create(
            host: String = "localhost",
            port: Int = 5432,
            database: String,
            user: String,
            password: String,
            maxPoolSize: Int = 5,
            vertx: Vertx = Vertx.vertx(),
            diagnosticsProfile: DiagnosticsProfile = DiagnosticsProfile.PRIVATE_PRODUCTION
        ): VertxPgDriver {
            val connectOptions = PgConnectOptions()
                .setPort(port)
                .setHost(host)
                .setDatabase(database)
                .setUser(user)
                .setPassword(password)

            val poolOptions = PoolOptions()
                .setMaxSize(maxPoolSize)

            val pool = PgBuilder
                .pool()
                .with(poolOptions)
                .connectingTo(connectOptions)
                .using(vertx)
                .build()
            return VertxPgDriver(pool, diagnosticsProfile)
        }

        /**
         * Creates a VertxPgDriver from a connection URL.
         */
        fun create(
            connectionUrl: String,
            maxPoolSize: Int = 5,
            vertx: Vertx = Vertx.vertx(),
            diagnosticsProfile: DiagnosticsProfile = DiagnosticsProfile.PRIVATE_PRODUCTION
        ): VertxPgDriver {
            // Parse connection URL: postgresql://user:password@host:port/database
            val regex = Regex("""postgresql://([^:]+):([^@]+)@([^:]+):(\d+)/(.+)""")
            val matchResult = regex.matchEntire(connectionUrl)
                ?: throw DatabaseException("Invalid PostgreSQL connection URL")

            val (user, password, host, port, database) = matchResult.destructured

            return create(
                host = host,
                port = port.toInt(),
                database = database,
                user = user,
                password = password,
                maxPoolSize = maxPoolSize,
                vertx = vertx,
                diagnosticsProfile = diagnosticsProfile
            )
        }
    }
}
private suspend fun acquireConnection(pool: Pool): SqlConnection =
    suspendCancellableCoroutine { continuation ->
        pool.connection.onComplete { result ->
            if (result.succeeded()) {
                continuation.resume(result.result()) { _, lateConnection, _ ->
                    lateConnection.close()
                }
            } else {
                continuation.resumeWithException(result.cause())
            }
        }
    }

private data class TransactionBlockResult<T>(val value: T)

private class TransactionScope(
    private val statementTimeout: Duration
) {
    private val active = AtomicBoolean(true)
    private val mutex = Mutex()

    fun close() {
        active.set(false)
    }

    suspend fun <T> run(block: suspend () -> T): T {
        ensureActive()
        return mutex.withLock {
            ensureActive()
            try {
                withTimeout(statementTimeout.timeoutMillis()) {
                    block()
                }
            } catch (timeout: TimeoutCancellationException) {
                if (!coroutineContext.isActive) throw timeout
                throw DatabaseTransactionException(
                    failure = TransactionFailure.STATEMENT_TIMEOUT,
                    retryable = true
                )
            } catch (failure: DatabaseOperationException) {
                throw mapTransactionConflict(failure)
            }
        }
    }

    private fun ensureActive() {
        if (!active.get()) {
            throw DatabaseTransactionException(TransactionFailure.SCOPE_CLOSED)
        }
    }
}

private class TransactionBoundDriver(
    private val delegate: VertxPgDriver,
    private val scope: TransactionScope
) : TransactionalDatabaseDriver {
    override suspend fun executeQuery(query: QueryAST): List<Row> =
        scope.run { delegate.executeQuery(query) }

    @Suppress("DEPRECATION")
    @Deprecated("Raw SQL cannot bind untrusted values; use executeQuery(sql, params)")
    override suspend fun executeQueryRaw(sql: String): List<Row> =
        scope.run { delegate.executeQueryRaw(sql) }

    override suspend fun executeQuery(sql: String, params: List<SqlValue>): List<Row> =
        scope.run { delegate.executeQuery(sql, params) }

    override suspend fun executeUpdate(query: QueryAST): Int =
        scope.run { delegate.executeUpdate(query) }

    override suspend fun executeDDL(query: QueryAST) =
        scope.run { delegate.executeDDL(query) }

    override suspend fun getTables(): List<String> =
        scope.run { delegate.getTables() }

    override suspend fun getColumns(table: String): List<ColumnDefinition> =
        scope.run { delegate.getColumns(table) }

    override suspend fun execute(sql: String, params: List<SqlValue>): Int =
        scope.run { delegate.execute(sql, params) }

    override suspend fun <T> withTransaction(
        options: TransactionOptions,
        block: suspend (DatabaseDriver) -> T
    ): T {
        throw DatabaseTransactionException(TransactionFailure.NESTED_TRANSACTION)
    }

    override suspend fun close() {
        throw DatabaseTransactionException(TransactionFailure.CONNECTION_CLOSE_FORBIDDEN)
    }
}

private fun TransactionIsolation.statementSql(): String = when (this) {
    TransactionIsolation.READ_COMMITTED ->
        "SET TRANSACTION ISOLATION LEVEL READ COMMITTED"
    TransactionIsolation.SERIALIZABLE ->
        "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE"
}

private fun Duration.timeoutMillis(): Long = inWholeMilliseconds.coerceAtLeast(1)

private suspend fun <T> withStatementTimeout(
    timeout: Duration,
    block: suspend () -> T
): T = try {
    withTimeout(timeout.timeoutMillis()) {
        block()
    }
} catch (failure: TimeoutCancellationException) {
    if (!coroutineContext.isActive) throw failure
    throw DatabaseTransactionException(
        failure = TransactionFailure.STATEMENT_TIMEOUT,
        retryable = true
    )
}

private suspend fun rollbackFailure(
    transaction: Transaction,
    timeout: Duration
): DatabaseTransactionException? = withContext(NonCancellable) {
    try {
        withTimeout(timeout.timeoutMillis()) {
            transaction.rollback().coAwait()
        }
        null
    } catch (_: Throwable) {
        DatabaseTransactionException(TransactionFailure.ROLLBACK_FAILED)
    }
}

private suspend fun releaseFailure(
    connection: SqlConnection,
    timeout: Duration
): DatabaseTransactionException? = withContext(NonCancellable) {
    try {
        withTimeout(timeout.timeoutMillis()) {
            connection.close().coAwait()
        }
        null
    } catch (_: Throwable) {
        DatabaseTransactionException(TransactionFailure.RELEASE_FAILED)
    }
}

private fun mapCommitFailure(
    failure: Throwable,
    profile: DiagnosticsProfile
): DatabaseTransactionException {
    val mapped = mapDatabaseFailure(DatabaseOperation.UPDATE, failure, profile)
    return when (mapped.sqlState) {
        "40001" -> DatabaseTransactionException(
            failure = TransactionFailure.SERIALIZATION_CONFLICT,
            retryable = true,
            sqlState = mapped.sqlState,
            cause = mapped.cause
        )
        "40P01" -> DatabaseTransactionException(
            failure = TransactionFailure.DEADLOCK,
            retryable = true,
            sqlState = mapped.sqlState,
            cause = mapped.cause
        )
        else -> DatabaseTransactionException(
            failure = TransactionFailure.COMMIT_OUTCOME_UNKNOWN,
            sqlState = mapped.sqlState,
            cause = mapped.cause
        )
    }
}

private fun mapTransactionConflict(
    failure: DatabaseOperationException
): Throwable = when (failure.sqlState) {
    "40001" -> DatabaseTransactionException(
        failure = TransactionFailure.SERIALIZATION_CONFLICT,
        retryable = true,
        sqlState = failure.sqlState,
        cause = failure.cause
    )
    "40P01" -> DatabaseTransactionException(
        failure = TransactionFailure.DEADLOCK,
        retryable = true,
        sqlState = failure.sqlState,
        cause = failure.cause
    )
    else -> failure
}


private fun translatedParameter(value: Any?): Any? =
    if (value is SqlValue) sqlParameter(value) else value

private fun sqlParameter(value: SqlValue): Any? = when (value) {
    is SqlValue.StringValue -> value.value
    is SqlValue.IntValue -> value.value
    is SqlValue.LongValue -> value.value
    is SqlValue.DoubleValue -> value.value
    is SqlValue.BooleanValue -> value.value
    is SqlValue.UuidValue -> UUID.fromString(value.value)
    is SqlValue.ByteArrayValue -> Buffer.buffer(value.value)
    is SqlValue.UtcTimestampValue -> OffsetDateTime.parse(value.value.toString())
    SqlValue.NullValue -> null
}

internal fun mapDatabaseFailure(
    operation: DatabaseOperation,
    throwable: Throwable,
    profile: DiagnosticsProfile = DiagnosticsProfile.PRIVATE_PRODUCTION
): DatabaseOperationException {
    if (throwable is CancellationException) throw throwable
    var current: Throwable? = throwable
    val visited = mutableSetOf<Throwable>()
    var sqlState: String? = null
    var depth = 0
    while (current != null && depth < 8 && visited.add(current)) {
        val candidate = current
        if (candidate is CancellationException) throw candidate
        if (candidate is PgException) {
            sqlState = candidate.sqlState.takeIf { state ->
                state.length == 5 && state.all { character -> character.isLetterOrDigit() }
            }
            break
        }
        current = candidate.cause
        depth++
    }
    val diagnosticCause = throwable.takeIf { profile == DiagnosticsProfile.DEVELOPMENT }
    return DatabaseOperationException(operation, sqlState, diagnosticCause)
}

/**
 * Implementation of Row interface wrapping Vert.x io.vertx.sqlclient.Row.
 */
class VertxRow(
    private val row: io.vertx.sqlclient.Row
) : Row {

    override fun getString(column: String): String? =
        typed(column, "string") { row.getString(column) }

    override fun getInt(column: String): Int? =
        typed(column, "integer") { row.getInteger(column) }

    override fun getLong(column: String): Long? =
        typed(column, "long") { row.getLong(column) }

    override fun getDouble(column: String): Double? =
        typed(column, "double") { row.getDouble(column) }

    override fun getBoolean(column: String): Boolean? =
        typed(column, "boolean") { row.getBoolean(column) }

    override fun getUuid(column: String): String? =
        typed(column, "uuid") { row.getUUID(column)?.toString() }

    override fun getBytes(column: String): ByteArray? =
        typed(column, "bytes") { row.getBuffer(column)?.bytes }

    override fun getUtcTimestamp(column: String): Instant? =
        typed(column, "UTC timestamp") {
            row.getOffsetDateTime(column)?.let { Instant.parse(it.toInstant().toString()) }
        }

    override fun getValue(column: String): Any? {
        return if (hasColumn(column)) {
            row.getValue(column)
        } else {
            null
        }
    }

    override fun getColumnNames(): List<String> {
        val names = mutableListOf<String>()
        for (i in 0 until row.size()) {
            names.add(row.getColumnName(i))
        }
        return names
    }

    override fun hasColumn(column: String): Boolean =
        try {
            row.getColumnIndex(column) >= 0
        } catch (_: Exception) {
            false
        }

    override fun isNull(column: String): Boolean =
        hasColumn(column) && row.getValue(column) == null

    private inline fun <T> typed(column: String, expectedType: String, read: () -> T?): T? {
        if (!hasColumn(column)) return null
        return try {
            read()
        } catch (_: Exception) {
            throw DatabaseRowAccessException(RowAccessFailure.INVALID_TYPE, expectedType)
        }
    }
}
