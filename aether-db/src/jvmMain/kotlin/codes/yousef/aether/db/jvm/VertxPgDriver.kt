package codes.yousef.aether.db.jvm

import codes.yousef.aether.db.*
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.pgclient.PgConnectOptions
import io.vertx.pgclient.PgPool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/**
 * JVM implementation of DatabaseDriver using Vert.x Reactive PostgreSQL Client.
 * Bridges Vert.x Future API to Kotlin Coroutines.
 */
class VertxPgDriver(
    private val client: SqlClient
) : DatabaseDriver {

    override suspend fun executeQuery(query: QueryAST): List<Row> {
        val translated = SqlTranslator.translate(query)
        val tuple = Tuple.from(translated.params)

        try {
            val rowSet = client.preparedQuery(translated.sql)
                .execute(tuple)
                .coAwait()

            return rowSet.map { vertxRow ->
                VertxRow(vertxRow)
            }
        } catch (e: Exception) {
            throw DatabaseException("Failed to execute query: ${translated.sql}", e)
        }
    }

    override suspend fun executeUpdate(query: QueryAST): Int {
        val translated = SqlTranslator.translate(query)
        val tuple = Tuple.from(translated.params)

        try {
            val rowSet = client.preparedQuery(translated.sql)
                .execute(tuple)
                .coAwait()

            return rowSet.rowCount()
        } catch (e: Exception) {
            throw DatabaseException("Failed to execute update: ${translated.sql}", e)
        }
    }

    override suspend fun executeDDL(query: QueryAST) {
        val translated = SqlTranslator.translate(query)

        try {
            client.query(translated.sql)
                .execute()
                .coAwait()
        } catch (e: Exception) {
            throw DatabaseException("Failed to execute DDL: ${translated.sql}", e)
        }
    }

    override suspend fun close() {
        try {
            client.close().coAwait()
        } catch (e: Exception) {
            throw DatabaseException("Failed to close database connection", e)
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
            vertx: Vertx = Vertx.vertx()
        ): VertxPgDriver {
            val connectOptions = PgConnectOptions()
                .setPort(port)
                .setHost(host)
                .setDatabase(database)
                .setUser(user)
                .setPassword(password)

            val poolOptions = PoolOptions()
                .setMaxSize(maxPoolSize)

            val pool = PgPool.pool(vertx, connectOptions, poolOptions)
            return VertxPgDriver(pool)
        }

        /**
         * Creates a VertxPgDriver from a connection URL.
         */
        fun create(
            connectionUrl: String,
            maxPoolSize: Int = 5,
            vertx: Vertx = Vertx.vertx()
        ): VertxPgDriver {
            // Parse connection URL: postgresql://user:password@host:port/database
            val regex = Regex("""postgresql://([^:]+):([^@]+)@([^:]+):(\d+)/(.+)""")
            val matchResult = regex.matchEntire(connectionUrl)
                ?: throw DatabaseException("Invalid PostgreSQL connection URL: $connectionUrl")

            val (user, password, host, port, database) = matchResult.destructured

            return create(
                host = host,
                port = port.toInt(),
                database = database,
                user = user,
                password = password,
                maxPoolSize = maxPoolSize,
                vertx = vertx
            )
        }
    }
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
