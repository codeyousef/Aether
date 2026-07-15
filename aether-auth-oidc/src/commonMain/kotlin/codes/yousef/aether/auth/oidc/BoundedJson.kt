package codes.yousef.aether.auth.oidc

/** Small strict JSON reader used for untrusted discovery, JWKS, token, and JWT documents. */
internal object BoundedJson {
    fun parseObject(
        bytes: ByteArray,
        maximumBytes: Int,
        maximumDepth: Int = 12,
        maximumEntries: Int = 1_024,
        maximumStringCharacters: Int = 16_384
    ): JsonObjectValue {
        if (bytes.size > maximumBytes) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
        return Parser(text, maximumDepth, maximumEntries, maximumStringCharacters).parseRootObject()
    }

    fun parseJwtObject(bytes: ByteArray, maximumBytes: Int): JsonObjectValue {
        if (bytes.size > maximumBytes) oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
        }
        return try {
            Parser(text, maximumDepth = 10, maximumEntries = 512, maximumStringCharacters = 8_192)
                .parseRootObject()
        } catch (failure: OidcAbort) {
            if (failure.code == OidcErrorCode.PROVIDER_METADATA_INVALID) {
                oidcAbort(OidcErrorCode.ID_TOKEN_INVALID)
            }
            throw failure
        }
    }
}

internal sealed interface JsonValue
internal data class JsonObjectValue(val members: Map<String, JsonValue>) : JsonValue
internal data class JsonArrayValue(val elements: List<JsonValue>) : JsonValue
internal data class JsonStringValue(val value: String) : JsonValue
internal data class JsonNumberValue(val source: String) : JsonValue
internal data class JsonBooleanValue(val value: Boolean) : JsonValue
internal data object JsonNullValue : JsonValue

internal fun JsonObjectValue.requiredString(name: String, maximumLength: Int = 8_192): String {
    val value = (members[name] as? JsonStringValue)?.value
        ?: oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    if (value.isEmpty() || value.length > maximumLength) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    return value
}

internal fun JsonObjectValue.optionalString(name: String, maximumLength: Int = 8_192): String? {
    val raw = members[name] ?: return null
    if (raw === JsonNullValue) return null
    val value = (raw as? JsonStringValue)?.value ?: oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    if (value.length > maximumLength) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    return value
}

internal fun JsonObjectValue.optionalStringSet(
    name: String,
    maximumElements: Int = 128,
    maximumElementLength: Int = 512
): Set<String>? {
    val raw = members[name] ?: return null
    if (raw === JsonNullValue) return null
    val array = raw as? JsonArrayValue ?: oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    if (array.elements.size > maximumElements) oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
    val result = LinkedHashSet<String>()
    array.elements.forEach { element ->
        val value = (element as? JsonStringValue)?.value ?: oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        if (value.isEmpty() || value.length > maximumElementLength || !result.add(value)) {
            oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
        }
    }
    return result
}

internal fun JsonObjectValue.requiredLong(name: String, errorCode: OidcErrorCode): Long {
    val source = (members[name] as? JsonNumberValue)?.source ?: oidcAbort(errorCode)
    if ('.' in source || 'e' in source || 'E' in source) oidcAbort(errorCode)
    return source.toLongOrNull() ?: oidcAbort(errorCode)
}

