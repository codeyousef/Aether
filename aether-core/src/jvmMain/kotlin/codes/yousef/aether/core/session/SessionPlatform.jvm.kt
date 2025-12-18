package codes.yousef.aether.core.session

import java.security.SecureRandom
import java.util.Base64

/**
 * JVM implementation: Get current time in milliseconds.
 */
actual fun currentTimeMillis(): Long = System.currentTimeMillis()

/**
 * JVM implementation: Generate a cryptographically secure session ID.
 */
actual fun generateSecureSessionId(length: Int): String {
    val random = SecureRandom()
    val bytes = ByteArray(length)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
