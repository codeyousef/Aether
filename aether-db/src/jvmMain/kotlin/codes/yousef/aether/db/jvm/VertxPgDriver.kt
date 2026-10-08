package codes.yousef.aether.db.jvm

import codes.yousef.aether.db.*
import codes.yousef.aether.core.pipeline.QueryLogContext
import codes.yousef.aether.core.pipeline.QueryLogEntry
import codes.yousef.aether.core.pipeline.DiagnosticsProfile
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.pgclient.PgBuilder
import io.vertx.pgclient.PgException
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

/**
 * JVM implementation of DatabaseDriver using Vert.x Reactive PostgreSQL Client.
 * Bridges Vert.x Future API to Kotlin Coroutines.
 */
class VertxPgDriver(
    private val client: SqlClient,
    private val diagnosticsProfile: DiagnosticsProfile = DiagnosticsProfile.PRIVATE_PRODUCTION
) : DatabaseDriver {

    override suspend fun executeQuery(query: QueryAST): List<Row> {
        val translated = SqlTranslator.translate(query)
        val tuple = Tuple.from(translated.params)
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
        val tuple = Tuple.from(translated.params)
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
        val paramValues = params.map { 
            when (it) {
                is SqlValue.StringValue -> it.value
                is SqlValue.IntValue -> it.value
                is SqlValue.LongValue -> it.value
                is SqlValue.DoubleValue -> it.value
                is SqlValue.BooleanValue -> it.value
                is SqlValue.NullValue -> null
            }
        }
        val tuple = Tuple.from(paramValues)
        try {
            val rowSet = client.preparedQuery(sql)
                .execute(tuple)
                .coAwait()
            return rowSet.rowCount()
        } catch (e: Exception) {
            throw databaseFailure(DatabaseOperation.UPDATE, e)
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

    override fun getString(column: String): String? {
        return if (hasColumn(column)) {
            row.getString(column)
        } else {
            null
        }
    }

    override fun getInt(column: String): Int? {
        return if (hasColumn(column)) {
            row.getInteger(column)
        } else {
            null
        }
    }

    override fun getLong(column: String): Long? {
        return if (hasColumn(column)) {
            row.getLong(column)
        } else {
            null
        }
    }

    override fun getDouble(column: String): Double? {
        return if (hasColumn(column)) {
            row.getDouble(column)
        } else {
            null
        }
    }

    override fun getBoolean(column: String): Boolean? {
        return if (hasColumn(column)) {
            row.getBoolean(column)
        } else {
            null
        }
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

    override fun hasColumn(column: String): Boolean {
        return try {
            row.getColumnIndex(column)
            true
        } catch (e: Exception) {
            false
        }
    }
}
