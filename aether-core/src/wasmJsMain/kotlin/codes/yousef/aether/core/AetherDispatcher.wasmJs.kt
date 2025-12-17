package codes.yousef.aether.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Wasm JS implementation using default dispatcher (single-threaded event loop).
 */
actual object AetherDispatcher {
    actual val dispatcher: CoroutineDispatcher = Dispatchers.Default
}
