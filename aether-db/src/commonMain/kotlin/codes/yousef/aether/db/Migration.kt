package codes.yousef.aether.db

import kotlinx.coroutines.CancellationException

private const val MIGRATION_LOCK_NAME = "aether-db-migrations"

/** Runs reviewed PostgreSQL migrations under one advisory-locked transaction. */
class MigrationRunner(
    private val driver: DatabaseDriver,
    private val profile: MigrationExecutionProfile = MigrationExecutionProfile.PRODUCTION_STARTUP,
    stream: String = "application"
) {
    private val stream = stream
    private val journalTable: String
    private val resumeTable: String

    init {
        require(MIGRATION_STREAM.matches(stream)) { "Invalid migration stream" }
        journalTable = if (stream == "application") "_aether_migrations" else "_aether_migrations_$stream"
        resumeTable = if (stream == "application") {
            "_aether_nontransactional_migrations"
        } else {
            "_aether_nontransactional_migrations_$stream"
        }
    }
    private val migrations = mutableListOf<Migration>()
    private val registeredSql = mutableMapOf<Long, String>()
    private val registeredChecksums = mutableMapOf<Long, String>()

    fun register(migration: Migration) {
        if (migrations.any { it.version == migration.version }) {
            throw MigrationException(MigrationFailure.DUPLICATE_VERSION, migration.version)
        }
        capture(migration)
    }

    fun registerAll(vararg migrations: Migration) {
        val duplicate = (this.migrations + migrations).groupBy(Migration::version)
            .entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            throw MigrationException(MigrationFailure.DUPLICATE_VERSION, duplicate.key)
        }
        migrations.forEach(::capture)
    }

    suspend fun migrate(): MigrationResult {
        var active: Migration? = null
        var pendingCount = migrations.size
        return try {
            driver.withTransaction { tx ->
                lockAndInitialize(tx)
                val applied = readApplied(tx)
                pendingCount = migrations.count { it.version !in applied }
                verifyAppliedChecksums(applied)
                ensureNoPrepared(tx)
                val pending = migrations.filter { it.version !in applied }
                pending.forEach { migration ->
                    active = migration
                    requireRunnable(migration)
                    tx.executeDDL(RawQuery(sql(migration)))
                    recordMigration(tx, migration)
                }
                MigrationResult(pending.size, 0, emptyList())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val migration = active
            MigrationResult(
                applied = 0,
                pending = pendingCount,
                errors = listOf(
                    MigrationError(
                        migration?.version ?: 0,
                        migration?.description ?: "Migration validation",
                        failure
                    )
                )
            )
        }
    }

    /** Prepares or resumes nontransactional operator work without executing its SQL. */
    suspend fun prepareNonTransactional(version: Long): NonTransactionalMigrationPlan {
        requireOperator()
        val migration = migrations.singleOrNull { it.version == version }
            ?: throw MigrationException(MigrationFailure.UNKNOWN_VERSION, version)
        if (migration.transactional) {
            throw MigrationException(MigrationFailure.TRANSACTIONAL_MIGRATION, version)
        }
        if (migration.source != MigrationSource.REVIEWED) {
            throw MigrationException(MigrationFailure.UNREVIEWED_CANDIDATE, version)
        }
        val checksum = checksum(migration)
        return driver.withTransaction { tx ->
            lockAndInitialize(tx)
            ensureNoPrepared(tx, allowedVersion = version)
            val applied = readApplied(tx)
            verifyAppliedChecksums(applied)
            if (version in applied) {
                throw MigrationException(MigrationFailure.ALREADY_APPLIED, version)
            }
            val state = tx.executeQuery(
                "SELECT checksum FROM $resumeTable WHERE version = $1 FOR UPDATE",
                listOf(SqlValue.LongValue(version))
            ).singleOrNull()?.getString("checksum")
            if (state != null && state != checksum) {
                throw MigrationException(MigrationFailure.CHECKSUM_DRIFT, version)
            }
            if (state == null) {
                tx.execute(
                    "INSERT INTO $resumeTable(version, checksum, state) VALUES ($1, $2, 'prepared')",
                    listOf(SqlValue.LongValue(version), SqlValue.StringValue(checksum))
                )
            }
            tx.execute(
                """
                INSERT INTO $FENCE_TABLE(stream, version) VALUES ($1, $2)
                ON CONFLICT (stream, version) DO NOTHING
                """.trimIndent(),
                listOf(SqlValue.StringValue(stream), SqlValue.LongValue(version))
            )
            NonTransactionalMigrationPlan(version, checksum, sql(migration))
        }
    }

    suspend fun completeNonTransactional(
        plan: NonTransactionalMigrationPlan,
        schemaVerified: Boolean
    ): MigrationResult {
        requireOperator()
        if (!schemaVerified) {
            throw MigrationException(MigrationFailure.SCHEMA_NOT_VERIFIED, plan.version)
        }
        val migration = migrations.singleOrNull { it.version == plan.version }
            ?: throw MigrationException(MigrationFailure.UNKNOWN_VERSION, plan.version)
        if (
            migration.transactional ||
            checksum(migration) != plan.checksum ||
            plan.sql.encodeToByteArray().sha256Hex() != plan.checksum
        ) {
            throw MigrationException(MigrationFailure.CHECKSUM_DRIFT, plan.version)
        }
        if (migration.source != MigrationSource.REVIEWED) {
            throw MigrationException(MigrationFailure.UNREVIEWED_CANDIDATE, plan.version)
        }
        return driver.withTransaction { tx ->
            lockAndInitialize(tx)
            ensureNoPrepared(tx, allowedVersion = plan.version)
            val applied = readApplied(tx)
            verifyAppliedChecksums(applied)
            if (plan.version in applied) {
                throw MigrationException(MigrationFailure.ALREADY_APPLIED, plan.version)
            }
            val resume = tx.executeQuery(
                "SELECT checksum FROM $resumeTable WHERE version = $1 AND state = 'prepared' FOR UPDATE",
                listOf(SqlValue.LongValue(plan.version))
            ).singleOrNull()?.getString("checksum")
            if (resume != plan.checksum) {
                throw MigrationException(MigrationFailure.RESUME_STATE_MISSING, plan.version)
            }
            recordMigration(tx, migration)
            tx.execute(
                "DELETE FROM $resumeTable WHERE version = $1",
                listOf(SqlValue.LongValue(plan.version))
            )
            tx.execute(
                "DELETE FROM $FENCE_TABLE WHERE stream = $1 AND version = $2",
                listOf(SqlValue.StringValue(stream), SqlValue.LongValue(plan.version))
            )
            MigrationResult(1, 0, emptyList())
        }
    }

    suspend fun rollback(): MigrationResult {
        requireOperator()
        return try {
            driver.withTransaction { tx ->
                lockAndInitialize(tx)
                ensureNoPrepared(tx)
                val applied = readApplied(tx)
                verifyAppliedChecksums(applied)
                val version = applied.keys.maxOrNull()
                    ?: return@withTransaction MigrationResult(0, 0, emptyList())
                val migration = migrations.singleOrNull { it.version == version }
                    ?: throw MigrationException(MigrationFailure.UNKNOWN_VERSION, version)
                val downSql = migration.down()
                    ?: throw MigrationException(MigrationFailure.MISSING_DOWN_SQL, version)
                tx.executeDDL(RawQuery(downSql))
                tx.execute(
                    "DELETE FROM $journalTable WHERE version = $1",
                    listOf(SqlValue.LongValue(version))
                )
                MigrationResult(1, 0, emptyList())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            MigrationResult(0, 0, listOf(MigrationError(0, "Operator rollback", failure)))
        }
    }

    suspend fun reset(): MigrationResult {
        requireOperator()
        return try {
            driver.withTransaction { tx ->
                lockAndInitialize(tx)
                ensureNoPrepared(tx)
                val applied = readApplied(tx)
                verifyAppliedChecksums(applied)
                applied.keys.sortedDescending().forEach { version ->
                    val migration = migrations.single { it.version == version }
                    val downSql = migration.down()
                        ?: throw MigrationException(MigrationFailure.MISSING_DOWN_SQL, version)
                    tx.executeDDL(RawQuery(downSql))
                    tx.execute(
                        "DELETE FROM $journalTable WHERE version = $1",
                        listOf(SqlValue.LongValue(version))
                    )
                }
                MigrationResult(applied.size, 0, emptyList())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            MigrationResult(0, 0, listOf(MigrationError(0, "Operator reset", failure)))
        }
    }

    suspend fun status(): MigrationStatus = driver.withTransaction { tx ->
        lockAndInitialize(tx)
        val applied = readApplied(tx)
        verifyAppliedChecksums(applied)
        MigrationStatus(
            applied = migrations.filter { it.version in applied },
            pending = migrations.filter { it.version !in applied },
            currentVersion = applied.keys.maxOrNull()
        )
    }

    private fun capture(migration: Migration) {
        val sql = migration.up()
        migrations.add(migration)
        migrations.sortBy(Migration::version)
        registeredSql[migration.version] = sql
        registeredChecksums[migration.version] = sql.encodeToByteArray().sha256Hex()
    }

    private fun requireRunnable(migration: Migration) {
        if (migration.source != MigrationSource.REVIEWED) {
            throw MigrationException(MigrationFailure.UNREVIEWED_CANDIDATE, migration.version)
        }
        if (!migration.transactional) {
            throw MigrationException(MigrationFailure.NONTRANSACTIONAL_REQUIRES_OPERATOR, migration.version)
        }
        if (migration.compatibility == MigrationCompatibility.CONTRACT && migration.retirementGate.isNullOrBlank()) {
            throw MigrationException(MigrationFailure.RETIREMENT_GATE_REQUIRED, migration.version)
        }
    }

    private fun requireOperator() {
        if (profile != MigrationExecutionProfile.OPERATOR) {
            throw MigrationException(MigrationFailure.DESTRUCTIVE_OPERATION_FORBIDDEN)
        }
    }

    private suspend fun lockAndInitialize(tx: DatabaseDriver) {
        tx.executeQuery(
            "SELECT pg_advisory_xact_lock(hashtext($1))",
            listOf(SqlValue.StringValue(MIGRATION_LOCK_NAME))
        )
        tx.executeDDL(RawQuery(journalSql()))
        val journalColumns = tx.getColumns(journalTable).associateBy(ColumnDefinition::name)
        if ("applied_at" !in journalColumns) {
            throw MigrationException(MigrationFailure.JOURNAL_UPGRADE_REQUIRED)
        }
        if (journalColumns["checksum"]?.nullable != false) {
            upgradeLegacyJournal(tx, checksumColumnExists = "checksum" in journalColumns)
        }
        tx.execute(
            """
            INSERT INTO $FENCE_TABLE(stream, version)
            SELECT $1, version FROM $resumeTable WHERE state = 'prepared'
            ON CONFLICT (stream, version) DO NOTHING
            """.trimIndent(),
            listOf(SqlValue.StringValue(stream))
        )
    }

    private suspend fun upgradeLegacyJournal(tx: DatabaseDriver, checksumColumnExists: Boolean) {
        if (!checksumColumnExists) {
            tx.executeDDL(RawQuery("ALTER TABLE $journalTable ADD COLUMN checksum CHAR(64)"))
        }
        val rows = tx.executeQuery(
            "SELECT version, checksum FROM $journalTable ORDER BY version",
            emptyList()
        )
        rows.forEach { row ->
            val version = row.getLong("version")
                ?: throw MigrationException(MigrationFailure.INVALID_JOURNAL)
            if (row.getString("checksum") == null) {
                val migration = migrations.singleOrNull { it.version == version }
                    ?.takeIf { it.source == MigrationSource.REVIEWED }
                    ?: throw MigrationException(MigrationFailure.JOURNAL_UPGRADE_REQUIRED, version)
                tx.execute(
                    "UPDATE $journalTable SET checksum = $1 WHERE version = $2 AND checksum IS NULL",
                    listOf(SqlValue.StringValue(checksum(migration)), SqlValue.LongValue(version))
                )
            }
        }
        tx.executeDDL(RawQuery("ALTER TABLE $journalTable ALTER COLUMN checksum SET NOT NULL"))
    }

    private suspend fun ensureNoPrepared(tx: DatabaseDriver, allowedVersion: Long? = null) {
        val pending = tx.executeQuery(
            "SELECT stream, version FROM $FENCE_TABLE ORDER BY stream, version",
            emptyList()
        )
        val conflicting = pending.firstOrNull { row ->
            row.getString("stream") != stream || row.getLong("version") != allowedVersion
        }
        if (conflicting != null) {
            throw MigrationException(
                MigrationFailure.OPERATOR_WORK_PENDING,
                conflicting.getLong("version")
            )
        }
    }

    private suspend fun readApplied(tx: DatabaseDriver): Map<Long, String> =
        tx.executeQuery(
            "SELECT version, checksum FROM $journalTable ORDER BY version",
            emptyList()
        ).associate { row ->
            val version = row.getLong("version")
                ?: throw MigrationException(MigrationFailure.INVALID_JOURNAL)
            val checksum = row.getString("checksum")
                ?: throw MigrationException(MigrationFailure.INVALID_JOURNAL, version)
            version to checksum
        }

    private fun verifyAppliedChecksums(applied: Map<Long, String>) {
        val registered = migrations.associateBy(Migration::version)
        applied.forEach { (version, recorded) ->
            val migration = registered[version]
                ?: throw MigrationException(MigrationFailure.UNKNOWN_APPLIED_VERSION, version)
            if (recorded != checksum(migration)) {
                throw MigrationException(MigrationFailure.CHECKSUM_DRIFT, version)
            }
        }
    }

    private suspend fun recordMigration(tx: DatabaseDriver, migration: Migration) {
        tx.execute(
            "INSERT INTO $journalTable(version, description, checksum) VALUES ($1, $2, $3)",
            listOf(
                SqlValue.LongValue(migration.version),
                SqlValue.StringValue(migration.description),
                SqlValue.StringValue(checksum(migration))
            )
        )
    }

    private fun sql(migration: Migration): String = registeredSql.getValue(migration.version)
    private fun checksum(migration: Migration): String = registeredChecksums.getValue(migration.version)

    private fun journalSql(): String = """
        CREATE TABLE IF NOT EXISTS $journalTable (
            version BIGINT PRIMARY KEY,
            description VARCHAR(255) NOT NULL,
            checksum CHAR(64) NOT NULL,
            applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS $resumeTable (
            version BIGINT PRIMARY KEY,
            checksum CHAR(64) NOT NULL,
            state VARCHAR(32) NOT NULL,
            prepared_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS $FENCE_TABLE (
            stream VARCHAR(32) NOT NULL,
            version BIGINT NOT NULL,
            prepared_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY(stream, version)
        )
    """.trimIndent()

    private companion object {
        val MIGRATION_STREAM = Regex("[a-z][a-z0-9_]{0,26}")
        const val FENCE_TABLE = "_aether_migration_fence"
    }
}

enum class MigrationExecutionProfile { PRODUCTION_STARTUP, OPERATOR }
enum class MigrationSource { REVIEWED, GENERATED_CANDIDATE }
enum class MigrationCompatibility { EXPAND, CONTRACT }

enum class MigrationFailure {
    DUPLICATE_VERSION,
    CHECKSUM_DRIFT,
    INVALID_JOURNAL,
    UNKNOWN_APPLIED_VERSION,
    JOURNAL_UPGRADE_REQUIRED,
    UNREVIEWED_CANDIDATE,
    NONTRANSACTIONAL_REQUIRES_OPERATOR,
    DESTRUCTIVE_OPERATION_FORBIDDEN,
    MISSING_DOWN_SQL,
    OPERATOR_WORK_PENDING,
    UNKNOWN_VERSION,
    TRANSACTIONAL_MIGRATION,
    ALREADY_APPLIED,
    SCHEMA_NOT_VERIFIED,
    RESUME_STATE_MISSING,
    RETIREMENT_GATE_REQUIRED
}

class MigrationException(
    val failure: MigrationFailure,
    val version: Long? = null
) : DatabaseException(buildString {
    append("Migration failed (")
    append(failure.name)
    if (version != null) append("; version ").append(version)
    append(')')
})

data class NonTransactionalMigrationPlan(val version: Long, val checksum: String, val sql: String)

interface Migration {
    val version: Long
    val description: String
    val source: MigrationSource get() = MigrationSource.REVIEWED
    val transactional: Boolean get() = true
    val compatibility: MigrationCompatibility get() = MigrationCompatibility.EXPAND
    val retirementGate: String? get() = null
    fun up(): String
    fun down(): String? = null
    val checksum: String get() = up().encodeToByteArray().sha256Hex()
}

data class SimpleMigration(
    override val version: Long,
    override val description: String,
    private val upSql: String,
    private val downSql: String? = null,
    override val source: MigrationSource = MigrationSource.REVIEWED,
    override val transactional: Boolean = true,
    override val compatibility: MigrationCompatibility = MigrationCompatibility.EXPAND,
    override val retirementGate: String? = null
) : Migration {
    override fun up(): String = upSql
    override fun down(): String? = downSql
}

data class MigrationResult(val applied: Int, val pending: Int, val errors: List<MigrationError>) {
    val success: Boolean get() = errors.isEmpty()
}

data class MigrationError(val version: Long, val description: String, val exception: Exception)

data class MigrationStatus(
    val applied: List<Migration>,
    val pending: List<Migration>,
    val currentVersion: Long?
)

@kotlinx.serialization.Serializable
data class RawQuery(val sql: String) : QueryAST()

fun migration(version: Long, description: String, block: MigrationBuilder.() -> Unit): Migration =
    MigrationBuilder(version, description).apply(block).build()

class MigrationBuilder(private val version: Long, private val description: String) {
    private var upSql: String = ""
    private var downSql: String? = null
    private var transactional: Boolean = true
    private var compatibility: MigrationCompatibility = MigrationCompatibility.EXPAND
    private var retirementGate: String? = null

    fun up(sql: String) {
        upSql = sql.trimIndent()
    }

    fun down(sql: String) {
        downSql = sql.trimIndent()
    }

    fun nonTransactional() {
        transactional = false
    }

    fun contract(retirementGate: String) {
        compatibility = MigrationCompatibility.CONTRACT
        this.retirementGate = retirementGate
    }

    fun build(): Migration = SimpleMigration(
        version,
        description,
        upSql,
        downSql,
        transactional = transactional,
        compatibility = compatibility,
        retirementGate = retirementGate
    )
}
