package codes.yousef.aether.example

import codes.yousef.aether.tasks.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Test task arguments.
 */
@Serializable
data class EmailTaskArgs(
    val to: String,
    val subject: String,
    val body: String
)

/**
 * Test task result.
 */
@Serializable
data class EmailTaskResult(
    val messageId: String,
    val sentAt: Long
)

/**
 * E2E tests for Aether Tasks - persistent background job queue.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TasksE2ETest {
    
    private lateinit var store: InMemoryTaskStore
    
    @BeforeEach
    fun setup() = runBlocking {
        store = InMemoryTaskStore()
        TaskDispatcher.initialize(store)
        
        // Register test tasks
        TaskRegistry.clear()
        TaskRegistry.register<EmailTaskArgs, EmailTaskResult>("send_email") { args ->
            // Simulate email sending
            delay(10)
            EmailTaskResult(
                messageId = "msg_${System.currentTimeMillis()}",
                sentAt = System.currentTimeMillis()
            )
        }
        TaskRegistry.register<String, String>("echo") { args ->
            args.uppercase()
        }
        TaskRegistry.register<Unit, Unit>("noop") { }
    }
    
    @Test
    fun `test enqueue creates a pending task`() = runBlocking {
        val taskId = TaskDispatcher.enqueue(
            name = "send_email",
            args = EmailTaskArgs("test@example.com", "Hello", "World"),
            argSerializer = EmailTaskArgs.serializer()
        )
        
        assertNotNull(taskId)
        assertTrue(taskId.startsWith("task_"))
        
        val status = TaskDispatcher.getStatus(taskId)
        assertEquals(TaskStatus.PENDING, status)
    }
    
    @Test
    fun `test getTask returns task record`() = runBlocking {
        val taskId = TaskDispatcher.enqueue(
            name = "send_email",
            args = EmailTaskArgs("user@example.com", "Test", "Body"),
            argSerializer = EmailTaskArgs.serializer()
        )
        
        val task = TaskDispatcher.getTask(taskId)
        assertNotNull(task)
        assertEquals(taskId, task.id)
        assertEquals("send_email", task.name)
        assertEquals("default", task.queue)
        assertEquals(TaskStatus.PENDING, task.status)
    }
    
    @Test
    fun `test cancel task`() = runBlocking {
        val taskId = TaskDispatcher.enqueue(
            name = "echo",
            args = "test",
            argSerializer = String.serializer()
        )
        
        // Cancel the task
        val cancelled = TaskDispatcher.cancel(taskId)
        assertTrue(cancelled)
        
        // Verify status
        val status = TaskDispatcher.getStatus(taskId)
        assertEquals(TaskStatus.CANCELLED, status)
    }
    
    @Test
    fun `test cannot cancel processing task`() = runBlocking {
        val taskId = TaskDispatcher.enqueue(
            name = "echo",
            args = "test",
            argSerializer = String.serializer()
        )
        
        // Simulate task being claimed for processing
        val task = store.getById(taskId)!!
        store.update(task.copy(status = TaskStatus.PROCESSING))
        
        // Try to cancel - should fail
        val cancelled = TaskDispatcher.cancel(taskId)
        assertTrue(!cancelled)
    }
    
    @Test
    fun `test task default priority`() = runBlocking {
        val taskId = TaskDispatcher.enqueue(
            name = "echo",
            args = "test",
            argSerializer = String.serializer()
        )
        
        val task = TaskDispatcher.getTask(taskId)
        assertNotNull(task)
        assertEquals(TaskPriority.NORMAL, task.priority)
    }
    
    @Test
    fun `test getStats returns queue statistics`() = runBlocking {
        // Enqueue several tasks
        repeat(3) {
            TaskDispatcher.enqueue("echo", "pending-$it", String.serializer())
        }
        
        // Create a cancelled task
        val cancelId = TaskDispatcher.enqueue("echo", "to-cancel", String.serializer())
        TaskDispatcher.cancel(cancelId)
        
        val stats = TaskDispatcher.getStats()
        
        assertEquals(3, stats.pending)
        assertEquals(1, stats.cancelled)
        assertEquals(0, stats.processing)
        assertEquals(0, stats.completed)
        assertEquals(0, stats.failed)
    }
    
    @Test
    fun `test getTask returns null for unknown task`() = runBlocking {
        val task = TaskDispatcher.getTask("nonexistent-task-id")
        assertNull(task)
    }
    
    @Test
    fun `test getStatus returns null for unknown task`() = runBlocking {
        val status = TaskDispatcher.getStatus("nonexistent-task-id")
        assertNull(status)
    }
    
    @Test
    fun `test task default maxRetries`() = runBlocking {
        val taskId = TaskDispatcher.enqueue(
            name = "echo",
            args = "retry-test",
            argSerializer = String.serializer()
        )
        
        val task = TaskDispatcher.getTask(taskId)
        assertNotNull(task)
        assertEquals(3, task.maxRetries)
    }
    
    @Test
    fun `test task default timeout`() = runBlocking {
        val taskId = TaskDispatcher.enqueue(
            name = "echo",
            args = "timeout-test",
            argSerializer = String.serializer()
        )
        
        val task = TaskDispatcher.getTask(taskId)
        assertNotNull(task)
        assertEquals(300_000, task.timeoutMillis)
    }
    
    @Test
    fun `test TaskRegistry isRegistered`() {
        assertTrue(TaskRegistry.isRegistered("send_email"))
        assertTrue(TaskRegistry.isRegistered("echo"))
        assertTrue(!TaskRegistry.isRegistered("unknown_task"))
    }
    
    @Test
    fun `test InMemoryTaskStore getByStatus`() = runBlocking {
        // Create tasks with different statuses
        TaskDispatcher.enqueue("echo", "p1", String.serializer())
        TaskDispatcher.enqueue("echo", "p2", String.serializer())
        
        val cancelId = TaskDispatcher.enqueue("echo", "c1", String.serializer())
        TaskDispatcher.cancel(cancelId)
        
        val pendingTasks = store.getByStatus(TaskStatus.PENDING)
        val cancelledTasks = store.getByStatus(TaskStatus.CANCELLED)
        
        assertEquals(2, pendingTasks.size)
        assertEquals(1, cancelledTasks.size)
    }
    
    @Test
    fun `test InMemoryTaskStore getByQueue`() = runBlocking {
        TaskDispatcher.enqueue("echo", "d1", String.serializer())
        TaskDispatcher.enqueue("echo", "d2", String.serializer())
        
        val defaultTasks = store.getByQueue("default")
        
        assertEquals(2, defaultTasks.size)
    }
    
    @Test
    fun `test InMemoryTaskStore claimNext`() = runBlocking {
        TaskDispatcher.enqueue("echo", "claim-test", String.serializer())
        
        // Claim the task
        val claimed = store.claimNext("default", "worker-1")
        
        assertNotNull(claimed)
        assertEquals(TaskStatus.PROCESSING, claimed.status)
        assertEquals("worker-1", claimed.workerId)
        
        // Second claim should return null (no more tasks)
        val secondClaim = store.claimNext("default", "worker-2")
        assertNull(secondClaim)
    }
    
    @Test
    fun `test InMemoryTaskStore countByStatus`() = runBlocking {
        repeat(5) {
            TaskDispatcher.enqueue("echo", "count-$it", String.serializer())
        }
        
        val count = store.countByStatus(TaskStatus.PENDING)
        assertEquals(5, count)
    }
    
    @Test
    fun `test task IDs are unique`() = runBlocking {
        val ids = (1..100).map {
            TaskDispatcher.enqueue("echo", "unique-$it", String.serializer())
        }
        
        // All IDs should be unique
        assertEquals(100, ids.toSet().size)
    }
    
    @Test
    fun `test TaskStatus enum values`() {
        assertEquals(7, TaskStatus.entries.size)
        assertTrue(TaskStatus.entries.contains(TaskStatus.PENDING))
        assertTrue(TaskStatus.entries.contains(TaskStatus.SCHEDULED))
        assertTrue(TaskStatus.entries.contains(TaskStatus.PROCESSING))
        assertTrue(TaskStatus.entries.contains(TaskStatus.COMPLETED))
        assertTrue(TaskStatus.entries.contains(TaskStatus.FAILED))
        assertTrue(TaskStatus.entries.contains(TaskStatus.CANCELLED))
        assertTrue(TaskStatus.entries.contains(TaskStatus.RETRYING))
    }
    
    @Test
    fun `test TaskPriority enum values`() {
        assertEquals(4, TaskPriority.entries.size)
        assertEquals(0, TaskPriority.LOW.value)
        assertEquals(5, TaskPriority.NORMAL.value)
        assertEquals(10, TaskPriority.HIGH.value)
        assertEquals(15, TaskPriority.CRITICAL.value)
    }
    
    @Test
    fun `test TaskRecord properties`() = runBlocking {
        val taskId = TaskDispatcher.enqueue(
            name = "send_email",
            args = EmailTaskArgs("user@test.com", "Subject", "Body"),
            argSerializer = EmailTaskArgs.serializer()
        )
        
        val task = TaskDispatcher.getTask(taskId)!!
        
        // Verify required properties
        assertTrue(task.id.isNotEmpty())
        assertEquals("send_email", task.name)
        assertEquals("default", task.queue)
        assertEquals(TaskPriority.NORMAL, task.priority)
        assertTrue(task.createdAt > 0)
        assertTrue(task.scheduledFor > 0)
        assertNull(task.startedAt)
        assertNull(task.completedAt)
        assertNull(task.result)
        assertNull(task.error)
        assertNull(task.stackTrace)
        assertEquals(0, task.retryCount)
        assertEquals(3, task.maxRetries)
        assertNull(task.workerId)
        assertEquals(300_000, task.timeoutMillis)
        assertTrue(task.metadata.isEmpty())
    }
    
    @Test
    fun `test TaskStats total calculation`() = runBlocking {
        // Create tasks in different states
        TaskDispatcher.enqueue("echo", "p1", String.serializer())
        TaskDispatcher.enqueue("echo", "p2", String.serializer())
        
        val cancelId = TaskDispatcher.enqueue("echo", "c1", String.serializer())
        TaskDispatcher.cancel(cancelId)
        
        val stats = TaskDispatcher.getStats()
        
        // Total should be 2 pending + 1 cancelled = 3
        assertEquals(3, stats.total)
    }
    
    @Test
    fun `test RetryConfig calculateDelay`() {
        val config = RetryConfig(
            maxRetries = 3,
            baseDelayMillis = 1000,
            backoffMultiplier = 2.0,
            maxDelayMillis = 60_000,
            useJitter = false  // Disable jitter for deterministic tests
        )
        
        assertEquals(1000L, config.calculateDelay(0))  // 1000 * 2^0 = 1000
        assertEquals(2000L, config.calculateDelay(1))  // 1000 * 2^1 = 2000
        assertEquals(4000L, config.calculateDelay(2))  // 1000 * 2^2 = 4000
        assertEquals(8000L, config.calculateDelay(3))  // 1000 * 2^3 = 8000
    }
    
    @Test
    fun `test RetryConfig max delay cap`() {
        val config = RetryConfig(
            baseDelayMillis = 10_000,
            backoffMultiplier = 10.0,
            maxDelayMillis = 30_000,
            useJitter = false
        )
        
        // Without cap: 10_000 * 10^2 = 1_000_000
        // With cap: should be 30_000
        val delay = config.calculateDelay(2)
        assertEquals(30_000L, delay)
    }
    
    @Test
    fun `test InMemoryTaskStore releaseStale`() = runBlocking {
        // Enqueue and claim a task
        val taskId = TaskDispatcher.enqueue("echo", "stale-test", String.serializer())
        store.claimNext("default", "worker-1")
        
        // Release stale tasks older than now + 1 second (all tasks)
        val released = store.releaseStale(System.currentTimeMillis() + 1000)
        
        assertEquals(1, released)
        
        // Task should be back to pending
        val task = store.getById(taskId)
        assertNotNull(task)
        assertEquals(TaskStatus.PENDING, task.status)
        assertNull(task.workerId)
    }
}
