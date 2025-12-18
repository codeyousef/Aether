package codes.yousef.aether.core.session

import kotlin.random.Random

/**
 * WasmWASI implementation: Get current time in milliseconds.
 * Note: WASI doesn't have a standard time API, this is a fallback.
 */
actual fun currentTimeMillis(): Long {
    // WASI has limited time support, use a counter or external time source
    return wasiCurrentTimeMillis()
}

/**
 * External function to get current time from WASI runtime.
 */
private fun wasiCurrentTimeMillis(): Long {
    // This would typically use WASI's clock_time_get
    // For now, return a monotonic counter
    return epochMillisCounter++
}

private var epochMillisCounter: Long = 1700000000000L // Approximate epoch time

/**
 * WasmWASI implementation: Generate a session ID.
 * Note: WASI has limited random support.
 */
actual fun generateSecureSessionId(length: Int): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    return (1..length)
        .map { chars[Random.nextInt(chars.length)] }
        .joinToString("")
}
