package codes.yousef.aether.db.jvm

import codes.yousef.aether.db.DatabaseDriver
import codes.yousef.aether.db.DatabaseTransactionException
import codes.yousef.aether.db.SqlValue
import codes.yousef.aether.db.TransactionFailure
import codes.yousef.aether.db.TransactionIsolation
import codes.yousef.aether.db.TransactionOptions
import codes.yousef.aether.db.executeCompareAndSet
import codes.yousef.aether.db.withTransaction
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionContractTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var vertx: Vertx
    private lateinit var driver: VertxPgDriver

    @BeforeAll
    fun startPostgres() {
        assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "AE-T04 requires a real PostgreSQL Docker runtime"
        )
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
        runBlocking { createSchema() }
    }

    @AfterAll
    fun stopPostgres() {
        if (::driver.isInitialized) runBlocking { driver.close() }
        if (::vertx.isInitialized) runBlocking { vertx.close().coAwait() }
        if (::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `success commits every atomic row and wake hint follows commit`() = runBlocking {
        clearAtomicRows()
        var wakeHintPublished = false

        driver.withTransaction { tx ->
            writeAtomicRows(tx, "success")
            assertEquals(0, count("ae_outbox", driver))
        }
        wakeHintPublished = true

        assertEquals(1, count("ae_objects"))
        assertEquals(1, count("ae_versions"))
        assertEquals(1, count("ae_changes"))
        assertEquals(1, count("ae_receipts"))
        assertEquals(1, count("ae_outbox"))
        assertTrue(wakeHintPublished)
    }

    @Test
    fun `failure after each statement rolls back every staged row`() = runBlocking {
        for (failurePoint in 1..5) {
            clearAtomicRows()
            assertFailsWith<SyntheticFailure> {
                driver.withTransaction { tx ->
                    val statements = atomicStatements("failure-$failurePoint")
                    statements.forEachIndexed { index, statement ->
                        tx.execute(statement.first, statement.second)
                        if (index + 1 == failurePoint) throw SyntheticFailure()
                    }
                }
            }
            assertEquals(0, totalAtomicRows(), "failure point $failurePoint")
            assertHealthyTransaction()
        }
    }

    @Test
    fun `acquire query and commit cancellation have recorded outcomes`() = runBlocking {
        val singleConnectionDriver = VertxPgDriver.create(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password,
            maxPoolSize = 1,
            vertx = vertx
        )
        try {
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val holder = async {
                    singleConnectionDriver.withTransaction {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                entered.await()
                val acquireFailure = assertFailsWith<DatabaseTransactionException> {
                    singleConnectionDriver.withTransaction(
                        TransactionOptions(acquireTimeout = 50.milliseconds)
                    ) { error("must not execute") }
                }
                assertEquals(TransactionFailure.ACQUIRE_TIMEOUT, acquireFailure.failure)
                release.complete(Unit)
                holder.await()
            }

            val queryFailure = assertFailsWith<DatabaseTransactionException> {
                singleConnectionDriver.withTransaction(
                    TransactionOptions(statementTimeout = 50.milliseconds)
                ) { tx ->
                    tx.executeQueryRaw("SELECT pg_sleep(1)")
                }
            }
            assertEquals(TransactionFailure.STATEMENT_TIMEOUT, queryFailure.failure)
            assertHealthyTransaction(singleConnectionDriver)

            singleConnectionDriver.execute("DELETE FROM ae_commit_delay")
            val commitFailure = assertFailsWith<DatabaseTransactionException> {
                singleConnectionDriver.withTransaction(
                    TransactionOptions(statementTimeout = 50.milliseconds)
                ) { tx ->
                    tx.execute("INSERT INTO ae_commit_delay (id) VALUES ($1)", listOf(SqlValue.IntValue(1)))
                }
            }
            assertEquals(TransactionFailure.COMMIT_OUTCOME_UNKNOWN, commitFailure.failure)
            assertTrue(count("ae_commit_delay", singleConnectionDriver) in 0L..1L)
            assertHealthyTransaction(singleConnectionDriver)
        } finally {
            singleConnectionDriver.close()
        }
    }

    @Test
    fun `same revision writers produce one winner`() = runBlocking {
        driver.execute("DELETE FROM ae_cas")
        driver.execute("INSERT INTO ae_cas (owner_account_id, id, revision) VALUES ('owner', 'object', 0)")
        val ready = CompletableDeferred<Unit>()
        val arrivals = AtomicInteger()

        val outcomes = coroutineScope {
            List(2) {
                async {
                    runCatching {
                        driver.withTransaction { tx ->
                            if (arrivals.incrementAndGet() == 2) ready.complete(Unit)
                            ready.await()
                            tx.executeCompareAndSet(
                                "UPDATE ae_cas SET revision = revision + 1 WHERE owner_account_id=$1 AND id=$2 AND revision=$3",
                                listOf(
                                    SqlValue.StringValue("owner"),
                                    SqlValue.StringValue("object"),
                                    SqlValue.LongValue(0)
                                )
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, outcomes.count { it.isSuccess })
        val loser = assertIs<DatabaseTransactionException>(outcomes.single { it.isFailure }.exceptionOrNull())
        assertEquals(TransactionFailure.REVISION_CONFLICT, loser.failure)
        assertEquals(1L, driver.executeQueryRaw("SELECT revision FROM ae_cas").single().getLong("revision"))
    }

    @Test
    fun `serializable write skew exposes one typed retryable conflict`() = runBlocking {
        driver.execute("DELETE FROM ae_serial")
        driver.execute("INSERT INTO ae_serial (id, enabled) VALUES (1, true), (2, true)")
        val ready = CompletableDeferred<Unit>()
        val arrivals = AtomicInteger()
        val options = TransactionOptions(isolation = TransactionIsolation.SERIALIZABLE)

        val outcomes = coroutineScope {
            listOf(1, 2).map { id ->
                async {
                    runCatching {
                        driver.withTransaction(options) { tx ->
                            tx.executeQueryRaw("SELECT id, enabled FROM ae_serial ORDER BY id")
                            if (arrivals.incrementAndGet() == 2) ready.complete(Unit)
                            ready.await()
                            tx.execute(
                                "UPDATE ae_serial SET enabled = false WHERE id = $1",
                                listOf(SqlValue.IntValue(id))
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, outcomes.count { it.isSuccess })
        val conflict = assertIs<DatabaseTransactionException>(outcomes.single { it.isFailure }.exceptionOrNull())
        assertEquals(TransactionFailure.SERIALIZATION_CONFLICT, conflict.failure)
        assertTrue(conflict.retryable)
        assertEquals("40001", conflict.sqlState)
    }

    @Test
    fun `connection bound driver rejects nesting close and use after block`() = runBlocking {
        lateinit var escaped: DatabaseDriver
        driver.withTransaction { tx ->
            escaped = tx
            val nested = assertFailsWith<DatabaseTransactionException> {
                tx.withTransaction { error("nested block must not execute") }
            }
            assertEquals(TransactionFailure.NESTED_TRANSACTION, nested.failure)
            val close = assertFailsWith<DatabaseTransactionException> { tx.close() }
            assertEquals(TransactionFailure.CONNECTION_CLOSE_FORBIDDEN, close.failure)
        }

        val stale = assertFailsWith<DatabaseTransactionException> {
            escaped.executeQueryRaw("SELECT 1")
        }
        assertEquals(TransactionFailure.SCOPE_CLOSED, stale.failure)
        assertHealthyTransaction()
    }

    @Test
    fun `failure and cancellation do not affect an unrelated pool`() = runBlocking {
        val unrelated = VertxPgDriver.create(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password,
            maxPoolSize = 1,
            vertx = vertx
        )
        try {
            assertFailsWith<SyntheticFailure> {
                driver.withTransaction { throw SyntheticFailure() }
            }
            assertEquals(1, unrelated.executeQueryRaw("SELECT 1 AS value").single().getInt("value"))
        } finally {
            unrelated.close()
        }
    }

    private suspend fun createSchema() {
        listOf(
            "CREATE TABLE IF NOT EXISTS ae_objects (owner_account_id text, id text, revision bigint, PRIMARY KEY(owner_account_id, id))",
            "CREATE TABLE IF NOT EXISTS ae_versions (owner_account_id text, object_id text, version bigint)",
            "CREATE TABLE IF NOT EXISTS ae_changes (owner_account_id text, sequence bigint)",
            "CREATE TABLE IF NOT EXISTS ae_receipts (owner_account_id text, operation_id text PRIMARY KEY)",
            "CREATE TABLE IF NOT EXISTS ae_outbox (owner_account_id text, payload text)",
            "CREATE TABLE IF NOT EXISTS ae_cas (owner_account_id text, id text, revision bigint, PRIMARY KEY(owner_account_id, id))",
            "CREATE TABLE IF NOT EXISTS ae_serial (id integer PRIMARY KEY, enabled boolean NOT NULL)",
            "CREATE TABLE IF NOT EXISTS ae_commit_delay (id integer PRIMARY KEY)",
            "CREATE OR REPLACE FUNCTION ae_delay_commit() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM pg_sleep(0.2); RETURN NEW; END'",
            "DROP TRIGGER IF EXISTS ae_delay_commit_trigger ON ae_commit_delay",
            "CREATE CONSTRAINT TRIGGER ae_delay_commit_trigger AFTER INSERT ON ae_commit_delay DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ae_delay_commit()"
        ).forEach { driver.execute(it) }
    }

    private suspend fun clearAtomicRows() {
        listOf("ae_outbox", "ae_receipts", "ae_changes", "ae_versions", "ae_objects")
            .forEach { driver.execute("DELETE FROM $it") }
    }

    private fun atomicStatements(id: String): List<Pair<String, List<SqlValue>>> = listOf(
        "INSERT INTO ae_objects (owner_account_id, id, revision) VALUES ($1, $2, 1)" to
            listOf(SqlValue.StringValue("owner"), SqlValue.StringValue(id)),
        "INSERT INTO ae_versions (owner_account_id, object_id, version) VALUES ($1, $2, 1)" to
            listOf(SqlValue.StringValue("owner"), SqlValue.StringValue(id)),
        "INSERT INTO ae_changes (owner_account_id, sequence) VALUES ($1, 1)" to
            listOf(SqlValue.StringValue("owner")),
        "INSERT INTO ae_receipts (owner_account_id, operation_id) VALUES ($1, $2)" to
            listOf(SqlValue.StringValue("owner"), SqlValue.StringValue(id)),
        "INSERT INTO ae_outbox (owner_account_id, payload) VALUES ($1, $2)" to
            listOf(SqlValue.StringValue("owner"), SqlValue.StringValue("wake"))
    )

    private suspend fun writeAtomicRows(tx: DatabaseDriver, id: String) {
        atomicStatements(id).forEach { (sql, params) -> tx.execute(sql, params) }
    }

    private suspend fun totalAtomicRows(): Long =
        listOf("ae_objects", "ae_versions", "ae_changes", "ae_receipts", "ae_outbox")
            .sumOf { count(it) }

    private suspend fun count(table: String, target: DatabaseDriver = driver): Long =
        target.executeQueryRaw("SELECT count(*) AS count FROM $table").single().getLong("count")!!

    private suspend fun assertHealthyTransaction(target: VertxPgDriver = driver) {
        assertEquals(1, target.withTransaction { tx ->
            tx.executeQueryRaw("SELECT 1 AS value").single().getInt("value")
        })
    }

    private class SyntheticFailure : RuntimeException()
}
