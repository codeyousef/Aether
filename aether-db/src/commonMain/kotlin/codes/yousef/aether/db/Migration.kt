package codes.yousef.aether.db

/**
 * Migration runner for executing database migrations.
 */
class MigrationRunner(
    private val driver: DatabaseDriver
) {
    private val migrations = mutableListOf<Migration>()

    /**
     * Register a migration.
     */
    fun register(migration: Migration) {
        migrations.add(migration)
        migrations.sortBy { it.version }
    }

    /**
     * Register multiple migrations.
     */
    fun registerAll(vararg migrations: Migration) {
        migrations.forEach { register(it) }
    }

    /**
     * Run all pending migrations.
     */
    suspend fun migrate(): MigrationResult {
        // Ensure migrations table exists
        createMigrationsTable()

        // Get applied migrations
        val applied = getAppliedMigrations()

        // Find pending migrations
        val pending = migrations.filter { it.version !in applied }

        if (pending.isEmpty()) {
            return MigrationResult(
                applied = 0,
                pending = 0,
                errors = emptyList()
            )
        }

        val errors = mutableListOf<MigrationError>()
        var appliedCount = 0

        for (migration in pending) {
            try {
                // Run the migration
                driver.executeDDL(RawQuery(migration.up()))
                
                // Record the migration
                recordMigration(migration)
                
                appliedCount++
            } catch (e: Exception) {
                errors.add(MigrationError(migration.version, migration.description, e))
                break // Stop on first error
            }
        }

        return MigrationResult(
            applied = appliedCount,
            pending = pending.size - appliedCount,
            errors = errors
        )
    }

    /**
     * Rollback the last migration.
     */
    suspend fun rollback(): MigrationResult {
        val applied = getAppliedMigrations().sortedDescending()
        if (applied.isEmpty()) {
            return MigrationResult(0, 0, emptyList())
        }

        val lastVersion = applied.first()
        val migration = migrations.find { it.version == lastVersion }
            ?: return MigrationResult(0, 0, listOf(
                MigrationError(lastVersion, "Unknown", Exception("Migration not found"))
            ))

        try {
            val downSql = migration.down()
            if (downSql != null) {
                driver.executeDDL(RawQuery(downSql))
            }
            
            removeMigration(migration)
            
            return MigrationResult(1, 0, emptyList())
        } catch (e: Exception) {
            return MigrationResult(0, 0, listOf(
                MigrationError(migration.version, migration.description, e)
            ))
        }
    }

    /**
     * Rollback all migrations.
     */
    suspend fun reset(): MigrationResult {
        var totalRolledBack = 0
        val errors = mutableListOf<MigrationError>()

        while (true) {
            val result = rollback()
            if (result.applied == 0) {
                errors.addAll(result.errors)
                break
            }
            totalRolledBack += result.applied
        }

        return MigrationResult(totalRolledBack, 0, errors)
    }

    /**
     * Get the current migration status.
     */
    suspend fun status(): MigrationStatus {
        createMigrationsTable()
        
        val applied = getAppliedMigrations()
        val pending = migrations.filter { it.version !in applied }

        return MigrationStatus(
            applied = migrations.filter { it.version in applied },
            pending = pending,
            currentVersion = applied.maxOrNull()
        )
    }

    private suspend fun createMigrationsTable() {
        val createTable = """
            CREATE TABLE IF NOT EXISTS _aether_migrations (
                version BIGINT PRIMARY KEY,
                description VARCHAR(255) NOT NULL,
                applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent()
        
        driver.executeDDL(RawQuery(createTable))
    }

    private suspend fun getAppliedMigrations(): Set<Long> {
        val query = SelectQuery(
            columns = listOf(Expression.ColumnRef(column = "version")),
            from = "_aether_migrations"
        )
        
        val rows = driver.executeQuery(query)
        return rows.mapNotNull { it.getLong("version") }.toSet()
    }

    private suspend fun recordMigration(migration: Migration) {
        val insert = InsertQuery(
            table = "_aether_migrations",
            columns = listOf("version", "description"),
            values = listOf(
                Expression.Literal(SqlValue.LongValue(migration.version)),
                Expression.Literal(SqlValue.StringValue(migration.description))
            )
        )
        
        driver.executeUpdate(insert)
    }

    private suspend fun removeMigration(migration: Migration) {
        val delete = DeleteQuery(
            table = "_aether_migrations",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "version"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.LongValue(migration.version))
            )
        )
        
        driver.executeUpdate(delete)
    }
}

/**
 * Represents a database migration.
 */
interface Migration {
    /**
     * Migration version (timestamp recommended).
     */
    val version: Long

    /**
     * Human-readable description.
     */
    val description: String

    /**
     * SQL to apply the migration.
     */
    fun up(): String

    /**
     * SQL to rollback the migration (optional).
     */
    fun down(): String? = null
}

/**
 * Simple migration implementation.
 */
data class SimpleMigration(
    override val version: Long,
    override val description: String,
    private val upSql: String,
    private val downSql: String? = null
) : Migration {
    override fun up(): String = upSql
    override fun down(): String? = downSql
}

/**
 * Result of a migration operation.
 */
data class MigrationResult(
    val applied: Int,
    val pending: Int,
    val errors: List<MigrationError>
) {
    val success: Boolean get() = errors.isEmpty()
}

/**
 * Error during migration.
 */
data class MigrationError(
    val version: Long,
    val description: String,
    val exception: Exception
)

/**
 * Current migration status.
 */
data class MigrationStatus(
    val applied: List<Migration>,
    val pending: List<Migration>,
    val currentVersion: Long?
)

/**
 * Raw SQL query for DDL operations.
 */
@kotlinx.serialization.Serializable
data class RawQuery(val sql: String) : QueryAST()

/**
 * DSL for creating migrations.
 */
fun migration(version: Long, description: String, block: MigrationBuilder.() -> Unit): Migration {
    return MigrationBuilder(version, description).apply(block).build()
}

/**
 * Builder for migrations.
 */
class MigrationBuilder(
    private val version: Long,
    private val description: String
) {
    private var upSql: String = ""
    private var downSql: String? = null

    fun up(sql: String) {
        upSql = sql.trimIndent()
    }

    fun down(sql: String) {
        downSql = sql.trimIndent()
    }

    fun build(): Migration = SimpleMigration(version, description, upSql, downSql)
}
