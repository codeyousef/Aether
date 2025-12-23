package codes.yousef.aether.core.jvm

import codes.yousef.aether.core.Exchange
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parse form parameters from the request body.
 * Handles application/x-www-form-urlencoded.
 */
suspend fun Exchange.receiveParameters(): Map<String, String> {
    val text = request.bodyText()
    if (text.isBlank()) return emptyMap()
    
    return text.split("&").associate {
        val parts = it.split("=", limit = 2)
        val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
        val value = if (parts.size > 1) URLDecoder.decode(parts[1], StandardCharsets.UTF_8) else ""
        key to value
    }
}
