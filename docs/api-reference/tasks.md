````markdown
# Background Tasks API

The `aether-tasks` module provides a persistent background job queue for async task processing.

## Overview

Background tasks allow you to defer expensive operations (sending emails, processing images, generating reports) to be executed asynchronously. Tasks are persisted to a store, so they survive server restarts.

## Installation

```kotlin
// build.gradle.kts
implementation("codes.yousef.aether:aether-tasks:0.8.0")
```

## Basic Usage

### Registering Task Handlers

```kotlin
import codes.yousef.aether.tasks.*

// Initialize the task system
val taskStore = InMemoryTaskStore()  // Or DatabaseTaskStore for production
TaskDispatcher.initialize(taskStore)
val worker = TaskWorker(taskStore)

// Register a task handler
TaskRegistry.register<SendEmailArgs, EmailResult>("send_email") { args ->
    val result = emailService.send(args.to, args.subject, args.body)
    EmailResult(success = result.delivered, messageId = result.id)
}

// Start the worker
worker.start()
```

### Enqueueing Tasks

```kotlin
@Serializable
data class SendEmailArgs(val to: String, val subject: String, val body: String)

@Serializable  
data class EmailResult(val success: Boolean, val messageId: String?)

// Enqueue a task
val taskId = TaskDispatcher.enqueue(
    name = "send_email",
    args = SendEmailArgs(
        to = "user@example.com",
        subject = "Welcome!",
        body = "Thanks for signing up."
    )
)

// Enqueue with options
val taskId = TaskDispatcher.enqueue("send_email", args) {
    priority = TaskPriority.HIGH
    delayMillis = 5.minutes.inWholeMilliseconds
    maxRetries = 3
}
```

## Task Stores

### InMemoryTaskStore

For development and testing. Tasks are lost on restart.

```kotlin
val store = InMemoryTaskStore()
```

### DatabaseTaskStore

For production. Persists tasks to the database via `aether-db`.

```kotlin
val store = DatabaseTaskStore(driver)

// Run migrations to create the tasks table
store.migrate()
```

### Private-suite leased tasks

Use `DatabaseLeasedTaskStore` and `LeasedTaskWorker` for private-suite work. The older
`TaskDispatcher`/`TaskWorker` model remains for applications whose JSON arguments and results may be
stored in the general task table; it is not the private-data boundary.

```kotlin
val store = DatabaseLeasedTaskStore(driver)
store.migrate()

store.enqueue(
    LeasedTaskRecord(
        id = "job-01",
        queue = "objects",
        state = LeasedTaskState.AVAILABLE,
        availableAtEpochMilliseconds = 0,
        attempts = 0,
        leaseOwner = null,
        leaseUntilEpochMilliseconds = null,
        leaseGeneration = 0,
        operationKey = "object:client-operation-01",
        encryptedPayloadReference = "ciphertext:job-01"
    )
)

val worker = LeasedTaskWorker(
    store = store,
    owner = "worker-01",
    config = LeasedTaskWorkerConfig(concurrency = 8, queues = listOf("objects")),
    executor = LeasedTaskExecutor { task ->
        // Resolve and decrypt task.encryptedPayloadReference in the application-owned store.
        "ciphertext-result:${task.id}"
    }
)
worker.run()
```

Claims are one parameterized `UPDATE ... RETURNING` statement with `FOR UPDATE SKIP LOCKED`.
PostgreSQL `clock_timestamp()` determines availability and lease expiry. Leases last 60 seconds;
workers heartbeat every 20 seconds. Every heartbeat, completion, retry, and release is conditional
on task ID, owner, generation, and applicable state. A stale worker therefore cannot commit after
takeover. Retries use 5, 30, 120, 600, and 3,600 second delays within a 24-hour budget; exhausted
tasks remain visible as `FAILED`, and `replayFailed` retains their operation and ciphertext
references. `LeasedTaskWorker` runs exactly the configured number of loops across all queues.

The leased table stores opaque `payload_ref` and `result_ref` values, never readable arguments,
results, errors, or stack traces.

### Idempotency, outbox, and external effects

`DatabaseReliableWorkStore.executeIdempotent` scopes a receipt by actor/account, operation kind,
and client operation ID. The caller supplies a canonical SHA-256 digest. The application mutation,
encrypted result reference, and outbox rows commit in one injected-driver transaction:

