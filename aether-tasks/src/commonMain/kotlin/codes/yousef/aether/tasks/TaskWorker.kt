package codes.yousef.aether.tasks

import codes.yousef.aether.signals.Signal
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Signals emitted by the task worker.
 */
object TaskSignals {
    /** Fired when a task starts processing */
    val taskStarted = Signal<TaskRecord>()
    
    /** Fired when a task completes successfully */
    val taskCompleted = Signal<TaskRecord>()
    
    /** Fired when a task fails */
    val taskFailed = Signal<TaskRecord>()
    
    /** Fired when a task is retried */
    val taskRetried = Signal<TaskRecord>()
}

/**
 * Configuration for the task worker.
 */
data class WorkerConfig(
    /** Number of concurrent tasks to process */
    val concurrency: Int = 4,
    
    /** Queues to poll for tasks */
    val queues: List<String> = listOf("default"),
    
    /** Polling interval when no tasks are available */
    val pollInterval: Duration = 1.seconds,
    
    /** Interval for checking scheduled tasks */
    val scheduleCheckInterval: Duration = 5.seconds,
    
    /** Interval for releasing stale tasks */
    val staleCheckInterval: Duration = 60.seconds,
    
    /** Timeout for considering a task stale */
    val staleTimeout: Duration = Duration.parse("5m"),
    
    /** Retry configuration */
    val retryConfig: RetryConfig = RetryConfig(),
    
    /** Whether to process scheduled tasks */
    val processScheduled: Boolean = true,
    
    /** Whether to release stale tasks */
    val releaseStale: Boolean = true
)

/**
 * Worker that processes background tasks.
 *
 * Example:
 * ```kotlin
 * // Create worker
 * val worker = TaskWorker(
 *     store = DatabaseTaskStore(driver),
 *     config = WorkerConfig(
 *         concurrency = 4,
 *         queues = listOf("default", "high-priority")
 *     )
 * )
 *
 * // Start in a coroutine scope
 * scope.launch {
 *     worker.start()
 * }
 *
 * // Stop gracefully
 * worker.stop()
 * ```
 */
class TaskWorker(
    private val store: TaskStore,
    private val config: WorkerConfig = WorkerConfig()
) {
    private val workerId = generateWorkerId()
    private val running = atomic(false)
    private var workerJob: Job? = null
    private val activeJobs = atomic(0)
    
    /**
     * Start the worker.
     * This suspends until stop() is called.
     */
    suspend fun start() {
        if (!running.compareAndSet(expect = false, update = true)) {
            throw IllegalStateException("Worker is already running")
        }
        
        coroutineScope {
            workerJob = coroutineContext.job
            
            // Launch poll workers for each queue
            val pollJobs = config.queues.map { queue ->
                launch { pollLoop(queue) }
            }
            
            // Launch scheduled task checker
            val scheduledJob = if (config.processScheduled) {
                launch { scheduledLoop() }
            } else null
            
            // Launch stale task releaser
            val staleJob = if (config.releaseStale) {
                launch { staleReleaseLoop() }
            } else null
            
            // Wait for all jobs
            pollJobs.forEach { it.join() }
            scheduledJob?.join()
            staleJob?.join()
        }
    }

    /**
     * Stop the worker gracefully.
     * Waits for active tasks to complete.
     */
    suspend fun stop(timeout: Duration = 30.seconds) {
        running.value = false
        
        // Wait for active jobs to complete
        withTimeoutOrNull(timeout) {
            while (activeJobs.value > 0) {
                delay(100)
            }
        }
        
        workerJob?.cancelAndJoin()
    }

    /**
     * Check if the worker is running.
     */
    val isRunning: Boolean get() = running.value

    /**
     * Get the number of active jobs.
     */
    val activeJobCount: Int get() = activeJobs.value

    private suspend fun pollLoop(queue: String) {
        while (running.value) {
            // Check if we can accept more work
            if (activeJobs.value >= config.concurrency) {
                delay(100)
                continue
            }
            
            try {
                val task = store.claimNext(queue, workerId)
                if (task != null) {
                    activeJobs.incrementAndGet()
                    try {
                        processTask(task)
                    } finally {
                        activeJobs.decrementAndGet()
                    }
                } else {
                    delay(config.pollInterval)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Log error and continue
                delay(config.pollInterval)
            }
        }
    }

    private suspend fun scheduledLoop() {
        while (running.value) {
            try {
                val now = Clock.System.now().toEpochMilliseconds()
                val scheduled = store.getByStatus(TaskStatus.SCHEDULED, limit = 100)
                
                for (task in scheduled) {
                    if (task.scheduledFor <= now) {
                        store.update(task.copy(status = TaskStatus.PENDING))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Log and continue
            }
            
            delay(config.scheduleCheckInterval)
        }
    }

    private suspend fun staleReleaseLoop() {
        while (running.value) {
            try {
                val cutoff = Clock.System.now().toEpochMilliseconds() - config.staleTimeout.inWholeMilliseconds
                store.releaseStale(cutoff)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Log and continue
            }
            
            delay(config.staleCheckInterval)
        }
    }

    private suspend fun processTask(task: TaskRecord) {
        val startTime = Clock.System.now().toEpochMilliseconds()
        
        // Mark as processing
        val processingTask = task.copy(
            status = TaskStatus.PROCESSING,
            startedAt = startTime
        )
        store.update(processingTask)
        
        TaskSignals.taskStarted.send(processingTask)
        
        try {
            // Execute with timeout
            val result = withTimeout(task.timeoutMillis) {
                TaskRegistry.execute(task.name, task.args)
            }
            
            // Success
            val completedTask = processingTask.copy(
                status = TaskStatus.COMPLETED,
                completedAt = Clock.System.now().toEpochMilliseconds(),
                result = result
            )
            store.update(completedTask)
            
            TaskSignals.taskCompleted.send(completedTask)
            
        } catch (e: CancellationException) {
            // Cancelled - re-throw
            throw e
        } catch (e: Exception) {
            handleTaskFailure(processingTask, e)
        }
    }

    private suspend fun handleTaskFailure(task: TaskRecord, error: Throwable) {
        val shouldRetry = task.retryCount < task.maxRetries
        
        if (shouldRetry) {
            val delay = config.retryConfig.calculateDelay(task.retryCount)
            val retryTask = task.copy(
                status = TaskStatus.SCHEDULED,
                scheduledFor = Clock.System.now().toEpochMilliseconds() + delay,
                retryCount = task.retryCount + 1,
                error = error.message,
                stackTrace = error.stackTraceToString(),
                workerId = null
            )
            store.update(retryTask)
            
            TaskSignals.taskRetried.send(retryTask)
        } else {
            val failedTask = task.copy(
                status = TaskStatus.FAILED,
                completedAt = Clock.System.now().toEpochMilliseconds(),
                error = error.message,
                stackTrace = error.stackTraceToString()
            )
            store.update(failedTask)
            
            TaskSignals.taskFailed.send(failedTask)
        }
    }
    
    private fun generateWorkerId(): String {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val random = (Random.nextDouble() * 1_000_000).toLong()
        return "worker_${timestamp}_${random.toString(36)}"
    }
}