private class Parser(
    private val source: String,
    private val maximumDepth: Int,
    private val maximumEntries: Int,
    private val maximumStringCharacters: Int
) {
    private var index = 0
    private var entries = 0

    fun parseRootObject(): JsonObjectValue {
        skipWhitespace()
        val result = parseValue(0) as? JsonObjectValue ?: fail()
        skipWhitespace()
        if (index != source.length) fail()
        return result
    }

    private fun parseValue(depth: Int): JsonValue {
        if (depth > maximumDepth || index >= source.length) fail()
        return when (source[index]) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> JsonStringValue(parseString())
            't' -> { consumeLiteral("true"); JsonBooleanValue(true) }
            'f' -> { consumeLiteral("false"); JsonBooleanValue(false) }
            'n' -> { consumeLiteral("null"); JsonNullValue }
            '-', in '0'..'9' -> JsonNumberValue(parseNumber())
            else -> fail()
        }
    }

    private fun parseObject(depth: Int): JsonObjectValue {
        index++
        skipWhitespace()
        val values = LinkedHashMap<String, JsonValue>()
        if (consumeIf('}')) return JsonObjectValue(values)
        while (true) {
            if (index >= source.length || source[index] != '"') fail()
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            countEntry()
            if (key in values) fail()
            values[key] = parseValue(depth)
            skipWhitespace()
            if (consumeIf('}')) return JsonObjectValue(values)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseArray(depth: Int): JsonArrayValue {
        index++
        skipWhitespace()
        val values = ArrayList<JsonValue>()
        if (consumeIf(']')) return JsonArrayValue(values)
        while (true) {
            countEntry()
            values += parseValue(depth)
            skipWhitespace()
            if (consumeIf(']')) return JsonArrayValue(values)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> appendEscape(result)
                character.code < 0x20 -> fail()
                character.isHighSurrogate() -> {
                    if (index >= source.length || !source[index].isLowSurrogate()) fail()
                    result.append(character).append(source[index++])
                }
                character.isLowSurrogate() -> fail()
                else -> result.append(character)
            }
            if (result.length > maximumStringCharacters) fail()
        }
        fail()
    }

    private fun appendEscape(result: StringBuilder) {
        if (index >= source.length) fail()
        when (val escaped = source[index++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                val first = readHexCodeUnit()
                when {
                    first in 0xD800..0xDBFF -> {
                        if (index + 2 > source.length || source[index] != '\\' || source[index + 1] != 'u') fail()
                        index += 2
                        val second = readHexCodeUnit()
                        if (second !in 0xDC00..0xDFFF) fail()
                        result.append(first.toChar()).append(second.toChar())
                    }
                    first in 0xDC00..0xDFFF -> fail()
                    else -> result.append(first.toChar())
                }
            }
            else -> fail()
        }
    }

    private fun readHexCodeUnit(): Int {
        if (index + 4 > source.length) fail()
        var value = 0
        repeat(4) {
            val digit = source[index++].digitToIntOrNull(16) ?: fail()
            value = (value shl 4) or digit
        }
        return value
    }

    private fun parseNumber(): String {
        val start = index
        consumeIf('-')
        if (consumeIf('0')) {
            if (index < source.length && source[index].isDigit()) fail()
        } else {
            if (index >= source.length || source[index] !in '1'..'9') fail()
            while (index < source.length && source[index].isDigit()) index++
        }
        if (consumeIf('.')) {
            if (index >= source.length || !source[index].isDigit()) fail()
            while (index < source.length && source[index].isDigit()) index++
        }
        if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            index++
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
            if (index >= source.length || !source[index].isDigit()) fail()
            while (index < source.length && source[index].isDigit()) index++
        }
        if (index - start > 64) fail()
        return source.substring(start, index)
    }

    private fun consumeLiteral(literal: String) {
        if (!source.startsWith(literal, index)) fail()
        index += literal.length
    }

    private fun skipWhitespace() {
        while (index < source.length &&
            (source[index] == ' ' || source[index] == '\t' || source[index] == '\r' || source[index] == '\n')
        ) index++
    }

    private fun expect(character: Char) {
        if (!consumeIf(character)) fail()
    }

    private fun consumeIf(character: Char): Boolean {
        if (index < source.length && source[index] == character) {
            index++
            return true
        }
        return false
    }

    private fun countEntry() {
        entries++
        if (entries > maximumEntries) fail()
    }

    private fun fail(): Nothing = oidcAbort(OidcErrorCode.PROVIDER_METADATA_INVALID)
}

internal class OidcAbort(val code: OidcErrorCode) : RuntimeException()
internal fun oidcAbort(code: OidcErrorCode): Nothing = throw OidcAbort(code)
