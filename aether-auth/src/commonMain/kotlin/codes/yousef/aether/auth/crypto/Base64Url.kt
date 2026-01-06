package codes.yousef.aether.auth.crypto

/**
 * Base64URL encoding/decoding utilities for JWT.
 * JWT uses base64url encoding (RFC 4648) without padding.
 */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val DECODE_TABLE = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { index, char -> this[char.code] = index }
    }

    /**
     * Encode bytes to base64url string (no padding).
     */
    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""

        val result = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0

        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            result.append(ALPHABET[b0 shr 2])

            if (i + 1 < data.size) {
                val b1 = data[i + 1].toInt() and 0xFF
                result.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])

                if (i + 2 < data.size) {
                    val b2 = data[i + 2].toInt() and 0xFF
                    result.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                    result.append(ALPHABET[b2 and 0x3F])
                } else {
                    result.append(ALPHABET[(b1 and 0x0F) shl 2])
                }
            } else {
                result.append(ALPHABET[(b0 and 0x03) shl 4])
            }

            i += 3
        }

        return result.toString()
    }

    /**
     * Decode base64url string to bytes.
     */
    fun decode(data: String): ByteArray {
        if (data.isEmpty()) return ByteArray(0)

        // Calculate output size
        val padding = when (data.length % 4) {
            2 -> 2
            3 -> 1
            else -> 0
        }
        val outputLength = (data.length * 3) / 4 - padding + (if (padding > 0) padding - 1 else 0)

        // More accurate calculation
        val len = data.length
        val outLen = (len * 3 + 3) / 4
        val result = ByteArray(outLen)
        var outIndex = 0

        var i = 0
        while (i < len) {
            val c0 = if (i < len) decodeChar(data[i]) else 0
            val c1 = if (i + 1 < len) decodeChar(data[i + 1]) else 0
            val c2 = if (i + 2 < len) decodeChar(data[i + 2]) else 0
            val c3 = if (i + 3 < len) decodeChar(data[i + 3]) else 0

            if (outIndex < result.size) result[outIndex++] = ((c0 shl 2) or (c1 shr 4)).toByte()
            if (outIndex < result.size && i + 2 < len) result[outIndex++] = ((c1 shl 4) or (c2 shr 2)).toByte()
            if (outIndex < result.size && i + 3 < len) result[outIndex++] = ((c2 shl 6) or c3).toByte()

            i += 4
        }

        return result.copyOf(outIndex)
    }

    private fun decodeChar(c: Char): Int {
        val code = c.code
        return if (code < 128) DECODE_TABLE[code] else -1
    }

    /**
     * Encode a string to base64url.
     */
    fun encodeToString(str: String): String = encode(str.encodeToByteArray())

    /**
     * Decode base64url to string.
     */
    fun decodeToString(data: String): String = decode(data).decodeToString()
}

