package codes.yousef.aether.tasks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.math.pow
import kotlin.random.Random

/**
 * Status of a background task.
 */
enum class TaskStatus {
    /** Task is waiting to be executed */
    PENDING,
    /** Task is scheduled for future execution */
    SCHEDULED,
    /** Task is currently being executed */
    PROCESSING,
    /** Task completed successfully */
    COMPLETED,
    /** Task failed with an error */
    FAILED,
    /** Task was manually cancelled */
    CANCELLED,
    /** Task is being retried after failure */
    RETRYING
}

/**
 * Priority levels for task execution.
 */
enum class TaskPriority(val value: Int) {
    LOW(0),
    NORMAL(5),
    HIGH(10),
    CRITICAL(15)
}

/**
 * Represents a task record in the database.
 */
@Serializable
data class TaskRecord(
    /** Unique task ID */
    val id: String,
    
    /** Task name (function identifier) */
    val name: String,
    
    /** Queue name for routing */
    val queue: String = "default",
    
    /** Serialized arguments as JSON */
    val args: JsonElement,
    
    /** Current task status */
    val status: TaskStatus = TaskStatus.PENDING,
    
    /** Task priority */
    val priority: TaskPriority = TaskPriority.NORMAL,
    
    /** Timestamp when task was created (epoch millis) */
    val createdAt: Long,
    
    /** Timestamp when task should be executed (epoch millis) */
    val scheduledFor: Long,
    
    /** Timestamp when task started processing (epoch millis) */
    val startedAt: Long? = null,
    
    /** Timestamp when task completed (epoch millis) */
    val completedAt: Long? = null,
    
    /** Serialized result as JSON */
    val result: JsonElement? = null,
    
    /** Error message if task failed */
    val error: String? = null,
    
    /** Stack trace if task failed */
    val stackTrace: String? = null,
    
    /** Number of retry attempts */
    val retryCount: Int = 0,
    
    /** Maximum retry attempts */
    val maxRetries: Int = 3,
    
    /** Worker ID that claimed this task */
    val workerId: String? = null,
    
    /** Timeout in milliseconds */
    val timeoutMillis: Long = 300_000, // 5 minutes default
    
    /** Metadata for task tracking */
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Configuration for task retry behavior.
 */
@Serializable
data class RetryConfig(
    /** Maximum number of retries */
    val maxRetries: Int = 3,
    
    /** Base delay between retries in milliseconds */
    val baseDelayMillis: Long = 1000,
    
    /** Multiplier for exponential backoff */
    val backoffMultiplier: Double = 2.0,
    
    /** Maximum delay between retries in milliseconds */
    val maxDelayMillis: Long = 60_000,
    
    /** Whether to use jitter in backoff calculation */
    val useJitter: Boolean = true
) {
    init {
        require(maxRetries in 0..100) { "Maximum retries must be 0..100" }
        require(baseDelayMillis in 1..86_400_000) { "Base retry delay must be 1ms..24h" }
        require(backoffMultiplier.isFinite() && backoffMultiplier >= 1.0) {
            "Backoff multiplier must be finite and at least 1"
        }
        require(maxDelayMillis in baseDelayMillis..86_400_000) {
            "Maximum retry delay must be between the base delay and 24h"
        }
    }
    /**
     * Calculate delay for a specific retry attempt.
     */
    fun calculateDelay(attempt: Int): Long {
        require(attempt >= 0) { "Retry attempt must be non-negative" }
        val exponentialDelay = (baseDelayMillis * backoffMultiplier.pow(attempt.toDouble())).toLong()
        val capped = minOf(exponentialDelay, maxDelayMillis)
        return if (useJitter) {
            (capped * (0.5 + Random.nextDouble() * 0.5)).toLong()
        } else {
            capped
        }
    }
}

/**
 * Result of task execution.
 */
sealed class TaskResult {
    /** Task completed successfully */
    data class Success(val result: JsonElement?) : TaskResult()
    
    /** Task failed with an error */
    data class Failure(
        val error: Throwable,
        val shouldRetry: Boolean = true
    ) : TaskResult()
    
    /** Task was cancelled */
    data object Cancelled : TaskResult()
}

/**
 * Options for queueing a task.
 */
data class TaskOptions(
    /** Queue name */
    var queue: String = "default",
    
    /** Task priority */
    var priority: TaskPriority = TaskPriority.NORMAL,
    
    /** Delay before executing (milliseconds) */
    var delayMillis: Long = 0,
    
    /** Scheduled time for execution (epoch millis) */
    var scheduledFor: Long? = null,
    
    /** Maximum retries */
    var maxRetries: Int = 3,
    
    /** Timeout in milliseconds */
    var timeoutMillis: Long = 300_000,
    
    /** Additional metadata */
    var metadata: Map<String, String> = emptyMap()
)
