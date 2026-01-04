package codes.yousef.aether.tasks

import codes.yousef.aether.db.*
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// Serializer for Map<String, String>
private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

/**
 * Database-backed task store using aether-db.
 * Stores tasks in the `_aether_tasks` table.
 *
 * Example:
 * ```kotlin
 * // Create the store
 * val store = DatabaseTaskStore()
 *
 * // Run migrations (creates the table if needed)
 * store.migrate()
 *
 * // Initialize the dispatcher
 * TaskDispatcher.initialize(store)
 * ```
 */
class DatabaseTaskStore(
    private val json: Json = TaskRegistry.json
) : TaskStore {
    
    /**
     * Run the migration to create the tasks table.
     */
    suspend fun migrate() {
        val runner = MigrationRunner(DatabaseDriverRegistry.driver)
        runner.register(TaskTableMigration)
        runner.migrate()
    }

    override suspend fun save(task: TaskRecord): TaskRecord {
        val driver = DatabaseDriverRegistry.driver
        
        val insertQuery = InsertQuery(
            table = "_aether_tasks",
            columns = listOf(
                "id", "name", "queue", "args", "status", "priority",
                "created_at", "scheduled_for", "max_retries", "timeout_millis", "metadata"
            ),
            values = listOf(
                Expression.Literal(SqlValue.StringValue(task.id)),
                Expression.Literal(SqlValue.StringValue(task.name)),
                Expression.Literal(SqlValue.StringValue(task.queue)),
                Expression.Literal(SqlValue.StringValue(json.encodeToString(JsonElement.serializer(), task.args))),
                Expression.Literal(SqlValue.StringValue(task.status.name)),
                Expression.Literal(SqlValue.IntValue(task.priority.value)),
                Expression.Literal(SqlValue.LongValue(task.createdAt)),
                Expression.Literal(SqlValue.LongValue(task.scheduledFor)),
                Expression.Literal(SqlValue.IntValue(task.maxRetries)),
                Expression.Literal(SqlValue.LongValue(task.timeoutMillis)),
                Expression.Literal(SqlValue.StringValue(json.encodeToString(mapSerializer, task.metadata)))
            )
        )
        
        driver.executeUpdate(insertQuery)
        return task
    }

    override suspend fun getById(id: String): TaskRecord? {
        val driver = DatabaseDriverRegistry.driver
        
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "_aether_tasks",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "id"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue(id))
            )
        )
        
        val rows = driver.executeQuery(query)
        return rows.firstOrNull()?.let { rowToTask(it) }
    }

    override suspend fun claimNext(queue: String, workerId: String): TaskRecord? {
        val driver = DatabaseDriverRegistry.driver
        val now = Clock.System.now().toEpochMilliseconds()
        
        // Use SELECT FOR UPDATE SKIP LOCKED for concurrent access
        val selectSql = """
            SELECT * FROM _aether_tasks 
            WHERE status = 'PENDING' 
            AND queue = '$queue'
            AND scheduled_for <= $now
            ORDER BY priority DESC, created_at ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
        """.trimIndent()
        
        val rows = driver.executeQuery(RawQuery(selectSql))
        val task = rows.firstOrNull()?.let { rowToTask(it) } ?: return null
        
        // Claim the task
        val updateQuery = UpdateQuery(
            table = "_aether_tasks",
            assignments = mapOf(
                "status" to Expression.Literal(SqlValue.StringValue(TaskStatus.PROCESSING.name)),
                "worker_id" to Expression.Literal(SqlValue.StringValue(workerId)),
                "started_at" to Expression.Literal(SqlValue.LongValue(now))
            ),
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "id"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue(task.id))
            )
        )
        
        driver.executeUpdate(updateQuery)
        
        return task.copy(
            status = TaskStatus.PROCESSING,
            workerId = workerId,
            startedAt = now
        )
    }

    override suspend fun update(task: TaskRecord): TaskRecord {
        val driver = DatabaseDriverRegistry.driver
        
        val assignments = mutableMapOf<String, Expression>(
            "status" to Expression.Literal(SqlValue.StringValue(task.status.name)),
            "retry_count" to Expression.Literal(SqlValue.IntValue(task.retryCount))
        )
        
        task.startedAt?.let {
            assignments["started_at"] = Expression.Literal(SqlValue.LongValue(it))
        }
        task.completedAt?.let {
            assignments["completed_at"] = Expression.Literal(SqlValue.LongValue(it))
        }
        task.result?.let {
            assignments["result"] = Expression.Literal(SqlValue.StringValue(json.encodeToString(JsonElement.serializer(), it)))
        }
        task.error?.let {
            assignments["error"] = Expression.Literal(SqlValue.StringValue(it))
        }
        task.stackTrace?.let {
            assignments["stack_trace"] = Expression.Literal(SqlValue.StringValue(it))
        }
        task.workerId?.let {
            assignments["worker_id"] = Expression.Literal(SqlValue.StringValue(it))
        } ?: run {
            assignments["worker_id"] = Expression.Literal(SqlValue.NullValue)
        }
        
        assignments["scheduled_for"] = Expression.Literal(SqlValue.LongValue(task.scheduledFor))
        
        val updateQuery = UpdateQuery(
            table = "_aether_tasks",
            assignments = assignments,
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "id"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue(task.id))
            )
        )
        
        driver.executeUpdate(updateQuery)
        return task
    }

    override suspend fun getByStatus(status: TaskStatus, limit: Int): List<TaskRecord> {
        val driver = DatabaseDriverRegistry.driver
        
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "_aether_tasks",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "status"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue(status.name))
            ),
            orderBy = listOf(OrderByClause(Expression.ColumnRef(column = "created_at"), OrderDirection.ASC)),
            limit = limit
        )
        
        return driver.executeQuery(query).map { rowToTask(it) }
    }

    override suspend fun getByQueue(queue: String, limit: Int): List<TaskRecord> {
        val driver = DatabaseDriverRegistry.driver
        
        val query = SelectQuery(
            columns = listOf(Expression.Star),
            from = "_aether_tasks",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "queue"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue(queue))
            ),
            orderBy = listOf(OrderByClause(Expression.ColumnRef(column = "created_at"), OrderDirection.ASC)),
            limit = limit
        )
        
        return driver.executeQuery(query).map { rowToTask(it) }
    }

    override suspend fun deleteOlderThan(timestamp: Long, status: TaskStatus): Int {
        val driver = DatabaseDriverRegistry.driver
        
        val deleteQuery = DeleteQuery(
            table = "_aether_tasks",
            where = WhereClause.And(listOf(
                WhereClause.Condition(
                    left = Expression.ColumnRef(column = "status"),
                    operator = ComparisonOperator.EQUALS,
                    right = Expression.Literal(SqlValue.StringValue(status.name))
                ),
                WhereClause.Condition(
                    left = Expression.ColumnRef(column = "completed_at"),
                    operator = ComparisonOperator.LESS_THAN,
                    right = Expression.Literal(SqlValue.LongValue(timestamp))
                )
            ))
        )
        
        return driver.executeUpdate(deleteQuery)
    }

    override suspend fun countByStatus(status: TaskStatus): Long {
        val driver = DatabaseDriverRegistry.driver
        
        val query = SelectQuery(
            columns = listOf(Expression.FunctionCall("COUNT", listOf(Expression.Star))),
            from = "_aether_tasks",
            where = WhereClause.Condition(
                left = Expression.ColumnRef(column = "status"),
                operator = ComparisonOperator.EQUALS,
                right = Expression.Literal(SqlValue.StringValue(status.name))
            )
        )
        
        val rows = driver.executeQuery(query)
        return rows.firstOrNull()?.getLong("count") ?: 0L
    }

    override suspend fun releaseStale(olderThan: Long): Int {
        val driver = DatabaseDriverRegistry.driver
        
        val updateQuery = UpdateQuery(
            table = "_aether_tasks",
            assignments = mapOf(
                "status" to Expression.Literal(SqlValue.StringValue(TaskStatus.PENDING.name)),
                "worker_id" to Expression.Literal(SqlValue.NullValue),
                "started_at" to Expression.Literal(SqlValue.NullValue)
            ),
            where = WhereClause.And(listOf(
                WhereClause.Condition(
                    left = Expression.ColumnRef(column = "status"),
                    operator = ComparisonOperator.EQUALS,
                    right = Expression.Literal(SqlValue.StringValue(TaskStatus.PROCESSING.name))
                ),
                WhereClause.Condition(
                    left = Expression.ColumnRef(column = "started_at"),
                    operator = ComparisonOperator.LESS_THAN,
                    right = Expression.Literal(SqlValue.LongValue(olderThan))
                )
            ))
        )
        
        return driver.executeUpdate(updateQuery)
    }
    
    private fun rowToTask(row: Row): TaskRecord {
        val argsStr = row.getString("args") ?: "{}"
        val resultStr = row.getString("result")
        val metadataStr = row.getString("metadata") ?: "{}"
        
        return TaskRecord(
            id = row.getString("id") ?: "",
            name = row.getString("name") ?: "",
            queue = row.getString("queue") ?: "default",
            args = json.parseToJsonElement(argsStr),
            status = TaskStatus.valueOf(row.getString("status") ?: "PENDING"),
            priority = TaskPriority.entries.find { it.value == row.getInt("priority") } ?: TaskPriority.NORMAL,
            createdAt = row.getLong("created_at") ?: 0L,
            scheduledFor = row.getLong("scheduled_for") ?: 0L,
            startedAt = row.getLong("started_at"),
            completedAt = row.getLong("completed_at"),
            result = resultStr?.let { json.parseToJsonElement(it) },
            error = row.getString("error"),
            stackTrace = row.getString("stack_trace"),
            retryCount = row.getInt("retry_count") ?: 0,
            maxRetries = row.getInt("max_retries") ?: 3,
            workerId = row.getString("worker_id"),
            timeoutMillis = row.getLong("timeout_millis") ?: 300_000,
            metadata = json.decodeFromString(metadataStr)
        )
    }
}

