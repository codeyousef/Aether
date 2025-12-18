package codes.yousef.aether.core.auth

import java.util.Base64

/**
 * JVM implementation: Decode Base64 string.
 */
actual fun decodeBase64(encoded: String): String {
    val bytes = Base64.getDecoder().decode(encoded)
    return String(bytes, Charsets.UTF_8)
}
