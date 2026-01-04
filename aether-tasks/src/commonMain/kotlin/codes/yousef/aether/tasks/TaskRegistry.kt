package codes.yousef.aether.tasks

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * A registered task handler.
 */
data class RegisteredTask<A, R>(
    val name: String,
    val handler: suspend (A) -> R,
    val argSerializer: KSerializer<A>,
    val resultSerializer: KSerializer<R>,
    val defaultOptions: TaskOptions = TaskOptions()
)

/**
 * Global registry for task handlers.
 * Tasks must be registered before they can be executed by workers.
 *
 * Example:
 * ```kotlin
 * // Register a task
 * TaskRegistry.register("send_email") { args: SendEmailArgs ->
 *     emailService.send(args.to, args.subject, args.body)
 * }
 *
 * // Queue the task
 * TaskDispatcher.enqueue("send_email", SendEmailArgs("user@example.com", "Hello", "World"))
 * ```
 */
object TaskRegistry {
    private val mutex = Mutex()
    private val tasks = mutableMapOf<String, RegisteredTask<*, *>>()
    
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /**
     * Register a task handler.
     *
     * @param name Unique task name
     * @param argSerializer Serializer for task arguments
     * @param resultSerializer Serializer for task result
     * @param options Default options for this task
     * @param handler The task function
     */
    fun <A, R> register(
        name: String,
        argSerializer: KSerializer<A>,
        resultSerializer: KSerializer<R>,
        options: TaskOptions = TaskOptions(),
        handler: suspend (A) -> R
    ) {
        val task = RegisteredTask(name, handler, argSerializer, resultSerializer, options)
        tasks[name] = task
    }

    /**
     * Get a registered task by name.
     */
    @Suppress("UNCHECKED_CAST")
    fun <A, R> get(name: String): RegisteredTask<A, R>? {
        return tasks[name] as? RegisteredTask<A, R>
    }

    /**
     * Get all registered task names.
     */
    fun getTaskNames(): Set<String> = tasks.keys.toSet()

    /**
     * Check if a task is registered.
     */
    fun isRegistered(name: String): Boolean = name in tasks

    /**
     * Clear all registered tasks. Useful for testing.
     */
    suspend fun clear() {
        mutex.withLock {
            tasks.clear()
        }
    }

    /**
     * Execute a task by name with JSON arguments.
     * Used internally by TaskWorker.
     */
    @Suppress("UNCHECKED_CAST")
    internal suspend fun execute(name: String, args: JsonElement): JsonElement? {
        val task = tasks[name] ?: throw TaskNotFoundException("Task not found: $name")
        
        val typedTask = task as RegisteredTask<Any, Any?>
        val deserializedArgs = json.decodeFromJsonElement(typedTask.argSerializer as KSerializer<Any>, args)
        val result = typedTask.handler(deserializedArgs)
        
        return if (result != null) {
            json.encodeToJsonElement(typedTask.resultSerializer as KSerializer<Any>, result)
        } else {
            null
        }
    }
}

/**
 * Exception thrown when a task is not found in the registry.
 */
class TaskNotFoundException(message: String) : Exception(message)

/**
 * DSL for registering tasks with reified type parameters.
 */
inline fun <reified A, reified R> TaskRegistry.register(
    name: String,
    options: TaskOptions = TaskOptions(),
    noinline handler: suspend (A) -> R
) {
    register(name, serializer<A>(), serializer<R>(), options, handler)
}

/**
 * DSL for registering tasks that don't return a value.
 */
inline fun <reified A> TaskRegistry.registerUnit(
    name: String,
    options: TaskOptions = TaskOptions(),
    noinline handler: suspend (A) -> Unit
) {
    register<A, Unit>(name, options, handler)
}
