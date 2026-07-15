package codes.yousef.aether.auth.testkit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small common lock used by deterministic test doubles and in-memory stores. */
class CoroutineSafeLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: () -> T): T = mutex.withLock { block() }
}
