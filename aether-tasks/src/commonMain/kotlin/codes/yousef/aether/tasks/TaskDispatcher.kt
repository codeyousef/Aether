package codes.yousef.aether.tasks

import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import kotlin.random.Random

/**
 * Interface for task storage backends.
 */
interface TaskStore {
    /**
     * Save a new task to the store.
     */
    suspend fun save(task: TaskRecord): TaskRecord
    
    /**
     * Get a task by ID.
     */
    suspend fun getById(id: String): TaskRecord?
    
    /**
     * Claim the next available task for processing.
     * Returns null if no tasks are available.
     *
     * @param queue Queue name to poll
     * @param workerId ID of the worker claiming the task
     */
    suspend fun claimNext(queue: String, workerId: String): TaskRecord?
    
    /**
     * Update a task's status and result.
     */
    suspend fun update(task: TaskRecord): TaskRecord
    
    /**
     * Get tasks by status.
     */
    suspend fun getByStatus(status: TaskStatus, limit: Int = 100): List<TaskRecord>
    
    /**
     * Get tasks in a queue.
     */
    suspend fun getByQueue(queue: String, limit: Int = 100): List<TaskRecord>
    
    /**
     * Delete completed tasks older than the specified timestamp.
     */
    suspend fun deleteOlderThan(timestamp: Long, status: TaskStatus = TaskStatus.COMPLETED): Int
    
    /**
     * Count tasks by status.
     */
    suspend fun countByStatus(status: TaskStatus): Long
    
    /**
     * Release stale tasks (tasks that were claimed but not completed).
     * Used for recovery when workers crash.
     *
     * @param olderThan Tasks claimed before this timestamp will be released
     */
    suspend fun releaseStale(olderThan: Long): Int
}

/**
 * Dispatches tasks to a store for async execution.
 *
 * Example:
 * ```kotlin
 * // Initialize the dispatcher
 * TaskDispatcher.initialize(DatabaseTaskStore(driver))
 *
 * // Queue a task
 * val taskId = TaskDispatcher.enqueue("send_email", SendEmailArgs(...))
 *
 * // Queue a delayed task
 * val delayedId = TaskDispatcher.enqueue("cleanup", CleanupArgs(...)) {
 *     delayMillis = 60_000 // Run in 1 minute
 * }
 *
 * // Queue a scheduled task
 * val scheduledId = TaskDispatcher.enqueue("report", ReportArgs(...)) {
 *     scheduledFor = tomorrow9am
 * }
 * ```
 */
object TaskDispatcher {
    private var store: TaskStore? = null
    
    /**
     * Initialize the dispatcher with a task store.
     */
    fun initialize(taskStore: TaskStore) {
        store = taskStore
    }
    
    private fun requireStore(): TaskStore {
        return store ?: throw IllegalStateException(
            "TaskDispatcher not initialized. Call TaskDispatcher.initialize(store) first."
        )
    }

    /**
     * Enqueue a task for async execution.
     *
     * @param name Task name (must be registered in TaskRegistry)
     * @param args Task arguments
     * @param configure Options configuration
     * @return Task ID
     */
    suspend fun <A> enqueue(
        name: String,
        args: A,
        argSerializer: KSerializer<A>,
        configure: TaskOptions.() -> Unit = {}
    ): String {
        val store = requireStore()
        
        if (!TaskRegistry.isRegistered(name)) {
            throw TaskNotFoundException("Task not registered: $name. Register it first with TaskRegistry.register()")
        }
        
        val options = TaskOptions().apply(configure)
        val now = Clock.System.now().toEpochMilliseconds()
        
        val scheduledFor = options.scheduledFor 
            ?: (now + options.delayMillis)
        
        val argsJson = TaskRegistry.json.encodeToJsonElement(argSerializer, args)
        
        val task = TaskRecord(
            id = generateTaskId(),
            name = name,
            queue = options.queue,
            args = argsJson,
            status = if (scheduledFor > now) TaskStatus.SCHEDULED else TaskStatus.PENDING,
            priority = options.priority,
            createdAt = now,
            scheduledFor = scheduledFor,
            maxRetries = options.maxRetries,
            timeoutMillis = options.timeoutMillis,
            metadata = options.metadata
        )
        
        store.save(task)
        return task.id
    }

    /**
     * Get task status.
     */
    suspend fun getStatus(taskId: String): TaskStatus? {
        return requireStore().getById(taskId)?.status
    }

    /**
     * Get task record.
     */
    suspend fun getTask(taskId: String): TaskRecord? {
        return requireStore().getById(taskId)
    }

    /**
     * Cancel a pending task.
     */
    suspend fun cancel(taskId: String): Boolean {
        val store = requireStore()
        val task = store.getById(taskId) ?: return false
        
        if (task.status in listOf(TaskStatus.PENDING, TaskStatus.SCHEDULED)) {
            store.update(task.copy(status = TaskStatus.CANCELLED))
            return true
        }
        
        return false
    }

    /**
     * Get queue statistics.
     */
    suspend fun getStats(): TaskStats {
        val store = requireStore()
        return TaskStats(
            pending = store.countByStatus(TaskStatus.PENDING),
            scheduled = store.countByStatus(TaskStatus.SCHEDULED),
            processing = store.countByStatus(TaskStatus.PROCESSING),
            completed = store.countByStatus(TaskStatus.COMPLETED),
            failed = store.countByStatus(TaskStatus.FAILED),
            cancelled = store.countByStatus(TaskStatus.CANCELLED)
        )
    }
    
    private fun generateTaskId(): String {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val random = (Random.nextDouble() * 1_000_000).toLong()
        return "task_${timestamp}_${random.toString(36)}"
    }
}

/**
 * Task queue statistics.
 */
data class TaskStats(
    val pending: Long,
    val scheduled: Long,
    val processing: Long,
    val completed: Long,
    val failed: Long,
    val cancelled: Long
) {
    val total: Long get() = pending + scheduled + processing + completed + failed + cancelled
}

/**
 * Enqueue a task with reified type parameter.
 */
suspend inline fun <reified A> TaskDispatcher.enqueue(
    name: String,
    args: A,
    noinline configure: TaskOptions.() -> Unit = {}
): String {
    return enqueue(name, args, serializer<A>(), configure)
}

/**
 * Extension to queue a task by referencing the registered task directly.
 */
suspend fun <A, R> RegisteredTask<A, R>.delay(
    args: A,
    configure: TaskOptions.() -> Unit = {}
): String {
    return TaskDispatcher.enqueue(name, args, argSerializer, configure)
}
