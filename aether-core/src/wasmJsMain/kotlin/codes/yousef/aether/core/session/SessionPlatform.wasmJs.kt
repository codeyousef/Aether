package codes.yousef.aether.core.session

import kotlin.random.Random

/**
 * External JS Date.now() function
 */
private external object Date {
    fun now(): Double
}

/**
 * WasmJS implementation: Get current time in milliseconds.
 */
actual fun currentTimeMillis(): Long = Date.now().toLong()

/**
 * WasmJS implementation: Generate a session ID.
 * Note: Uses Kotlin Random which may not be cryptographically secure on all platforms.
 * For production use, consider using the Web Crypto API.
 */
actual fun generateSecureSessionId(length: Int): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    return (1..length)
        .map { chars[Random.nextInt(chars.length)] }
        .joinToString("")
}
