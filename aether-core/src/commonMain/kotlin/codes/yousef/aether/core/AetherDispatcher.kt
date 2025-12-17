package codes.yousef.aether.core

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Platform-specific coroutine dispatcher for handling requests.
 * JVM uses Virtual Threads, Wasm uses single-threaded event loop.
 */
expect object AetherDispatcher {
    val dispatcher: CoroutineDispatcher
}
