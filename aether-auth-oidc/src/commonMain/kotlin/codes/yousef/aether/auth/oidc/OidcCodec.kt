package codes.yousef.aether.auth.oidc

import codes.yousef.aether.auth.Base64Url
import codes.yousef.aether.auth.IdentityCrypto

internal fun Char.isProtocolControl(): Boolean = code < 0x20 || code in 0x7f..0x9f

internal fun formEncode(parameters: List<Pair<String, String>>): ByteArray =
    parameters.joinToString("&") { (name, value) -> "${percentEncode(name)}=${percentEncode(value)}" }.encodeToByteArray()

internal fun appendQuery(url: String, parameters: List<Pair<String, String>>): String {
    val separator = if ('?' in url) '&' else '?'
    return buildString(url.length + parameters.sumOf { it.first.length + it.second.length + 2 }) {
        append(url)
        append(separator)
        parameters.forEachIndexed { index, (name, value) ->
            if (index > 0) append('&')
            append(percentEncode(name)).append('=').append(percentEncode(value))
        }
    }
}

internal fun percentEncode(value: String): String = percentEncode(value.encodeToByteArray())

internal fun percentEncode(bytes: ByteArray): String = buildString(bytes.size * 3) {
    val hex = "0123456789ABCDEF"
    bytes.forEach { byte ->
        val value = byte.toInt() and 0xff
        if ((value in 'A'.code..'Z'.code) || (value in 'a'.code..'z'.code) ||
            (value in '0'.code..'9'.code) || value == '-'.code || value == '.'.code ||
            value == '_'.code || value == '~'.code
        ) {
            append(value.toChar())
        } else {
            append('%').append(hex[value ushr 4]).append(hex[value and 0x0f])
        }
    }
}

internal fun queryParameterNames(url: String): Set<String> {
    if ('?' !in url) return emptySet()
    val query = url.substringAfter('?')
    if (query.isEmpty()) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    val result = LinkedHashSet<String>()
    query.split('&').forEach { parameter ->
        if (parameter.isEmpty()) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        val encodedName = parameter.substringBefore('=')
        val name = percentDecode(encodedName)
        if (name.isEmpty() || !result.add(name)) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    }
    return result
}

private fun percentDecode(value: String): String {
    val output = ByteArray(value.length)
    var inputIndex = 0
    var outputIndex = 0
    while (inputIndex < value.length) {
        when (val character = value[inputIndex++]) {
            '%' -> {
                if (inputIndex + 2 > value.length) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
                val high = value[inputIndex++].digitToIntOrNull(16)
                    ?: oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
                val low = value[inputIndex++].digitToIntOrNull(16)
                    ?: oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
                output[outputIndex++] = ((high shl 4) or low).toByte()
            }
            '+' -> output[outputIndex++] = ' '.code.toByte()
            else -> {
                if (character.code > 0x7f) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
                output[outputIndex++] = character.code.toByte()
            }
        }
    }
    return try {
        output.copyOf(outputIndex).decodeToString(throwOnInvalidSequence = true)
    } catch (_: Exception) {
        oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    } finally {
        output.fill(0)
    }
}

internal fun standardBase64(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    if (bytes.isEmpty()) return ""
    val output = StringBuilder((bytes.size + 2) / 3 * 4)
    var index = 0
    while (index < bytes.size) {
        val first = bytes[index++].toInt() and 0xff
        val second = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
        val third = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
        output.append(alphabet[first ushr 2])
        output.append(alphabet[((first and 3) shl 4) or if (second >= 0) second ushr 4 else 0])
        if (second < 0) {
            output.append("==")
        } else {
            output.append(alphabet[((second and 15) shl 2) or if (third >= 0) third ushr 6 else 0])
            output.append(if (third < 0) '=' else alphabet[third and 63])
        }
    }
    return output.toString()
}

internal suspend fun providerStorageKey(config: OidcProviderConfig, crypto: IdentityCrypto): String {
    val canonical = lengthPrefixed(
        config.tenantId.value.encodeToByteArray(),
        config.providerId.encodeToByteArray(),
        config.issuer.encodeToByteArray()
    )
    return try {
        val digest = crypto.sha256(canonical)
        try {
            if (digest.size != 32) oidcAbort(OidcErrorCode.STORE_UNAVAILABLE)
            "oidc.${Base64Url.encode(digest)}"
        } finally {
            digest.fill(0)
        }
    } finally {
        canonical.fill(0)
    }
}

internal fun lengthPrefixed(vararg values: ByteArray): ByteArray {
    val size = values.sumOf { 4 + it.size }
    val output = ByteArray(size)
    var offset = 0
    values.forEach { value ->
        val length = value.size
        output[offset++] = (length ushr 24).toByte()
        output[offset++] = (length ushr 16).toByte()
        output[offset++] = (length ushr 8).toByte()
        output[offset++] = length.toByte()
        value.copyInto(output, offset)
        offset += length
    }
    return output
}

internal fun rsaSubjectPublicKeyInfo(modulus: ByteArray, exponent: ByteArray): ByteArray {
    if (modulus.size !in 256..1_024 || modulus[0] == 0.toByte()) {
        oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    }
    val significantBits = modulus.size * 8 - modulus[0].countLeadingZeroBits()
    if (significantBits < 2_048) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    if (exponent.isEmpty() || exponent.size > 4 || exponent[0] == 0.toByte()) {
        oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    }
    var exponentValue = 0L
    exponent.forEach { exponentValue = (exponentValue shl 8) or (it.toInt() and 0xff).toLong() }
    if (exponentValue < 3 || exponentValue > 0xffff_ffffL || exponentValue and 1L == 0L) {
        oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    }

    val rsaKey = derSequence(derInteger(modulus), derInteger(exponent))
    val algorithm = derSequence(
        byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01),
        byteArrayOf(0x05, 0x00)
    )
    return derSequence(algorithm, derValue(0x03, byteArrayOf(0) + rsaKey))
}

private fun derInteger(unsigned: ByteArray): ByteArray {
    var offset = 0
    while (offset < unsigned.lastIndex && unsigned[offset] == 0.toByte()) offset++
    val value = unsigned.copyOfRange(offset, unsigned.size)
    val positive = if (value[0].toInt() and 0x80 != 0) byteArrayOf(0) + value else value
    return derValue(0x02, positive)
}

private fun derSequence(vararg children: ByteArray): ByteArray = derValue(0x30, children.fold(ByteArray(0), ByteArray::plus))

private fun derValue(tag: Int, value: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + derLength(value.size) + value

private fun derLength(length: Int): ByteArray = when {
    length < 0x80 -> byteArrayOf(length.toByte())
    length <= 0xff -> byteArrayOf(0x81.toByte(), length.toByte())
    length <= 0xffff -> byteArrayOf(0x82.toByte(), (length ushr 8).toByte(), length.toByte())
    else -> byteArrayOf(
        0x84.toByte(),
        (length ushr 24).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte()
    )
}
