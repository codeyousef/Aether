package codes.yousef.aether.core.auth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * WasmJS implementation: Decode Base64 string.
 */
@OptIn(ExperimentalEncodingApi::class)
actual fun decodeBase64(encoded: String): String {
    val bytes = Base64.decode(encoded)
    return bytes.decodeToString()
}
