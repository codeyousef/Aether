package codes.yousef.aether.tasks

import codes.yousef.aether.db.RawQuery
import codes.yousef.aether.db.SqlValue
import codes.yousef.aether.db.jvm.VertxPgDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReliableTaskPostgresTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var driver: VertxPgDriver
    private lateinit var tasks: DatabaseLeasedTaskStore
    private lateinit var reliable: DatabaseReliableWorkStore

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "AE-T09 requires PostgreSQL")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        driver = VertxPgDriver.create(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password,
            maxPoolSize = 16
        )
        tasks = DatabaseLeasedTaskStore(driver)
        reliable = DatabaseReliableWorkStore(driver)
    }

    @AfterAll
    fun stopPostgres() {
        if (::driver.isInitialized) runBlocking { driver.close() }
        if (::postgres.isInitialized) postgres.stop()
    }

    @BeforeEach
    fun resetSchema() = runBlocking {
        driver.executeDDL(RawQuery("""
            DROP TABLE IF EXISTS ae_task_mutation;
            DROP TABLE IF EXISTS _aether_task_effects;
            DROP TABLE IF EXISTS _aether_task_outbox;
            DROP TABLE IF EXISTS _aether_task_receipts;
            DROP TABLE IF EXISTS _aether_leased_tasks;
            DROP TABLE IF EXISTS _aether_tasks;
            DROP TABLE IF EXISTS _aether_migration_fence;
            DROP TABLE IF EXISTS _aether_nontransactional_migrations_tasks;
            DROP TABLE IF EXISTS _aether_migrations_tasks;
        """.trimIndent()))
        tasks.migrate()
        driver.executeDDL(RawQuery("CREATE TABLE ae_task_mutation(id VARCHAR(64) PRIMARY KEY);"))
    }

    @Test
    fun `two workers claim one job once and stale generation cannot commit`(): Unit = runBlocking {
        enqueue("race", "race-key")

        val claims = listOf("worker-a", "worker-b").map { owner ->
            async { tasks.claimNext("suite", owner) }
        }.awaitAll().filterNotNull()

        assertEquals(1, claims.size)
        val first = claims.single()
        val firstToken = TaskLeaseToken(first.id, requireNotNull(first.leaseOwner), first.leaseGeneration)
        driver.execute(
            "UPDATE _aether_leased_tasks SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE id = $1",
            listOf(SqlValue.StringValue(first.id))
        )
        val takeover = assertNotNull(tasks.claimNext("suite", "worker-c"))
        assertTrue(takeover.leaseGeneration > first.leaseGeneration)
        assertFalse(tasks.complete(firstToken, "result:stale"))
        assertTrue(tasks.complete(TaskLeaseToken(takeover.id, "worker-c", takeover.leaseGeneration), "result:current"))
        assertEquals(LeasedTaskState.COMPLETED, tasks.getById("race")?.state)
    }

    @Test
    fun `queue names are bound and injection cannot change schema`(): Unit = runBlocking {
        enqueue("safe", "safe-key")
        assertFailsWith<IllegalArgumentException> {
            tasks.claimNext("suite'; DROP TABLE _aether_leased_tasks;--", "worker")
        }
        assertNotNull(tasks.getById("safe"))
    }

    @Test
    fun `retry schedule becomes terminal and replay retains operation identity`(): Unit = runBlocking {
        enqueue("poison", "operation:stable")
        driver.execute(
            "UPDATE _aether_leased_tasks SET attempts = 5 WHERE id = $1",
            listOf(SqlValue.StringValue("poison"))
        )
        val claimed = assertNotNull(tasks.claimNext("suite", "worker"))
        val token = TaskLeaseToken(claimed.id, "worker", claimed.leaseGeneration)
        assertEquals(LeasedTaskState.FAILED, tasks.retry(token, "retry_exhausted"))
        val failed = assertNotNull(tasks.getById("poison"))
        assertEquals("operation:stable", failed.operationKey)
        assertEquals("payload:poison", failed.encryptedPayloadReference)
        assertEquals("retry_exhausted", failed.terminalCode)

        assertTrue(tasks.replayFailed("poison"))
        val replayed = assertNotNull(tasks.getById("poison"))
        assertEquals(LeasedTaskState.AVAILABLE, replayed.state)
        assertEquals("operation:stable", replayed.operationKey)
        assertEquals(0, replayed.attempts)
    }

    @Test
    fun `mutation receipt and outbox commit atomically then replay without mutation`(): Unit = runBlocking {
        val scope = IdempotencyScope("account:1", "object:create", "client:1")
        val digest = "a".repeat(64)
        assertFailsWith<IllegalStateException> {
            reliable.executeIdempotent(scope, digest, TASK_RETRY_BUDGET_SECONDS) { tx ->
                tx.execute("INSERT INTO ae_task_mutation(id) VALUES ($1)", listOf(SqlValue.StringValue("rolled-back")))
                throw IllegalStateException("rollback")
            }
        }
        assertEquals(0, count("ae_task_mutation"))
        assertEquals(0, count("_aether_task_receipts"))
        assertEquals(0, count("_aether_task_outbox"))

        val executed = reliable.executeIdempotent(scope, digest, TASK_RETRY_BUDGET_SECONDS) { tx ->
            tx.execute("INSERT INTO ae_task_mutation(id) VALUES ($1)", listOf(SqlValue.StringValue("committed")))
            IdempotentMutationResult(
                encryptedResultReference = "result:object-1",
                outboxEvents = listOf(OutboxEvent("event-1", "object.created", "payload:event-1"))
            )
        }
        assertEquals(IdempotentExecution.Executed("result:object-1"), executed)
        assertEquals(1, count("ae_task_mutation"))
        assertEquals(1, count("_aether_task_outbox"))

        val replay = reliable.executeIdempotent(scope, digest, TASK_RETRY_BUDGET_SECONDS) {
            error("replay must not execute mutation")
        }
        assertEquals(IdempotentExecution.Replay("result:object-1"), replay)
        assertIs<IdempotentExecution.Conflict>(
            reliable.executeIdempotent(scope, "b".repeat(64), TASK_RETRY_BUDGET_SECONDS) {
                error("conflict must not execute mutation")
            }
        )
    }

    @Test
    fun `outbox and provider acknowledgements reconcile unknown outcomes with fencing`(): Unit = runBlocking {
        reliable.executeIdempotent(
            IdempotencyScope("account:2", "mail:send", "client:2"),
            "c".repeat(64),
            TASK_RETRY_BUDGET_SECONDS
        ) {
            IdempotentMutationResult("result:mail", listOf(OutboxEvent("mail-1", "mail.send", "payload:mail")))
        }
        val first = assertNotNull(reliable.claimOutbox("sender-a"))
        assertTrue(reliable.markOutboxUnknown(first))
        val second = assertNotNull(reliable.claimOutbox("sender-b"))
        assertTrue(second.generation > first.generation)
        assertFalse(reliable.acknowledgeOutbox(first, "provider:stale"))
        assertTrue(reliable.acknowledgeOutbox(second, "provider:accepted"))
        assertNull(reliable.claimOutbox("sender-c"))

        val effect = assertIs<ExternalEffectClaim.Claimed>(
            reliable.claimExternalEffect("effect:mail-1", "d".repeat(64))
        )
        driver.execute(
            "UPDATE _aether_task_effects SET attempt_until = clock_timestamp() - INTERVAL '1 second' WHERE effect_key = $1",
            listOf(SqlValue.StringValue(effect.token.effectKey))
        )
        assertEquals(1, reliable.recoverExpiredExternalEffects())
        val retried = assertIs<ExternalEffectClaim.Claimed>(
            reliable.claimExternalEffect("effect:mail-1", "d".repeat(64))
        )
        assertTrue(retried.token.generation > effect.token.generation)
        assertFalse(reliable.acknowledgeExternalEffect(effect.token, "provider:stale"))
        assertTrue(reliable.acknowledgeExternalEffect(retried.token, "provider:ack"))
        assertEquals(
            ExternalEffectClaim.AlreadyAcknowledged("provider:ack"),
            reliable.claimExternalEffect("effect:mail-1", "d".repeat(64))
        )
        assertIs<ExternalEffectClaim.Conflict>(
            reliable.claimExternalEffect("effect:mail-1", "e".repeat(64))
        )
    }

    @Test
    fun `worker concurrency is globally bounded across queues`(): Unit = runBlocking {
        repeat(4) { index -> enqueue("global-$index", "global-key-$index", if (index % 2 == 0) "one" else "two") }
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val completed = AtomicInteger()
        val worker = LeasedTaskWorker(
            store = tasks,
            owner = "bounded-worker",
            config = LeasedTaskWorkerConfig(concurrency = 2, queues = listOf("one", "two")),
            executor = LeasedTaskExecutor {
                val current = active.incrementAndGet()
                maximum.accumulateAndGet(current, ::maxOf)
                try {
                    delay(100)
                    "result:${it.id}"
                } finally {
                    active.decrementAndGet()
                    completed.incrementAndGet()
                }
            }
        )
        val running = launch { worker.run() }
        withTimeout(10_000) {
            while (completed.get() < 4) delay(20)
        }
        worker.stop()
        running.join()
        assertEquals(4, completed.get())
        assertEquals(2, maximum.get())
        assertEquals(4, driver.executeQuery(
            "SELECT id FROM _aether_leased_tasks WHERE state = 'COMPLETED'", emptyList()
        ).size)
    }

    @Test
    fun `heartbeat extends only the current unexpired lease`(): Unit = runBlocking {
        enqueue("heartbeat", "heartbeat-key")
        val claim = assertNotNull(tasks.claimNext("suite", "heartbeat-worker"))
        val token = TaskLeaseToken(claim.id, "heartbeat-worker", claim.leaseGeneration)
        driver.execute(
            "UPDATE _aether_leased_tasks SET lease_until = clock_timestamp() + INTERVAL '2 seconds' WHERE id = $1",
            listOf(SqlValue.StringValue(claim.id))
        )
        val before = requireNotNull(tasks.getById(claim.id)?.leaseUntilEpochMilliseconds)
        assertTrue(tasks.heartbeat(token))
        val after = requireNotNull(tasks.getById(claim.id)?.leaseUntilEpochMilliseconds)
        assertTrue(after - before >= 50_000)

        driver.execute(
            "UPDATE _aether_leased_tasks SET lease_until = clock_timestamp() - INTERVAL '1 second' WHERE id = $1",
            listOf(SqlValue.StringValue(claim.id))
        )
        assertFalse(tasks.heartbeat(token))
    }

    @Test
    fun `retry delays follow the fixed schedule and sixth failure is terminal`(): Unit = runBlocking {
        enqueue("schedule", "schedule-key")
        TASK_RETRY_DELAYS_SECONDS.forEachIndexed { index, expectedSeconds ->
            val claim = assertNotNull(tasks.claimNext("suite", "retry-worker"))
            val token = TaskLeaseToken(claim.id, "retry-worker", claim.leaseGeneration)
            assertEquals(LeasedTaskState.RETRY_WAIT, tasks.retry(token, "task_failed"))
            val remaining = requireNotNull(driver.executeQuery(
                """
                SELECT FLOOR(EXTRACT(EPOCH FROM (available_at - clock_timestamp())))::BIGINT AS seconds
                FROM _aether_leased_tasks WHERE id = $1
                """.trimIndent(),
                listOf(SqlValue.StringValue("schedule"))
            ).single().getLong("seconds"))
            assertTrue(remaining in (expectedSeconds - 2)..expectedSeconds)
            driver.execute(
                "UPDATE _aether_leased_tasks SET available_at = clock_timestamp() WHERE id = $1",
                listOf(SqlValue.StringValue("schedule"))
            )
            assertEquals(index + 1, tasks.getById("schedule")?.attempts)
        }
        val finalClaim = assertNotNull(tasks.claimNext("suite", "retry-worker"))
        assertEquals(
            LeasedTaskState.FAILED,
            tasks.retry(
                TaskLeaseToken(finalClaim.id, "retry-worker", finalClaim.leaseGeneration),
                "retry_exhausted"
            )
        )
    }

    @Test
    fun `worker cancellation releases its current lease without completing`(): Unit = runBlocking {
        enqueue("cancelled-work", "cancel-key")
        val started = CompletableDeferred<Unit>()
        val worker = LeasedTaskWorker(
            store = tasks,
            owner = "cancel-worker",
            config = LeasedTaskWorkerConfig(concurrency = 1, queues = listOf("suite")),
            executor = LeasedTaskExecutor {
                started.complete(Unit)
                awaitCancellation()
            }
        )
        val running = launch { worker.run() }
        withTimeout(5_000) { started.await() }
        worker.stop()
        running.join()
        val released = assertNotNull(tasks.getById("cancelled-work"))
        assertEquals(LeasedTaskState.AVAILABLE, released.state)
        assertNull(released.leaseOwner)
        assertNull(released.leaseUntilEpochMilliseconds)
    }

    @Test
    fun `secure task tables expose references but no readable private payload columns`(): Unit = runBlocking {
        val columns = driver.executeQuery(
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_name IN ('_aether_leased_tasks', '_aether_task_outbox', '_aether_task_receipts')
            """.trimIndent(),
            emptyList()
        ).mapNotNull { it.getString("column_name") }.toSet()
        assertTrue("payload_ref" in columns)
        assertTrue("result_ref" in columns)
        assertFalse(columns.any { it in setOf("args", "result", "error", "stack_trace", "payload") })
    }

    private suspend fun enqueue(id: String, operationKey: String, queue: String = "suite") {
        assertTrue(tasks.enqueue(LeasedTaskRecord(
            id = id,
            queue = queue,
            state = LeasedTaskState.AVAILABLE,
            availableAtEpochMilliseconds = 0,
            attempts = 0,
            leaseOwner = null,
            leaseUntilEpochMilliseconds = null,
            leaseGeneration = 0,
            operationKey = operationKey,
            encryptedPayloadReference = "payload:$id"
        )))
    }

    private suspend fun count(table: String): Long = requireNotNull(
        driver.executeQuery("SELECT COUNT(*) AS count FROM $table", emptyList()).single().getLong("count")
    )
}