```kotlin
val outcome = reliable.executeIdempotent(
    IdempotencyScope(accountId, "object:create", clientOperationId),
    canonicalDigest,
    retentionSeconds = 86_400
) { tx ->
    objectRepository.insert(tx, encryptedObject)
    IdempotentMutationResult(
        encryptedResultReference = resultReference,
        outboxEvents = listOf(
            OutboxEvent(eventId, "object.created", encryptedEventReference)
        )
    )
}
```

The same key and digest returns the stored reference without re-running the mutation; a different
digest returns `Conflict`. Retention must cover the retry/offline horizon. Outbox claims and
acknowledgements are generation-fenced. Provider-facing effects use
`claimExternalEffect`/`acknowledgeExternalEffect`; acknowledgement is reconciled once, while an
expired send becomes `UNKNOWN_OUTCOME` through `recoverExpiredExternalEffects` and requires
application/provider reconciliation. The API does not claim exactly-once network delivery.

## Task Status

Tasks progress through these states:

| Status | Description |
|--------|-------------|
| `PENDING` | Waiting to be picked up by a worker |
| `SCHEDULED` | Scheduled for future execution |
| `PROCESSING` | Currently being executed |
| `COMPLETED` | Finished successfully |
| `FAILED` | Failed after all retry attempts |
| `CANCELLED` | Manually cancelled |
| `RETRYING` | Failed but will retry |

## Task Priority

Tasks are processed in priority order:

```kotlin
enum class TaskPriority {
    LOW,      // Background, non-urgent
    NORMAL,   // Default priority
    HIGH,     // Important, process soon
    CRITICAL  // Process immediately
}
```

## Retry Configuration

Configure automatic retries with exponential backoff:

```kotlin
val retryConfig = RetryConfig(
    maxRetries = 5,            // Retries after the initial attempt
    baseDelayMillis = 1000,    // Initial delay (1 second)
    backoffMultiplier = 2.0,   // Double delay each retry
    maxDelayMillis = 60_000,   // Cap delay at 1 minute
    useJitter = true           // Add randomness to prevent thundering herd
)

// Delays: 1s, 2s, 4s, 8s, 16s (capped at 60s)
```

## Task Worker

The worker polls the store and executes tasks:

```kotlin
val worker = TaskWorker(
    store = taskStore,
    config = WorkerConfig(
        concurrency = 4,
        pollInterval = 1.seconds,
        retryConfig = RetryConfig(maxRetries = 3)
    )
)

// Start processing
worker.start()

// Graceful shutdown
worker.stop()
```

## Task Signals

Subscribe to task lifecycle events:

```kotlin
import codes.yousef.aether.tasks.TaskSignals

TaskSignals.taskStarted.connect { task ->
    println("Task ${task.id} started: ${task.name}")
}

TaskSignals.taskCompleted.connect { task ->
    println("Task ${task.id} completed")
}

TaskSignals.taskFailed.connect { task ->
    println("Task ${task.id} failed: ${task.error}")
}

TaskSignals.taskRetried.connect { task ->
    println("Task ${task.id} retrying (attempt ${task.retryCount})")
}
```

## Monitoring

Get task queue statistics:

```kotlin
val stats = TaskDispatcher.getStats()
println("""
    Pending: ${stats.pending}
    Scheduled: ${stats.scheduled}
    Processing: ${stats.processing}
    Completed: ${stats.completed}
    Failed: ${stats.failed}
""")
```

## Task Management

```kotlin
// Get task by ID
val task = TaskDispatcher.getTask(taskId)

// Cancel a pending task
TaskDispatcher.cancel(taskId)
```

## Example: Email Queue

```kotlin
@Serializable
data class EmailTask(
    val to: String,
    val template: String,
    val data: Map<String, String>
)

// Register handler
TaskRegistry.register<EmailTask, Unit>("send_templated_email") { task ->
    val html = templateEngine.render(task.template, task.data)
    mailService.send(task.to, html)
}

// Usage in your application
suspend fun sendWelcomeEmail(user: User) {
    TaskDispatcher.enqueue(
        name = "send_templated_email",
        args = EmailTask(
            to = user.email,
            template = "welcome",
            data = mapOf("name" to user.name)
        )
    ) {
        priority = TaskPriority.HIGH
    }
}
```

## Best Practices

1. **Use DatabaseTaskStore in production** - Tasks survive restarts
2. **Set appropriate priorities** - Don't make everything CRITICAL
3. **Configure retries wisely** - Exponential backoff prevents overload
4. **Monitor failed tasks** - Set up alerts for stuck tasks
5. **Keep payloads small** - Store references, not large data blobs
6. **Idempotent handlers** - Tasks may run more than once on failure

````
