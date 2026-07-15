package codes.yousef.aether.auth.scim

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ScimJsonLimits(
    val maximumBytes: Int = 256 * 1024,
    val maximumDepth: Int = 20,
    val maximumNodes: Int = 10_000,
    val maximumStringCharacters: Int = 16_384
) {
    init {
        require(maximumBytes in 1..2 * 1024 * 1024)
        require(maximumDepth in 1..100)
        require(maximumNodes in 1..100_000)
        require(maximumStringCharacters in 1..256 * 1024)
    }
}

/**
 * Parses a size-capped UTF-8 JSON document without relying on an unbounded object mapper.
 * Duplicate member names, excessive nesting/nodes/strings, invalid Unicode, and trailing data are
 * rejected before typed SCIM decoding.
 */
class BoundedScimJson(
    private val limits: ScimJsonLimits = ScimJsonLimits()
) {
    private val typedJson = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    fun parse(bytes: ByteArray): JsonElement {
        if (bytes.isEmpty() || bytes.size > limits.maximumBytes) throw ScimJsonException("invalid_size")
        val source = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Throwable) {
            throw ScimJsonException("invalid_utf8")
        }
        return Parser(source, limits).parse()
    }

    fun <T> decode(bytes: ByteArray, deserializer: DeserializationStrategy<T>): T {
        val element = parse(bytes)
        return try {
            typedJson.decodeFromJsonElement(deserializer, element)
        } catch (_: SerializationException) {
            throw ScimJsonException("invalid_document")
        } catch (_: IllegalArgumentException) {
            throw ScimJsonException("invalid_value")
        }
    }

    private class Parser(private val source: String, private val limits: ScimJsonLimits) {
        private var index = 0
        private var nodes = 0

        fun parse(): JsonElement {
            skipWhitespace()
            val value = value(1)
            skipWhitespace()
            if (index != source.length) fail("trailing_data")
            return value
        }

        private fun value(depth: Int): JsonElement {
            if (depth > limits.maximumDepth) fail("too_deep")
            if (++nodes > limits.maximumNodes) fail("too_many_nodes")
            if (index >= source.length) fail("unexpected_end")
            return when (source[index]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> JsonPrimitive(stringValue())
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'n' -> literal("null", JsonNull)
                '-', in '0'..'9' -> numberValue()
                else -> fail("invalid_token")
            }
        }

        private fun objectValue(depth: Int): JsonObject {
            index++
            skipWhitespace()
            val fields = linkedMapOf<String, JsonElement>()
            if (consume('}')) return JsonObject(fields)
            while (true) {
                if (index >= source.length || source[index] != '"') fail("invalid_object_key")
                val key = stringValue()
                if (fields.containsKey(key)) fail("duplicate_key")
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = value(depth + 1)
                skipWhitespace()
                if (consume('}')) return JsonObject(fields)
                expect(',')
                skipWhitespace()
            }
        }

        private fun arrayValue(depth: Int): JsonArray {
            index++
            skipWhitespace()
            val values = mutableListOf<JsonElement>()
            if (consume(']')) return JsonArray(values)
            while (true) {
                values += value(depth + 1)
                skipWhitespace()
                if (consume(']')) return JsonArray(values)
                expect(',')
                skipWhitespace()
            }
        }

        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                when {
                    char == '"' -> return result.toString()
                    char == '\\' -> result.append(escape())
                    char.code < 0x20 -> fail("control_character")
                    char.isHighSurrogate() -> {
                        if (index >= source.length || !source[index].isLowSurrogate()) fail("invalid_surrogate")
                        result.append(char)
                        result.append(source[index++])
                    }
                    char.isLowSurrogate() -> fail("invalid_surrogate")
                    else -> result.append(char)
                }
                if (result.length > limits.maximumStringCharacters) fail("string_too_long")
            }
            fail("unterminated_string")
        }

        private fun escape(): String {
            if (index >= source.length) fail("unterminated_escape")
            return when (val escaped = source[index++]) {
                '"', '\\', '/' -> escaped.toString()
                'b' -> "\b"
                'f' -> "\u000c"
                'n' -> "\n"
                'r' -> "\r"
                't' -> "\t"
                'u' -> unicodeEscape()
                else -> fail("invalid_escape")
            }
        }

        private fun unicodeEscape(): String {
            val first = readHexCodeUnit()
            if (first.isLowSurrogate()) fail("invalid_surrogate")
            if (!first.isHighSurrogate()) return first.toString()
            if (index + 2 > source.length || source[index] != '\\' || source[index + 1] != 'u') {
                fail("invalid_surrogate")
            }
            index += 2
            val second = readHexCodeUnit()
            if (!second.isLowSurrogate()) fail("invalid_surrogate")
            return "$first$second"
        }

        private fun readHexCodeUnit(): Char {
            if (index + 4 > source.length) fail("invalid_unicode_escape")
            var value = 0
            repeat(4) {
                val digit = source[index++].digitToIntOrNull(16) ?: fail("invalid_unicode_escape")
                value = (value shl 4) or digit
            }
            return value.toChar()
        }

        private fun numberValue(): JsonPrimitive {
            val start = index
            if (consume('-') && index >= source.length) fail("invalid_number")
            if (consume('0')) {
                if (index < source.length && source[index].isDigit()) fail("invalid_number")
            } else {
                if (index >= source.length || source[index] !in '1'..'9') fail("invalid_number")
                while (index < source.length && source[index].isDigit()) index++
            }
            if (consume('.')) {
                if (index >= source.length || !source[index].isDigit()) fail("invalid_number")
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                if (index >= source.length || !source[index].isDigit()) fail("invalid_number")
                while (index < source.length && source[index].isDigit()) index++
            }
            val encoded = source.substring(start, index)
            encoded.toLongOrNull()?.let { return JsonPrimitive(it) }
            val number = encoded.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fail("invalid_number")
            return JsonPrimitive(number)
        }

        private fun <T : JsonElement> literal(text: String, value: T): T {
            if (!source.startsWith(text, index)) fail("invalid_literal")
            index += text.length
            return value
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in setOf(' ', '\t', '\r', '\n')) index++
        }

        private fun consume(expected: Char): Boolean {
            if (index < source.length && source[index] == expected) {
                index++
                return true
            }
            return false
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) fail("expected_$expected")
        }

        private fun fail(code: String): Nothing = throw ScimJsonException(code)
    }
}

class ScimJsonException internal constructor(internal val safeCode: String) : IllegalArgumentException("Invalid SCIM JSON")
