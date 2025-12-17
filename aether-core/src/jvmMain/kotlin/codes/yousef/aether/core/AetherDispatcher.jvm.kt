package codes.yousef.aether.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * JVM implementation using Virtual Threads (Project Loom).
 * Requires Java 21+.
 */
actual object AetherDispatcher {
    actual val dispatcher: CoroutineDispatcher by lazy {
        Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    }
}
