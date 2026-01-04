package codes.yousef.aether.signals

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A disposable handle returned when connecting a signal receiver.
 * Call [dispose] to disconnect the receiver.
 */
fun interface Disposable {
    /**
     * Disconnect the receiver from the signal.
     */
    fun dispose()
}

/**
 * Configuration for signal dispatch behavior.
 */
data class SignalConfig(
    /**
     * Whether to dispatch to receivers in parallel.
     * When false, receivers are called sequentially in connection order.
     */
    val parallel: Boolean = false,

    /**
     * Whether to continue dispatching if a receiver throws an exception.
     * When true, exceptions are collected and rethrown after all receivers complete.
     */
    val continueOnError: Boolean = true,

    /**
     * Optional dispatcher to use for signal dispatch.
     * When null, uses the current coroutine context.
     */
    val dispatcher: CoroutineDispatcher? = null
)

/**
 * A type-safe signal that can dispatch events to connected receivers.
 *
 * Signals are similar to Django signals - they provide a way to decouple
 * components by allowing them to communicate through events.
 *
 * Example:
 * ```kotlin
 * val userCreated = Signal<User>()
 *
 * // Connect a receiver
 * userCreated.connect { user ->
 *     sendWelcomeEmail(user)
 * }
 *
 * // Send the signal
 * userCreated.send(newUser)
 * ```
 *
 * @param T The type of payload this signal carries.
 */
class Signal<T>(
    private val config: SignalConfig = SignalConfig()
) {
    private data class Receiver<T>(
        val id: Long,
        val handler: suspend (T) -> Unit,
        val weak: Boolean = false
    )

    private val nextId = atomic(0L)
    private val receivers = atomic(emptyList<Receiver<T>>())

    /**
     * Connect a receiver to this signal.
     *
     * @param weak If true, the receiver may be garbage collected when no other references exist.
     *             Note: Weak references are best-effort on all platforms.
     * @param handler The suspend function to call when the signal is sent.
     * @return A [Disposable] that can be used to disconnect the receiver.
     */
    fun connect(
        weak: Boolean = false,
        handler: suspend (T) -> Unit
    ): Disposable {
        val id = nextId.getAndIncrement()
        val receiver = Receiver(id, handler, weak)

        receivers.update { it + receiver }

        return Disposable {
            receivers.update { list -> list.filter { it.id != id } }
        }
    }

    /**
     * Connect a receiver that automatically disconnects after being called once.
     *
     * @param handler The suspend function to call when the signal is sent.
     * @return A [Disposable] that can be used to disconnect the receiver before it fires.
     */
    fun connectOnce(handler: suspend (T) -> Unit): Disposable {
        var disposable: Disposable? = null
        disposable = connect { payload ->
            disposable?.dispose()
            handler(payload)
        }
        return disposable
    }

    /**
     * Send the signal to all connected receivers.
     *
     * @param payload The data to send to receivers.
     * @throws SignalDispatchException If any receiver throws and [SignalConfig.continueOnError] is true.
     */
    suspend fun send(payload: T) {
        val currentReceivers = receivers.value
        if (currentReceivers.isEmpty()) return

        val errors = mutableListOf<Throwable>()
        val errorsMutex = Mutex()

        val context = config.dispatcher?.let { currentCoroutineContext() + it }
            ?: currentCoroutineContext()

        if (config.parallel) {
            supervisorScope {
                currentReceivers.map { receiver ->
                    async(context) {
                        try {
                            receiver.handler(payload)
                        } catch (e: Throwable) {
                            if (config.continueOnError) {
                                errorsMutex.withLock { errors.add(e) }
                            } else {
                                throw e
                            }
                        }
                    }
                }.awaitAll()
            }
        } else {
            for (receiver in currentReceivers) {
                try {
                    withContext(context) {
                        receiver.handler(payload)
                    }
                } catch (e: Throwable) {
                    if (config.continueOnError) {
                        errors.add(e)
                    } else {
                        throw e
                    }
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw SignalDispatchException(errors)
        }
    }

    /**
     * Send the signal without waiting for receivers to complete.
     * Errors are logged but not propagated.
     *
     * @param payload The data to send to receivers.
     * @param scope The coroutine scope to launch in.
     * @param onError Optional callback for handling errors.
     */
    fun sendAsync(
        payload: T,
        scope: CoroutineScope,
        onError: ((Throwable) -> Unit)? = null
    ) {
        scope.launch {
            try {
                send(payload)
            } catch (e: Throwable) {
                onError?.invoke(e)
            }
        }
    }

    /**
     * Disconnect all receivers from this signal.
     */
    fun disconnectAll() {
        receivers.update { emptyList() }
    }

    /**
     * Get the number of connected receivers.
     */
    val receiverCount: Int
        get() = receivers.value.size

    /**
     * Check if any receivers are connected.
     */
    val hasReceivers: Boolean
        get() = receivers.value.isNotEmpty()
}

/**
 * Exception thrown when signal dispatch encounters errors.
 */
class SignalDispatchException(
    val errors: List<Throwable>
) : Exception("Signal dispatch encountered ${errors.size} error(s)") {
    override val cause: Throwable?
        get() = errors.firstOrNull()

    override fun toString(): String {
        return buildString {
            appendLine("SignalDispatchException: ${errors.size} error(s)")
            errors.forEachIndexed { index, error ->
                appendLine("  [$index] ${error::class.simpleName}: ${error.message}")
            }
        }
    }
}

/**
 * Create a signal with custom configuration.
 */
fun <T> signal(
    parallel: Boolean = false,
    continueOnError: Boolean = true,
    dispatcher: CoroutineDispatcher? = null
): Signal<T> = Signal(SignalConfig(parallel, continueOnError, dispatcher))
