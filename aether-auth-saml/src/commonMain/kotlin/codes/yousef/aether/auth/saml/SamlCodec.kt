package codes.yousef.aether.auth.saml

import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.IdentityCrypto

internal object SamlBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val decodeTable = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, character -> table[character.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val output = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xff
            val second = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
            val third = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
            output.append(ALPHABET[first ushr 2])
            output.append(ALPHABET[((first and 3) shl 4) or if (second >= 0) second ushr 4 else 0])
            if (second < 0) {
                output.append("==")
            } else {
                output.append(ALPHABET[((second and 15) shl 2) or if (third >= 0) third ushr 6 else 0])
                output.append(if (third < 0) '=' else ALPHABET[third and 63])
            }
        }
        return output.toString()
    }

    fun decode(value: String, maximumBytes: Int): ByteArray {
        require(maximumBytes >= 0) { "maximumBytes must not be negative" }
        require(value.isNotEmpty() && value.length % 4 == 0) { "Invalid base64 length" }
        require(value.none(Char::isWhitespace)) { "Base64 must not contain whitespace" }
        val padding = when {
            value.endsWith("==") -> 2
            value.endsWith('=') -> 1
            else -> 0
        }
        require('=' !in value.dropLast(padding)) { "Invalid base64 padding" }
        val outputSize = value.length / 4 * 3 - padding
        require(outputSize in 1..maximumBytes) { "Decoded base64 value exceeds the configured limit" }
        val output = ByteArray(outputSize)
        var outputIndex = 0
        var inputIndex = 0
        while (inputIndex < value.length) {
            val a = decode(value[inputIndex++])
            val b = decode(value[inputIndex++])
            val thirdCharacter = value[inputIndex++]
            val fourthCharacter = value[inputIndex++]
            val c = if (thirdCharacter == '=') 0 else decode(thirdCharacter)
            val d = if (fourthCharacter == '=') 0 else decode(fourthCharacter)
            if (outputIndex < output.size) output[outputIndex++] = ((a shl 2) or (b ushr 4)).toByte()
            if (outputIndex < output.size) output[outputIndex++] = ((b shl 4) or (c ushr 2)).toByte()
            if (outputIndex < output.size) output[outputIndex++] = ((c shl 6) or d).toByte()
        }
        require(outputIndex == output.size && encode(output) == value) { "Non-canonical base64 encoding" }
        return output
    }

    private fun decode(character: Char): Int {
        require(character.code < decodeTable.size && decodeTable[character.code] >= 0) {
            "Invalid base64 character"
        }
        return decodeTable[character.code]
    }
}

/** RFC 1951 raw DEFLATE using bounded stored blocks. Compression is optional for correctness. */
internal fun rawDeflateStored(input: ByteArray): ByteArray {
    val blockCount = if (input.isEmpty()) 1 else (input.size + 65_534) / 65_535
    val output = ByteArray(input.size + blockCount * 5)
    var sourceOffset = 0
    var outputOffset = 0
    repeat(blockCount) { blockIndex ->
        val length = minOf(65_535, input.size - sourceOffset).coerceAtLeast(0)
        val finalBlock = blockIndex == blockCount - 1
        output[outputOffset++] = if (finalBlock) 0x01 else 0x00
        output[outputOffset++] = length.toByte()
        output[outputOffset++] = (length ushr 8).toByte()
        val inverse = length xor 0xffff
        output[outputOffset++] = inverse.toByte()
        output[outputOffset++] = (inverse ushr 8).toByte()
        input.copyInto(output, outputOffset, sourceOffset, sourceOffset + length)
        sourceOffset += length
        outputOffset += length
    }
    return output
}

internal fun percentEncode(value: String): String = percentEncode(value.encodeToByteArray())

internal fun percentEncode(bytes: ByteArray): String = buildString(bytes.size * 3) {
    val hex = "0123456789ABCDEF"
    bytes.forEach { byte ->
        val value = byte.toInt() and 0xff
        if (value in 'A'.code..'Z'.code || value in 'a'.code..'z'.code ||
            value in '0'.code..'9'.code || value == '-'.code || value == '.'.code ||
            value == '_'.code || value == '~'.code
        ) {
            append(value.toChar())
        } else {
            append('%').append(hex[value ushr 4]).append(hex[value and 0x0f])
        }
    }
}

internal fun appendQuery(url: String, encodedQuery: String): String =
    "$url${if ('?' in url) '&' else '?'}$encodedQuery"

internal fun escapeXmlText(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '\r' -> append("&#xD;")
            else -> append(character)
        }
    }
}

internal fun escapeXmlAttribute(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '"' -> append("&quot;")
            '\t' -> append("&#x9;")
            '\n' -> append("&#xA;")
            '\r' -> append("&#xD;")
            else -> append(character)
        }
    }
}

internal suspend fun samlProviderStorageKey(config: SamlProviderConfig, crypto: IdentityCrypto): String {
    val canonical = lengthPrefixed(
        config.tenantId.value.encodeToByteArray(),
        config.providerId.encodeToByteArray(),
        config.idpEntityId.encodeToByteArray()
    )
    return try {
        val digest = crypto.sha256(canonical)
        try {
            if (digest.size != 32) samlAbort(SamlErrorCode.STORE_UNAVAILABLE)
            "saml.${Base64Url.encode(digest)}"
        } finally {
            digest.fill(0)
        }
    } finally {
        canonical.fill(0)
    }
}

internal fun lengthPrefixed(vararg values: ByteArray): ByteArray {
    val output = ByteArray(values.sumOf { 4 + it.size })
    var offset = 0
    values.forEach { value ->
        output[offset++] = (value.size ushr 24).toByte()
        output[offset++] = (value.size ushr 16).toByte()
        output[offset++] = (value.size ushr 8).toByte()
        output[offset++] = value.size.toByte()
        value.copyInto(output, offset)
        offset += value.size
    }
    return output
}