/**
 * Migration for creating the _aether_tasks table.
 */
object TaskTableMigration : Migration {
    override val version: Long = 20260104000001L
    override val description: String = "Create _aether_tasks table"
    
    override fun up(): String = """
        CREATE TABLE IF NOT EXISTS _aether_tasks (
            id VARCHAR(64) PRIMARY KEY,
            name VARCHAR(255) NOT NULL,
            queue VARCHAR(64) NOT NULL DEFAULT 'default',
            args TEXT NOT NULL,
            status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
            priority INTEGER NOT NULL DEFAULT 5,
            created_at BIGINT NOT NULL,
            scheduled_for BIGINT NOT NULL,
            started_at BIGINT,
            completed_at BIGINT,
            result TEXT,
            error TEXT,
            stack_trace TEXT,
            retry_count INTEGER NOT NULL DEFAULT 0,
            max_retries INTEGER NOT NULL DEFAULT 3,
            worker_id VARCHAR(64),
            timeout_millis BIGINT NOT NULL DEFAULT 300000,
            metadata TEXT
        );
        
        CREATE INDEX IF NOT EXISTS idx_aether_tasks_status ON _aether_tasks(status);
        CREATE INDEX IF NOT EXISTS idx_aether_tasks_queue ON _aether_tasks(queue);
        CREATE INDEX IF NOT EXISTS idx_aether_tasks_scheduled ON _aether_tasks(scheduled_for);
        CREATE INDEX IF NOT EXISTS idx_aether_tasks_priority ON _aether_tasks(priority DESC, created_at ASC);
    """.trimIndent()
    
    override fun down(): String = """
        DROP TABLE IF EXISTS _aether_tasks;
    """.trimIndent()
}
