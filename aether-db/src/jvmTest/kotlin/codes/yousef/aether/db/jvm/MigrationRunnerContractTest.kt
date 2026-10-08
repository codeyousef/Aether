package codes.yousef.aether.db.jvm

import codes.yousef.aether.db.MigrationExecutionProfile
import codes.yousef.aether.db.MigrationException
import codes.yousef.aether.db.MigrationFailure
import codes.yousef.aether.db.MigrationRunner
import codes.yousef.aether.db.MigrationSource
import codes.yousef.aether.db.RawQuery
import codes.yousef.aether.db.SimpleMigration
import codes.yousef.aether.db.SqlValue
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationRunnerContractTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var vertx: Vertx
    private lateinit var driver: VertxPgDriver

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "AE-T06 requires PostgreSQL")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        vertx = Vertx.vertx()
        driver = VertxPgDriver.create(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password,
            maxPoolSize = 4,
            vertx = vertx
        )
    }

    @AfterAll
    fun stopPostgres() {
        if (::driver.isInitialized) runBlocking { driver.close() }
        if (::vertx.isInitialized) runBlocking { vertx.close().coAwait() }
        if (::postgres.isInitialized) postgres.stop()
    }

    @BeforeEach
    fun resetSchema() = runBlocking {
        driver.executeDDL(
            RawQuery(
                """
                DROP TABLE IF EXISTS _aether_migration_fence;
                DROP TABLE IF EXISTS _aether_nontransactional_migrations;
                DROP TABLE IF EXISTS _aether_migrations;
                DROP TABLE IF EXISTS ae_concurrent;
                DROP TABLE IF EXISTS ae_failed;
                DROP TABLE IF EXISTS ae_restarted;
                DROP TABLE IF EXISTS ae_drift_should_not_exist;
                DROP TABLE IF EXISTS ae_ciphertext;
                DROP TABLE IF EXISTS ae_metadata;
                DROP TABLE IF EXISTS _aether_nontransactional_migrations_tasks;
                DROP TABLE IF EXISTS _aether_migrations_tasks;
                DROP TABLE IF EXISTS ae_operator_work;
                DROP TABLE IF EXISTS ae_waiting;
                DROP TABLE IF EXISTS ae_application;
                DROP TABLE IF EXISTS ae_task_stream;
                DROP TABLE IF EXISTS ae_reset_second;
                DROP TABLE IF EXISTS ae_reset_first;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `legacy journal is atomically upgraded from registered reviewed SQL`() = runBlocking {
        val migration = SimpleMigration(1, "already applied", "CREATE TABLE ae_failed(id BIGINT);")
        driver.executeDDL(
            RawQuery(
                """
                CREATE TABLE _aether_migrations(
                    version BIGINT PRIMARY KEY,
                    description TEXT NOT NULL,
                    applied_at TIMESTAMP
                );
                INSERT INTO _aether_migrations VALUES (1, 'already applied', CURRENT_TIMESTAMP);
                """.trimIndent()
            )
        )
        val result = MigrationRunner(driver).apply { register(migration) }.migrate()

        assertTrue(result.success)
        assertEquals(0, result.applied)
        assertEquals(migration.checksum, scalarString("SELECT checksum AS value FROM _aether_migrations"))
        assertNull(regclass("ae_failed"))
    }
    @Test
    fun `legacy journal never backfills from an unreviewed candidate`() = runBlocking {
        driver.executeDDL(
            RawQuery(
                """
                CREATE TABLE _aether_migrations(
                    version BIGINT PRIMARY KEY,
                    description TEXT NOT NULL,
                    applied_at TIMESTAMP
                );
                INSERT INTO _aether_migrations VALUES (1, 'historical', CURRENT_TIMESTAMP);
                """.trimIndent()
            )
        )
        val result = MigrationRunner(driver).apply {
            register(
                SimpleMigration(
                    1,
                    "candidate",
                    "SELECT 1;",
                    source = MigrationSource.GENERATED_CANDIDATE
                )
            )
        }.migrate()

        assertEquals(
            MigrationFailure.JOURNAL_UPGRADE_REQUIRED,
            assertIs<MigrationException>(result.errors.single().exception).failure
        )
        assertEquals(
            0L,
            scalarLong(
                "SELECT count(*) AS value FROM information_schema.columns " +
                    "WHERE table_name = '_aether_migrations' AND column_name = 'checksum'"
            )
        )
    }


    @Test
    fun `two runners serialize and apply one migration once`() = runBlocking {
        val migration = SimpleMigration(
            1,
            "concurrent",
            "CREATE TABLE ae_concurrent(id BIGINT PRIMARY KEY); INSERT INTO ae_concurrent VALUES (1);"
        )
        val runners = List(2) { MigrationRunner(driver).apply { register(migration) } }

        val results = coroutineScope { runners.map { runner -> async { runner.migrate() } }.awaitAll() }

        assertEquals(1, results.sumOf { it.applied })
        assertTrue(results.all { it.success })
        assertEquals(1L, scalarLong("SELECT count(*) AS value FROM ae_concurrent"))
        assertEquals(1L, scalarLong("SELECT count(*) AS value FROM _aether_migrations"))
    }

    @Test
    fun `DDL failure rolls back and restart resumes without a journal lie`() = runBlocking {
        val failed = MigrationRunner(driver).apply {
            register(SimpleMigration(2, "fails", "CREATE TABLE ae_failed(id BIGINT); SELECT 1 / 0;"))
        }.migrate()

        assertFalse(failed.success)
        assertEquals(1, failed.pending)
        assertNull(regclass("ae_failed"))
        assertNull(regclass("_aether_migrations"))

        val restarted = MigrationRunner(driver).apply {
            register(SimpleMigration(2, "restarted", "CREATE TABLE ae_restarted(id BIGINT);"))
        }.migrate()
        assertTrue(restarted.success)
        assertEquals("ae_restarted", regclass("ae_restarted"))
        assertEquals(1L, scalarLong("SELECT count(*) AS value FROM _aether_migrations"))
    }

    @Test
    fun `checksum drift and generated candidates are rejected before their DDL`() = runBlocking {
        val original = SimpleMigration(3, "original", "CREATE TABLE ae_concurrent(id BIGINT);")
        assertTrue(MigrationRunner(driver).apply { register(original) }.migrate().success)

        val drift = MigrationRunner(driver).apply {
            register(SimpleMigration(3, "changed", "CREATE TABLE ae_drift_should_not_exist(id BIGINT);"))
        }.migrate()
        assertEquals(
            MigrationFailure.CHECKSUM_DRIFT,
            assertIs<MigrationException>(drift.errors.single().exception).failure
        )
        assertEquals(0, drift.pending)
        assertNull(regclass("ae_drift_should_not_exist"))

        val candidate = MigrationRunner(driver).apply {
            register(original)
            register(
                SimpleMigration(
                    4,
                    "generated",
                    "DROP TABLE ae_concurrent",
                    source = MigrationSource.GENERATED_CANDIDATE
                )
            )
        }.migrate()
        assertEquals(
            MigrationFailure.UNREVIEWED_CANDIDATE,
            assertIs<MigrationException>(candidate.errors.single().exception).failure
        )
        assertEquals(1, candidate.pending)
        assertEquals("ae_concurrent", regclass("ae_concurrent"))
    }

    @Test
    fun `production cannot roll back and missing down SQL keeps journal state`() = runBlocking {
        val migration = SimpleMigration(5, "forward only", "CREATE TABLE ae_concurrent(id BIGINT);")
        val startup = MigrationRunner(driver).apply { register(migration) }
        assertTrue(startup.migrate().success)
        val forbidden = assertFailsWith<MigrationException> { startup.rollback() }
        assertEquals(MigrationFailure.DESTRUCTIVE_OPERATION_FORBIDDEN, forbidden.failure)

        val operator = MigrationRunner(driver, MigrationExecutionProfile.OPERATOR).apply { register(migration) }
        val rollback = operator.rollback()
        assertEquals(MigrationFailure.MISSING_DOWN_SQL, assertIs<MigrationException>(rollback.errors.single().exception).failure)
        assertEquals(1L, scalarLong("SELECT count(*) AS value FROM _aether_migrations"))
        assertEquals("ae_concurrent", regclass("ae_concurrent"))
    }

    @Test
    fun `nontransactional migration requires explicit resumable operator completion`() = runBlocking {
        val historical = SimpleMigration(6, "historical", "CREATE TABLE ae_restarted(id BIGINT);")
        assertTrue(MigrationRunner(driver).apply { register(historical) }.migrate().success)
        val migration = SimpleMigration(
            7,
            "operator procedure",
            "CREATE TABLE ae_concurrent(id BIGINT PRIMARY KEY);",
            transactional = false
        )
        val startup = MigrationRunner(driver).apply {
            register(historical)
            register(migration)
        }
        val startupFailure = startup.migrate()
        assertEquals(
            MigrationFailure.NONTRANSACTIONAL_REQUIRES_OPERATOR,
            assertIs<MigrationException>(startupFailure.errors.single().exception).failure
        )
        assertNull(regclass("ae_concurrent"))

        val operator = MigrationRunner(driver, MigrationExecutionProfile.OPERATOR).apply {
            register(historical)
            register(migration)
        }
        val firstPlan = operator.prepareNonTransactional(7)
        val resumedPlan = operator.prepareNonTransactional(7)
        assertEquals(firstPlan, resumedPlan)
        assertEquals(1L, scalarLong("SELECT count(*) AS value FROM _aether_migrations"))
        assertEquals(1L, scalarLong("SELECT count(*) AS value FROM _aether_nontransactional_migrations"))

        val drifted = MigrationRunner(driver, MigrationExecutionProfile.OPERATOR).apply {
            register(SimpleMigration(6, "changed", "SELECT 1;"))
            register(migration)
        }
        val drift = assertFailsWith<MigrationException> {
            drifted.completeNonTransactional(firstPlan, schemaVerified = true)
        }
        assertEquals(MigrationFailure.CHECKSUM_DRIFT, drift.failure)

        driver.executeDDL(RawQuery(firstPlan.sql))
        val unverified = assertFailsWith<MigrationException> {
            operator.completeNonTransactional(firstPlan, schemaVerified = false)
        }
        assertEquals(MigrationFailure.SCHEMA_NOT_VERIFIED, unverified.failure)
        assertEquals(1L, scalarLong("SELECT count(*) AS value FROM _aether_migrations"))

        assertTrue(operator.completeNonTransactional(firstPlan, schemaVerified = true).success)
        assertEquals(2L, scalarLong("SELECT count(*) AS value FROM _aether_migrations"))
        assertEquals(0L, scalarLong("SELECT count(*) AS value FROM _aether_nontransactional_migrations"))
    }
    @Test
    fun `expand migration preserves ciphertext and existing metadata for old readers`() = runBlocking {
        driver.executeDDL(
            RawQuery(
                """
                CREATE TABLE ae_ciphertext(id BIGINT PRIMARY KEY, payload BYTEA NOT NULL);

                CREATE TABLE ae_metadata(
                    id BIGINT PRIMARY KEY,
                    object_id UUID NOT NULL UNIQUE,
                    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0)
                );
                CREATE INDEX ae_metadata_size_idx ON ae_metadata(size_bytes);
                """.trimIndent()
            )
        )
        val payload = byteArrayOf(0, 1, -1, 42)
        driver.execute(
            "INSERT INTO ae_ciphertext(id, payload) VALUES ($1, $2)",
            listOf(SqlValue.LongValue(1), SqlValue.ByteArrayValue(payload))
        )
        val runner = MigrationRunner(driver).apply {
            register(
                SimpleMigration(
                    6,
                    "expand",
                    "ALTER TABLE ae_ciphertext ADD COLUMN media_type TEXT; " +
                        "ALTER TABLE ae_metadata ADD COLUMN created_at TIMESTAMPTZ;"
                )
            )
        }

        assertTrue(runner.migrate().success)
        val oldReader = driver.executeQuery(
            "SELECT id, payload FROM ae_ciphertext WHERE id = $1",
            listOf(SqlValue.LongValue(1))
        ).single()
        assertEquals(1L, oldReader.getLong("id"))
        assertContentEquals(payload, oldReader.getBytes("payload"))
        assertEquals(3L, scalarLong("SELECT count(*) AS value FROM pg_constraint WHERE conrelid = 'ae_metadata'::regclass"))
        assertEquals(1L, scalarLong("SELECT count(*) AS value FROM pg_indexes WHERE indexname = 'ae_metadata_size_idx'"))
        assertEquals("uuid", scalarString("SELECT data_type AS value FROM information_schema.columns WHERE table_name = 'ae_metadata' AND column_name = 'object_id'"))
    }

    @Test
    fun `prepared operator work blocks startup migrations`() = runBlocking {
        val operatorMigration = SimpleMigration(
            7,
            "operator work",
            "CREATE TABLE ae_operator_work(id BIGINT);",
            transactional = false
        )
        val operator = MigrationRunner(
            driver,
            MigrationExecutionProfile.OPERATOR,
            stream = "tasks"
        ).apply {
            register(operatorMigration)
        }
        operator.prepareNonTransactional(7)

        val startup = MigrationRunner(driver).apply {
            register(operatorMigration)
            register(SimpleMigration(8, "must wait", "CREATE TABLE ae_waiting(id BIGINT);"))
        }.migrate()

        assertEquals(
            MigrationFailure.OPERATOR_WORK_PENDING,
            assertIs<MigrationException>(startup.errors.single().exception).failure
        )
        assertNull(regclass("ae_waiting"))
    }

    @Test
    fun `migration streams isolate module journals`() = runBlocking {
        val application = MigrationRunner(driver).apply {
            register(SimpleMigration(1, "application", "CREATE TABLE ae_application(id BIGINT);"))
        }
        val tasks = MigrationRunner(driver, stream = "tasks").apply {
            register(SimpleMigration(1, "tasks", "CREATE TABLE ae_task_stream(id BIGINT);"))
        }

        assertTrue(application.migrate().success)
        assertTrue(tasks.migrate().success)
        assertEquals(1L, scalarLong("SELECT count(*) AS value FROM _aether_migrations"))
        assertEquals(1L, scalarLong("SELECT count(*) AS value FROM _aether_migrations_tasks"))
    }

    @Test
    fun `reset rolls back every down migration and journal change atomically`() = runBlocking {
        val runner = MigrationRunner(driver, MigrationExecutionProfile.OPERATOR).apply {
            register(SimpleMigration(10, "no rollback", "CREATE TABLE ae_reset_first(id BIGINT);"))
            register(
                SimpleMigration(
                    11,
                    "rollback",
                    "CREATE TABLE ae_reset_second(id BIGINT);",
                    downSql = "DROP TABLE ae_reset_second;"
                )
            )
        }
        assertTrue(runner.migrate().success)

        val reset = runner.reset()

        assertEquals(
            MigrationFailure.MISSING_DOWN_SQL,
            assertIs<MigrationException>(reset.errors.single().exception).failure
        )
        assertEquals("ae_reset_first", regclass("ae_reset_first"))
        assertEquals("ae_reset_second", regclass("ae_reset_second"))
        assertEquals(2L, scalarLong("SELECT count(*) AS value FROM _aether_migrations"))
    }

    private suspend fun scalarLong(sql: String): Long =
        driver.executeQuery(sql, emptyList()).single().getLong("value")!!

    private suspend fun scalarString(sql: String): String? =
        driver.executeQuery(sql, emptyList()).single().getString("value")

    private suspend fun regclass(table: String): String? =
        driver.executeQuery(
            "SELECT to_regclass($1)::text AS value",
            listOf(SqlValue.StringValue("public.$table"))
        ).single().getString("value")
}
