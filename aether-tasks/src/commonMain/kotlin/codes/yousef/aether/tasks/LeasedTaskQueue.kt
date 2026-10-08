package codes.yousef.aether.tasks

import codes.yousef.aether.db.DatabaseDriver
import codes.yousef.aether.db.DatabaseDriverRegistry
import codes.yousef.aether.db.Migration
import codes.yousef.aether.db.MigrationRunner
import codes.yousef.aether.db.SqlValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

const val TASK_LEASE_SECONDS: Long = 60
const val TASK_HEARTBEAT_SECONDS: Long = 20
const val TASK_RETRY_BUDGET_SECONDS: Long = 86_400
val TASK_RETRY_DELAYS_SECONDS: List<Long> = listOf(5, 30, 120, 600, 3_600)

private val SAFE_TASK_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")
private val SAFE_TASK_QUEUE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")

private fun requireTaskToken(value: String, label: String, maxLength: Int = 256) {
    require(value.length <= maxLength && SAFE_TASK_TOKEN.matches(value)) { "Invalid $label" }
}

enum class LeasedTaskState {
    AVAILABLE,
    LEASED,
    RETRY_WAIT,
    COMPLETED,
    FAILED
}

data class LeasedTaskRecord(
    val id: String,
    val queue: String,
    val state: LeasedTaskState,
    val availableAtEpochMilliseconds: Long,
    val attempts: Int,
    val leaseOwner: String?,
    val leaseUntilEpochMilliseconds: Long?,
    val leaseGeneration: Long,
    val operationKey: String,
    val encryptedPayloadReference: String,
    val encryptedResultReference: String? = null,
    val terminalCode: String? = null,
    val createdAtEpochMilliseconds: Long = 0
) {
    init {
        requireTaskToken(id, "task ID", 64)
        require(SAFE_TASK_QUEUE.matches(queue)) { "Invalid task queue" }
        require(attempts >= 0 && leaseGeneration >= 0) { "Task counters must be non-negative" }
        requireTaskToken(operationKey, "operation key")
        requireTaskToken(encryptedPayloadReference, "encrypted payload reference", 512)
        encryptedResultReference?.let { requireTaskToken(it, "encrypted result reference", 512) }
        terminalCode?.let { requireTaskToken(it, "terminal code", 64) }
    }
}

data class TaskLeaseToken(val id: String, val owner: String, val generation: Long) {
    init {
        requireTaskToken(id, "task ID", 64)
        requireTaskToken(owner, "lease owner", 64)
        require(generation > 0) { "Lease generation must be positive" }
    }
}

interface LeasedTaskStore {
    suspend fun enqueue(task: LeasedTaskRecord): Boolean
    suspend fun claimNext(queue: String, owner: String): LeasedTaskRecord?
    suspend fun heartbeat(token: TaskLeaseToken): Boolean
    suspend fun complete(token: TaskLeaseToken, encryptedResultReference: String?): Boolean
    suspend fun retry(token: TaskLeaseToken, terminalCode: String): LeasedTaskState?
    suspend fun release(token: TaskLeaseToken): Boolean
    suspend fun replayFailed(id: String): Boolean
    suspend fun getById(id: String): LeasedTaskRecord?
}

