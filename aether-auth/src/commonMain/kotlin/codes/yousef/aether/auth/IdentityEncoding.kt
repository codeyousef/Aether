package codes.yousef.aether.auth

/** Strict RFC 4648 base64url without padding, used by every identity wire byte field. */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val decodeTable = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, character -> table[character.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val result = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xff
            result.append(ALPHABET[first ushr 2])
            if (index == bytes.size) {
                result.append(ALPHABET[(first and 0x03) shl 4])
                break
            }

            val second = bytes[index++].toInt() and 0xff
            result.append(ALPHABET[((first and 0x03) shl 4) or (second ushr 4)])
            if (index == bytes.size) {
                result.append(ALPHABET[(second and 0x0f) shl 2])
                break
            }

            val third = bytes[index++].toInt() and 0xff
            result.append(ALPHABET[((second and 0x0f) shl 2) or (third ushr 6)])
            result.append(ALPHABET[third and 0x3f])
        }
        return result.toString()
    }

    fun decode(value: String, maximumBytes: Int = 1_048_576): ByteArray {
        require(maximumBytes >= 0) { "maximumBytes must not be negative" }
        require(value.length % 4 != 1) { "Invalid base64url length" }
        require(value.none { it == '=' || it.code >= decodeTable.size || decodeTable[it.code] < 0 }) {
            "Invalid base64url character"
        }

        val outputSize = value.length * 6 / 8
        require(outputSize <= maximumBytes) { "Decoded base64url value exceeds the configured limit" }
        val result = ByteArray(outputSize)
        var accumulator = 0
        var availableBits = 0
        var outputIndex = 0
        value.forEach { character ->
            accumulator = (accumulator shl 6) or decodeTable[character.code]
            availableBits += 6
            if (availableBits >= 8) {
                availableBits -= 8
                result[outputIndex++] = (accumulator ushr availableBits).toByte()
                accumulator = accumulator and ((1 shl availableBits) - 1)
            }
        }
        require(accumulator == 0) { "Non-canonical base64url trailing bits" }
        require(outputIndex == result.size && encode(result) == value) { "Non-canonical base64url encoding" }
        return result
    }
}
