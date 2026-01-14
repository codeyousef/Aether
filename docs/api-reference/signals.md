````markdown
# Signals API

The `aether-signals` module provides a Django-style event dispatch system for decoupled communication between components.

## Overview

Signals allow certain senders to notify a set of receivers that some action has taken place. They're especially useful for decoupling application logic—for example, sending notifications when a model is saved.

## Installation

```kotlin
// build.gradle.kts
implementation("codes.yousef.aether:aether-signals:0.5.0.2")
```

## Basic Usage

### Creating a Signal

```kotlin
import codes.yousef.aether.signals.Signal

// Create a typed signal
val userCreated = Signal<User>()

// Or with custom configuration
val orderPlaced = Signal<Order>(SignalConfig(
    parallel = true,       // Dispatch to receivers in parallel
    continueOnError = true // Continue if a receiver throws
))
```

### Connecting Receivers

```kotlin
// Connect a receiver (handler)
val disposable = userCreated.connect { user ->
    println("User created: ${user.username}")
    sendWelcomeEmail(user)
}

// Connect a one-time receiver (auto-disconnects after first call)
userCreated.connectOnce { user ->
    logFirstTimeSetup(user)
}
```

### Sending Signals

```kotlin
// Synchronous send (suspending)
suspend fun createUser(data: UserData): User {
    val user = Users.create(data)
    userCreated.send(user)  // All receivers called before returning
    return user
}

// Async send (fire and forget)
fun createUserAsync(data: UserData): User {
    val user = Users.create(data)
    userCreated.sendAsync(user)  // Returns immediately
    return user
}
```

### Disconnecting Receivers

```kotlin
// Use the Disposable returned by connect()
val disposable = signal.connect { ... }
disposable.dispose()  // Receiver is now disconnected

// Or disconnect all receivers
signal.disconnectAll()
```

## Signal Configuration

```kotlin
data class SignalConfig(
    // Dispatch to receivers in parallel (default: false)
    val parallel: Boolean = false,
    
    // Continue dispatching if a receiver throws (default: false)
    val continueOnError: Boolean = false,
    
    // Custom dispatcher for receiver execution
    val dispatcher: CoroutineDispatcher? = null
)
```

### Error Handling

When `continueOnError = true`, exceptions are collected and thrown as a `SignalDispatchException` after all receivers complete:

```kotlin
val signal = Signal<String>(SignalConfig(continueOnError = true))

signal.connect { throw Exception("First error") }
signal.connect { throw Exception("Second error") }
signal.connect { println("This still runs") }

try {
    signal.send("test")
} catch (e: SignalDispatchException) {
    e.errors.forEach { println(it.message) }
}
```

## Built-in Database Signals

The `aether-db` module provides lifecycle signals for models:

```kotlin
import codes.yousef.aether.db.signals.*

// Pre-save signal (before insert/update)
ModelSignals.preSave.connect { event ->
    println("About to save ${event.model.tableName}")
    // Modify event.instance before save
}

// Post-save signal (after insert/update)
ModelSignals.postSave.connect { event ->
    if (event.created) {
        println("New record created: ${event.instance}")
    } else {
        println("Record updated: ${event.instance}")
    }
}

// Pre-delete signal
ModelSignals.preDelete.connect { event ->
    println("About to delete from ${event.model.tableName}")
}

// Post-delete signal
ModelSignals.postDelete.connect { event ->
    println("Deleted: ${event.instance}")
}
```

## Pipeline Signals

Use `SignalMiddleware` to emit signals at request lifecycle points:

```kotlin
import codes.yousef.aether.signals.SignalMiddleware

val pipeline = Pipeline().apply {
    use(SignalMiddleware.middleware)
}

// Connect to request signals
SignalMiddleware.requestStarted.connect { exchange ->
    println("Request started: ${exchange.request.path}")
}

SignalMiddleware.requestCompleted.connect { exchange ->
    println("Request completed: ${exchange.response.statusCode}")
}
```

## Best Practices

1. **Keep receivers lightweight**: Heavy operations should be offloaded to background tasks
2. **Handle errors gracefully**: Use `continueOnError` for non-critical receivers
3. **Dispose when done**: Always dispose receivers when the listener's lifecycle ends
4. **Avoid circular signals**: Be careful not to create infinite loops

## Thread Safety

Signals are thread-safe:
- Receiver list modifications are atomic
- Parallel dispatch uses coroutines with proper synchronization
- Safe to connect/disconnect while dispatching

````
