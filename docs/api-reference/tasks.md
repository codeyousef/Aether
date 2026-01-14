````markdown
# Background Tasks API

The `aether-tasks` module provides a persistent background job queue for async task processing.

## Overview

Background tasks allow you to defer expensive operations (sending emails, processing images, generating reports) to be executed asynchronously. Tasks are persisted to a store, so they survive server restarts.

## Installation

```kotlin
// build.gradle.kts
implementation("codes.yousef.aether:aether-tasks:0.5.0.2")
```

## Basic Usage

### Registering Task Handlers

```kotlin
import codes.yousef.aether.tasks.*

// Initialize the task system
val taskStore = InMemoryTaskStore()  // Or DatabaseTaskStore for production
val dispatcher = TaskDispatcher(taskStore)
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
val taskId = dispatcher.enqueue(
    taskName = "send_email",
    args = SendEmailArgs(
        to = "user@example.com",
        subject = "Welcome!",
        body = "Thanks for signing up."
    )
)

// Enqueue with options
val taskId = dispatcher.enqueue(
    taskName = "send_email",
    args = args,
    priority = TaskPriority.HIGH,
    delay = 5.minutes,  // Start after 5 minutes
    retryConfig = RetryConfig(
        maxAttempts = 3,
        baseDelayMillis = 1000,
        backoffMultiplier = 2.0
    )
)
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
    maxAttempts = 5,           // Total attempts including first try
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
    concurrency = 4,           // Process 4 tasks simultaneously
    pollInterval = 1.seconds   // Check for new tasks every second
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
    println("Task ${task.id} started: ${task.taskName}")
}

TaskSignals.taskCompleted.connect { task ->
    println("Task ${task.id} completed")
}

TaskSignals.taskFailed.connect { task ->
    println("Task ${task.id} failed: ${task.error}")
}

TaskSignals.taskRetrying.connect { task ->
    println("Task ${task.id} retrying (attempt ${task.attempts})")
}
```

## Monitoring

Get task queue statistics:

```kotlin
val stats = dispatcher.stats()
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
val task = dispatcher.getTask(taskId)

// Cancel a pending task
dispatcher.cancel(taskId)

// Retry a failed task
dispatcher.retry(taskId)

// List tasks by status
val failedTasks = dispatcher.listByStatus(TaskStatus.FAILED)
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
    dispatcher.enqueue(
        taskName = "send_templated_email",
        args = EmailTask(
            to = user.email,
            template = "welcome",
            data = mapOf("name" to user.name)
        ),
        priority = TaskPriority.HIGH
    )
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