class DatabaseLeasedTaskStore(
    private val driver: DatabaseDriver = DatabaseDriverRegistry.driver
) : LeasedTaskStore {
    suspend fun migrate() {
        val runner = MigrationRunner(driver, stream = "tasks")
        runner.register(TaskTableMigration)
        runner.register(LeasedTaskTableMigration)
        runner.register(ReliableWorkTableMigration)
        val result = runner.migrate()
        if (!result.success) throw result.errors.first().exception
    }

    override suspend fun enqueue(task: LeasedTaskRecord): Boolean {
        require(task.state == LeasedTaskState.AVAILABLE) { "New leased task must be available" }
        return driver.execute(
            """
            INSERT INTO _aether_leased_tasks(
                id, queue, state, available_at, attempts, lease_generation,
                operation_key, payload_ref, created_at
            ) VALUES (
                $1, $2, 'AVAILABLE', to_timestamp($3 / 1000.0), 0, 0, $4, $5,
                CASE WHEN $6 = 0 THEN clock_timestamp() ELSE to_timestamp($6 / 1000.0) END
            ) ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            listOf(
                SqlValue.StringValue(task.id),
                SqlValue.StringValue(task.queue),
                SqlValue.LongValue(task.availableAtEpochMilliseconds),
                SqlValue.StringValue(task.operationKey),
                SqlValue.StringValue(task.encryptedPayloadReference),
                SqlValue.LongValue(task.createdAtEpochMilliseconds)
            )
        ) == 1
    }

    override suspend fun claimNext(queue: String, owner: String): LeasedTaskRecord? {
        require(SAFE_TASK_QUEUE.matches(queue)) { "Invalid task queue" }
        requireTaskToken(owner, "lease owner", 64)
        val rows = driver.executeQuery(
            """
            WITH candidate AS (
                SELECT id
                FROM _aether_leased_tasks
                WHERE queue = $1
                  AND (
                    (state IN ('AVAILABLE', 'RETRY_WAIT') AND available_at <= clock_timestamp())
                    OR (state = 'LEASED' AND lease_until <= clock_timestamp())
                  )
                ORDER BY available_at ASC, created_at ASC, id ASC
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE _aether_leased_tasks AS task
            SET state = 'LEASED',
                lease_owner = $2,
                lease_until = clock_timestamp() + INTERVAL '60 seconds',
                lease_generation = task.lease_generation + 1,
                attempts = task.attempts + 1,
                first_attempt_at = COALESCE(task.first_attempt_at, clock_timestamp()),
                terminal_code = NULL
            FROM candidate
            WHERE task.id = candidate.id
            RETURNING task.*,
                (EXTRACT(EPOCH FROM task.available_at) * 1000)::BIGINT AS available_at_ms,
                (EXTRACT(EPOCH FROM task.lease_until) * 1000)::BIGINT AS lease_until_ms,
                (EXTRACT(EPOCH FROM task.created_at) * 1000)::BIGINT AS created_at_ms
            """.trimIndent(),
            listOf(SqlValue.StringValue(queue), SqlValue.StringValue(owner))
        )
        return rows.singleOrNull()?.let(::rowToLeasedTask)
    }

    override suspend fun heartbeat(token: TaskLeaseToken): Boolean = fencedUpdate(
        token,
        "lease_until = clock_timestamp() + INTERVAL '60 seconds'"
    )

    override suspend fun complete(
        token: TaskLeaseToken,
        encryptedResultReference: String?
    ): Boolean {
        encryptedResultReference?.let { requireTaskToken(it, "encrypted result reference", 512) }
        return driver.execute(
            """
            UPDATE _aether_leased_tasks
            SET state = 'COMPLETED', completed_at = clock_timestamp(), result_ref = $4,
                lease_owner = NULL, lease_until = NULL
            WHERE id = $1 AND lease_owner = $2 AND lease_generation = $3
              AND state = 'LEASED' AND lease_until > clock_timestamp()
            """.trimIndent(),
            listOf(
                SqlValue.StringValue(token.id), SqlValue.StringValue(token.owner),
                SqlValue.LongValue(token.generation),
                encryptedResultReference?.let(SqlValue::StringValue) ?: SqlValue.NullValue
            )
        ) == 1
    }

    override suspend fun retry(token: TaskLeaseToken, terminalCode: String): LeasedTaskState? {
        requireTaskToken(terminalCode, "terminal code", 64)
        val rows = driver.executeQuery(
            """
            UPDATE _aether_leased_tasks
            SET state = CASE
                    WHEN attempts > 5 OR first_attempt_at + INTERVAL '24 hours' <= clock_timestamp()
                        THEN 'FAILED'
                    ELSE 'RETRY_WAIT'
                END,
                available_at = clock_timestamp() + CASE attempts
                    WHEN 1 THEN INTERVAL '5 seconds'
                    WHEN 2 THEN INTERVAL '30 seconds'
                    WHEN 3 THEN INTERVAL '120 seconds'
                    WHEN 4 THEN INTERVAL '600 seconds'
                    ELSE INTERVAL '3600 seconds'
                END,
                terminal_code = CASE
                    WHEN attempts > 5 OR first_attempt_at + INTERVAL '24 hours' <= clock_timestamp()
                        THEN $4
                    ELSE NULL
                END,
                lease_owner = NULL, lease_until = NULL
            WHERE id = $1 AND lease_owner = $2 AND lease_generation = $3
              AND state = 'LEASED' AND lease_until > clock_timestamp()
            RETURNING state
            """.trimIndent(),
            listOf(
                SqlValue.StringValue(token.id), SqlValue.StringValue(token.owner),
                SqlValue.LongValue(token.generation), SqlValue.StringValue(terminalCode)
            )
        )
        return rows.singleOrNull()?.getString("state")?.let(LeasedTaskState::valueOf)
    }

    override suspend fun release(token: TaskLeaseToken): Boolean = driver.execute(
        """
        UPDATE _aether_leased_tasks
        SET state = 'AVAILABLE', available_at = clock_timestamp(),
            lease_owner = NULL, lease_until = NULL
        WHERE id = $1 AND lease_owner = $2 AND lease_generation = $3
          AND state = 'LEASED'
        """.trimIndent(),
        token.parameters()
    ) == 1

    override suspend fun replayFailed(id: String): Boolean {
        requireTaskToken(id, "task ID", 64)
        return driver.execute(
            """
            UPDATE _aether_leased_tasks
            SET state = 'AVAILABLE', available_at = clock_timestamp(), attempts = 0,
                first_attempt_at = NULL, completed_at = NULL, terminal_code = NULL,
                lease_owner = NULL, lease_until = NULL
            WHERE id = $1 AND state = 'FAILED'
            """.trimIndent(),
            listOf(SqlValue.StringValue(id))
        ) == 1
    }

    override suspend fun getById(id: String): LeasedTaskRecord? {
        requireTaskToken(id, "task ID", 64)
        return driver.executeQuery(
            """
            SELECT task.*,
                (EXTRACT(EPOCH FROM task.available_at) * 1000)::BIGINT AS available_at_ms,
                (EXTRACT(EPOCH FROM task.lease_until) * 1000)::BIGINT AS lease_until_ms,
                (EXTRACT(EPOCH FROM task.created_at) * 1000)::BIGINT AS created_at_ms
            FROM _aether_leased_tasks task WHERE id = $1
            """.trimIndent(),
            listOf(SqlValue.StringValue(id))
        ).singleOrNull()?.let(::rowToLeasedTask)
    }

    private suspend fun fencedUpdate(token: TaskLeaseToken, assignment: String): Boolean = driver.execute(
        """
        UPDATE _aether_leased_tasks SET $assignment
        WHERE id = $1 AND lease_owner = $2 AND lease_generation = $3
          AND state = 'LEASED' AND lease_until > clock_timestamp()
        """.trimIndent(),
        token.parameters()
    ) == 1

    private fun TaskLeaseToken.parameters(): List<SqlValue> = listOf(
        SqlValue.StringValue(id), SqlValue.StringValue(owner), SqlValue.LongValue(generation)
    )

    private fun rowToLeasedTask(row: codes.yousef.aether.db.Row): LeasedTaskRecord = LeasedTaskRecord(
        id = requireNotNull(row.getString("id")),
        queue = requireNotNull(row.getString("queue")),
        state = LeasedTaskState.valueOf(requireNotNull(row.getString("state"))),
        availableAtEpochMilliseconds = requireNotNull(row.getLong("available_at_ms")),
        attempts = requireNotNull(row.getInt("attempts")),
        leaseOwner = row.getString("lease_owner"),
        leaseUntilEpochMilliseconds = row.getLong("lease_until_ms"),
        leaseGeneration = requireNotNull(row.getLong("lease_generation")),
        operationKey = requireNotNull(row.getString("operation_key")),
        encryptedPayloadReference = requireNotNull(row.getString("payload_ref")),
        encryptedResultReference = row.getString("result_ref"),
        terminalCode = row.getString("terminal_code"),
        createdAtEpochMilliseconds = requireNotNull(row.getLong("created_at_ms"))
    )
}

fun interface LeasedTaskExecutor {
    suspend fun execute(task: LeasedTaskRecord): String?
}

data class LeasedTaskWorkerConfig(
    val concurrency: Int = 4,
    val queues: List<String> = listOf("default"),
    val pollInterval: Duration = 1.seconds,
    val heartbeatInterval: Duration = TASK_HEARTBEAT_SECONDS.seconds
) {
    init {
        require(concurrency in 1..1_024) { "Task concurrency must be 1..1024" }
        require(queues.size in 1..64 && queues.all(SAFE_TASK_QUEUE::matches)) { "Invalid task queues" }
        require(pollInterval.isPositive()) { "Poll interval must be positive" }
        require(heartbeatInterval.inWholeSeconds in 1 until TASK_LEASE_SECONDS) {
            "Heartbeat must be shorter than the lease"
        }
    }
}

/** Worker with exactly [LeasedTaskWorkerConfig.concurrency] loops shared across every queue. */
class LeasedTaskWorker(
    private val store: LeasedTaskStore,
    private val owner: String,
    private val executor: LeasedTaskExecutor,
    private val config: LeasedTaskWorkerConfig = LeasedTaskWorkerConfig()
) {
    private var runJob: Job? = null

    init {
        requireTaskToken(owner, "lease owner", 64)
    }

    suspend fun run(): Unit = coroutineScope {
        check(runJob == null) { "Worker is already running" }
        runJob = coroutineContext[Job]
        repeat(config.concurrency) { workerIndex ->
            launch { poll(workerIndex) }
        }
    }

    suspend fun stop() {
        runJob?.cancelAndJoin()
        runJob = null
    }

    private suspend fun poll(workerIndex: Int) {
        var nextQueue = workerIndex % config.queues.size
        while (kotlin.coroutines.coroutineContext.isActive) {
            var task: LeasedTaskRecord? = null
            for (ignored in config.queues.indices) {
                val queue = config.queues[nextQueue]
                nextQueue = (nextQueue + 1) % config.queues.size
                task = store.claimNext(queue, owner)
                if (task != null) break
            }
            if (task == null) delay(config.pollInterval) else process(task)
        }
    }

    private suspend fun process(task: LeasedTaskRecord) {
        val token = TaskLeaseToken(task.id, owner, task.leaseGeneration)
        coroutineScope {
            val heartbeat = launch {
                while (isActive) {
                    delay(config.heartbeatInterval)
                    if (!store.heartbeat(token)) throw CancellationException("Task lease lost")
                }
            }
            try {
                val resultReference = executor.execute(task)
                heartbeat.cancelAndJoin()
                store.complete(token, resultReference)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    heartbeat.cancelAndJoin()
                    store.release(token)
                }
                throw cancelled
            } catch (_: Throwable) {
                heartbeat.cancelAndJoin()
                store.retry(token, "task_failed")
            }
        }
    }
}

object LeasedTaskTableMigration : Migration {
    override val version: Long = 20261008000002L
    override val description: String = "Create fenced leased task queue"

    override fun up(): String = """
        CREATE TABLE IF NOT EXISTS _aether_leased_tasks (
            id VARCHAR(64) PRIMARY KEY,
            queue VARCHAR(64) NOT NULL,
            state VARCHAR(32) NOT NULL,
            available_at TIMESTAMPTZ NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
            first_attempt_at TIMESTAMPTZ,
            lease_owner VARCHAR(64),
            lease_until TIMESTAMPTZ,
            lease_generation BIGINT NOT NULL DEFAULT 0 CHECK (lease_generation >= 0),
            operation_key VARCHAR(256) NOT NULL UNIQUE,
            payload_ref VARCHAR(512) NOT NULL,
            result_ref VARCHAR(512),
            terminal_code VARCHAR(64),
            created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
            completed_at TIMESTAMPTZ,
            CHECK (state IN ('AVAILABLE', 'LEASED', 'RETRY_WAIT', 'COMPLETED', 'FAILED')),
            CHECK ((state = 'LEASED') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL))
        );
        CREATE INDEX IF NOT EXISTS idx_aether_leased_tasks_claim
            ON _aether_leased_tasks(queue, available_at, created_at)
            WHERE state IN ('AVAILABLE', 'RETRY_WAIT', 'LEASED');
    """.trimIndent()

    override fun down(): String = "DROP TABLE IF EXISTS _aether_leased_tasks;"
}
