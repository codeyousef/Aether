package codes.yousef.aether.tasks

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class TaskWorkerConcurrencyTest {
    @AfterTest
    fun clearRegistry() = runTest {
        TaskRegistry.clear()
    }

    @Test
    fun `worker configuration rejects dead or unbounded loops`(): Unit {
        assertFailsWith<IllegalArgumentException> { WorkerConfig(concurrency = 0) }
        assertFailsWith<IllegalArgumentException> { WorkerConfig(queues = emptyList()) }
        assertFailsWith<IllegalArgumentException> { WorkerConfig(pollInterval = 0.milliseconds) }
    }

    @Test
    fun `dispatcher applies registered defaults and mutable per-call overrides`() = runTest {
        TaskRegistry.register<Int, Int>(
            name = "task-options-test",
            options = TaskOptions(queue = "registered", maxRetries = 7, timeoutMillis = 10_000)
        ) { it }
        val store = InMemoryTaskStore()
        TaskDispatcher.initialize(store)

        val defaultId = TaskDispatcher.enqueue("task-options-test", 1)
        val defaultTask = requireNotNull(store.getById(defaultId))
        assertEquals("registered", defaultTask.queue)
        assertEquals(7, defaultTask.maxRetries)
        assertEquals(10_000, defaultTask.timeoutMillis)

        val overrideId = TaskDispatcher.enqueue("task-options-test", 2) {
            queue = "override"
            maxRetries = 2
            timeoutMillis = 5_000
        }
        val overridden = requireNotNull(store.getById(overrideId))
        assertEquals("override", overridden.queue)
        assertEquals(2, overridden.maxRetries)
        assertEquals(5_000, overridden.timeoutMillis)

        assertFailsWith<IllegalArgumentException> {
            TaskDispatcher.enqueue("task-options-test", 3) { delayMillis = -1 }
        }
    }


    @Test
    fun `concurrency cap is global across queues`() = runTest {
        val active = atomic(0)
        val maximum = atomic(0)
        val completed = atomic(0)
        val allCompleted = CompletableDeferred<Unit>()
        TaskRegistry.register<Int, Int>("bounded-worker-test") { value ->
            val current = active.incrementAndGet()
            maximum.update { maxOf(it, current) }
            try {
                delay(100)
                value
            } finally {
                active.decrementAndGet()
                if (completed.incrementAndGet() == 6) allCompleted.complete(Unit)
            }
        }
        val now = Clock.System.now().toEpochMilliseconds()
        val store = InMemoryTaskStore()
        repeat(6) { index ->
            store.save(TaskRecord(
                id = "task-$index",
                name = "bounded-worker-test",
                queue = "queue-${index % 3}",
                args = JsonPrimitive(index),
                createdAt = now + index,
                scheduledFor = now
            ))
        }
        val worker = TaskWorker(
            store,
            WorkerConfig(
                concurrency = 2,
                queues = listOf("queue-0", "queue-1", "queue-2"),
                pollInterval = 10.milliseconds,
                processScheduled = false,
                releaseStale = false
            )
        )
        val running = launch { worker.start() }
        withTimeout(5_000) { allCompleted.await() }
        worker.stop()
        running.join()

        assertEquals(2, maximum.value)
        assertEquals(6, store.countByStatus(TaskStatus.COMPLETED))
    }
}
