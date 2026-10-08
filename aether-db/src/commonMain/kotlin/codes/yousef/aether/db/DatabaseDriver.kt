package codes.yousef.aether.db

/**
 * Platform-agnostic database driver interface.
 * Implementations exist for JVM (Vert.x PostgreSQL) and Wasm (HTTP-based).
 */
interface DatabaseDriver {
    /**
     * Executes a query that returns rows (SELECT).
     */
    suspend fun executeQuery(query: QueryAST): List<Row>

    /**
     * Executes SQL without a value-binding contract. Only fixed, trusted statements are safe.
     */
    @Deprecated(
        message = "Raw SQL cannot bind untrusted values; use executeQuery(sql, params)",
        replaceWith = ReplaceWith("executeQuery(sql, emptyList())")
    )
    suspend fun executeQueryRaw(sql: String): List<Row>

    /**
     * Executes fixed SQL with bound values and returns rows. Implementations without this
     * capability reject the call rather than interpolating values.
     */
    suspend fun executeQuery(sql: String, params: List<SqlValue>): List<Row> {
        throw DatabaseFeatureUnsupportedException(DatabaseFeature.BOUND_QUERY)
    }

    /**
     * Executes a query that modifies data (INSERT, UPDATE, DELETE).
     * Returns the number of affected rows.
     */
    suspend fun executeUpdate(query: QueryAST): Int

    /**
     * Executes a DDL statement (CREATE TABLE, etc.).
     */
    suspend fun executeDDL(query: QueryAST)

    /**
     * Returns a list of all table names in the database.
     */
    suspend fun getTables(): List<String>

    /**
     * Returns the column definitions for the specified table.
     */
    suspend fun getColumns(table: String): List<ColumnDefinition>

    /**
     * Executes fixed SQL with bound values and returns the number of affected rows.
     */
    suspend fun execute(sql: String, params: List<SqlValue> = emptyList()): Int

    /**
     * Closes the database connection and releases resources.
     */
    suspend fun close()
}

/**
 * Represents a single row in a result set.
 * Provides typed accessors for column values.
 */
interface Row {
    /**
     * Gets a string value from the specified column.
     * Returns null if the value is NULL.
     */
    fun getString(column: String): String?

    /**
     * Gets an integer value from the specified column.
     * Returns null if the value is NULL.
     */
    fun getInt(column: String): Int?

    /**
     * Gets a long value from the specified column.
     * Returns null if the value is NULL.
     */
    fun getLong(column: String): Long?

    /**
     * Gets a double value from the specified column.
     * Returns null if the value is NULL.
     */
    fun getDouble(column: String): Double?

    /**
     * Gets a boolean value from the specified column.
     * Returns null if the value is NULL.
     */
    fun getBoolean(column: String): Boolean?

    /** Gets a PostgreSQL UUID in canonical text form, or null for SQL NULL/missing columns. */
    fun getUuid(column: String): String? {
        throw DatabaseFeatureUnsupportedException(DatabaseFeature.NATIVE_UUID)
    }

    /** Gets bytea bytes, or null for SQL NULL/missing columns. */
    fun getBytes(column: String): ByteArray? {
        throw DatabaseFeatureUnsupportedException(DatabaseFeature.NATIVE_BYTES)
    }

    /** Gets a UTC timestamptz value, or null for SQL NULL/missing columns. */
    fun getUtcTimestamp(column: String): kotlin.time.Instant? {
        throw DatabaseFeatureUnsupportedException(DatabaseFeature.NATIVE_TIMESTAMP)
    }

    /**
     * Gets a value from the specified column as an Any?.
     * Returns null if the value is NULL.
     */
    fun getValue(column: String): Any?

    /**
     * Gets the column names in this row.
     */
    fun getColumnNames(): List<String>

    /**
     * Checks if the specified column exists in this row.
     */
    fun hasColumn(column: String): Boolean

    /** True only when [column] exists and contains SQL NULL. */
    fun isNull(column: String): Boolean = hasColumn(column) && getValue(column) == null
}

/**
 * Base exception for database operations. Public messages must not include SQL, parameters or
 * connection details.
 */
open class DatabaseException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

enum class DatabaseFeature {
    BOUND_QUERY,
    NATIVE_UUID,
    NATIVE_BYTES,
    NATIVE_TIMESTAMP
}

class DatabaseFeatureUnsupportedException(
    val feature: DatabaseFeature
) : DatabaseException("Database feature is unsupported (${feature.name})")

enum class DatabaseFailureCategory {
    UNIQUE_VIOLATION,
    FOREIGN_KEY_VIOLATION,
    CHECK_VIOLATION,
    SERIALIZATION_CONFLICT,
    DEADLOCK,
    OTHER
}

enum class RowAccessFailure {
    INVALID_TYPE
}

class DatabaseRowAccessException(
    val failure: RowAccessFailure,
    val expectedType: String,
    cause: Throwable? = null
) : DatabaseException(
    "Database row access failed (${failure.name}; expected $expectedType)",
    cause
)

enum class DatabaseOperation {
    QUERY,
    UPDATE,
    DDL,
    METADATA,
    CLOSE
}

/** Safe database failure surface: operation type and validated SQLSTATE only. */
class DatabaseOperationException(
    val operation: DatabaseOperation,
    val sqlState: String? = null,
    cause: Throwable? = null
) : DatabaseException(
    buildString {
        append("Database ")
        append(operation.name.lowercase())
        append(" failed")
        if (sqlState != null) {
            append(" (SQLSTATE ")
            append(sqlState)
            append(')')
        }
    },
    cause
) {
    val category: DatabaseFailureCategory = when (sqlState) {
        "23505" -> DatabaseFailureCategory.UNIQUE_VIOLATION
        "23503" -> DatabaseFailureCategory.FOREIGN_KEY_VIOLATION
        "23514" -> DatabaseFailureCategory.CHECK_VIOLATION
        "40001" -> DatabaseFailureCategory.SERIALIZATION_CONFLICT
        "40P01" -> DatabaseFailureCategory.DEADLOCK
        else -> DatabaseFailureCategory.OTHER
    }

    val retryable: Boolean
        get() = category == DatabaseFailureCategory.SERIALIZATION_CONFLICT ||
            category == DatabaseFailureCategory.DEADLOCK
}

/**
 * Global database driver instance.
 * This should be set during application initialization.
 */
object DatabaseDriverRegistry {
    private var _driver: DatabaseDriver? = null

    var driver: DatabaseDriver
        get() = _driver ?: throw DatabaseException("DatabaseDriver not initialized. Call DatabaseDriverRegistry.initialize() first.")
        set(value) {
            _driver = value
        }

    fun initialize(driver: DatabaseDriver) {
        this.driver = driver
    }

    fun isInitialized(): Boolean = _driver != null
}
