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
     * Executes a raw SQL query.
     * Use with caution.
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
}

/**
 * Exception thrown when database operations fail.
 */
class DatabaseException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

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
