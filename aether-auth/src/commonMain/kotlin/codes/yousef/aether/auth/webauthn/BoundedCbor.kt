package codes.yousef.aether.auth.webauthn

/** Minimal definite-length CBOR tree used by WebAuthn/COSE. */
sealed interface CborValue {
    data class Integer(val value: Long) : CborValue

    class Bytes(value: ByteArray) : CborValue {
        private val content = value.copyOf()
        fun copyBytes(): ByteArray = content.copyOf()
        override fun equals(other: Any?): Boolean = other is Bytes && content.contentEquals(other.content)
        override fun hashCode(): Int = content.contentHashCode()
        override fun toString(): String = "Bytes(${content.size})"
    }

    data class Text(val value: String) : CborValue
    data class ArrayValue(val values: List<CborValue>) : CborValue
    data class MapValue(val entries: Map<CborValue, CborValue>) : CborValue
    data class BooleanValue(val value: Boolean) : CborValue
    data object NullValue : CborValue
}

data class DecodedCbor(val value: CborValue, val nextOffset: Int)

class CborDecodingException : IllegalArgumentException("Malformed or unsupported CBOR")

/**
 * Allocation-bounded CBOR decoder. WebAuthn never needs indefinite lengths, floats, tags, or
 * simple values beyond booleans/null, so those encodings are rejected.
 */
class BoundedCborDecoder(
    private val maximumInputBytes: Int = 65_536,
    private val maximumDepth: Int = 8,
    private val maximumContainerItems: Int = 256,
    private val maximumTextBytes: Int = 8_192,
    private val maximumByteStringBytes: Int = 65_536
) {
    init {
        require(maximumInputBytes in 1..16 * 1_024 * 1_024)
        require(maximumDepth in 1..32)
        require(maximumContainerItems in 1..16_384)
        require(maximumTextBytes in 1..maximumInputBytes)
        require(maximumByteStringBytes in 1..maximumInputBytes)
    }

    fun decode(input: ByteArray, offset: Int = 0): DecodedCbor {
        if (input.size > maximumInputBytes || offset !in 0 until input.size) fail()
        val cursor = Cursor(input, offset)
        val value = decodeValue(cursor, depth = 0)
        return DecodedCbor(value, cursor.offset)
    }

    fun decodeExactly(input: ByteArray): CborValue {
        val decoded = decode(input)
        if (decoded.nextOffset != input.size) fail()
        return decoded.value
    }

    private fun decodeValue(cursor: Cursor, depth: Int): CborValue {
        if (depth > maximumDepth) fail()
        val initial = cursor.readByte()
        val major = initial ushr 5
        val additional = initial and 0x1f
        return when (major) {
            0 -> CborValue.Integer(readArgument(cursor, additional))
            1 -> {
                val argument = readArgument(cursor, additional)
                if (argument == Long.MAX_VALUE) fail()
                CborValue.Integer(-1L - argument)
            }
            2 -> {
                val length = readLength(cursor, additional, maximumByteStringBytes)
                CborValue.Bytes(cursor.readBytes(length))
            }
            3 -> {
                val length = readLength(cursor, additional, maximumTextBytes)
                val bytes = cursor.readBytes(length)
                val value = try {
                    bytes.decodeToString(throwOnInvalidSequence = true)
                } catch (_: IllegalArgumentException) {
                    fail()
                }
                CborValue.Text(value)
            }
            4 -> {
                val size = readLength(cursor, additional, maximumContainerItems)
                CborValue.ArrayValue(List(size) { decodeValue(cursor, depth + 1) })
            }
            5 -> {
                val size = readLength(cursor, additional, maximumContainerItems)
                val entries = LinkedHashMap<CborValue, CborValue>(size)
                repeat(size) {
                    val key = decodeValue(cursor, depth + 1)
                    if (key is CborValue.ArrayValue || key is CborValue.MapValue || key in entries) fail()
                    entries[key] = decodeValue(cursor, depth + 1)
                }
                CborValue.MapValue(entries)
            }
            7 -> when (additional) {
                20 -> CborValue.BooleanValue(false)
                21 -> CborValue.BooleanValue(true)
                22 -> CborValue.NullValue
                else -> fail()
            }
            else -> fail()
        }
    }

    private fun readLength(cursor: Cursor, additional: Int, maximum: Int): Int {
        val argument = readArgument(cursor, additional)
        if (argument > maximum.toLong()) fail()
        return argument.toInt()
    }

    private fun readArgument(cursor: Cursor, additional: Int): Long = when (additional) {
        in 0..23 -> additional.toLong()
        24 -> cursor.readByte().also { if (it < 24) fail() }.toLong()
        25 -> cursor.readUnsigned(2).also { if (it <= 0xff) fail() }
        26 -> cursor.readUnsigned(4).also { if (it <= 0xffff) fail() }
        27 -> cursor.readUnsigned(8).also { if (it <= 0xffff_ffffL) fail() }
        else -> fail()
    }

    private class Cursor(private val input: ByteArray, var offset: Int) {
        fun readByte(): Int {
            if (offset >= input.size) fail()
            return input[offset++].toInt() and 0xff
        }

        fun readUnsigned(count: Int): Long {
            if (count !in setOf(2, 4, 8) || offset > input.size - count) fail()
            var result = 0L
            repeat(count) {
                val byte = readByte()
                if (count == 8 && it == 0 && byte and 0x80 != 0) fail()
                result = (result shl 8) or byte.toLong()
            }
            return result
        }

        fun readBytes(count: Int): ByteArray {
            if (count < 0 || offset > input.size - count) fail()
            return input.copyOfRange(offset, offset + count).also { offset += count }
        }
    }
}

private fun fail(): Nothing = throw CborDecodingException()
