package codes.yousef.aether.grpc.streaming

/**
 * Length-Prefixed Message (LPM) codec for gRPC framing.
 *
 * The gRPC protocol uses a simple framing format:
 * - 1 byte: Compression flag (0 = uncompressed, 1 = compressed)
 * - 4 bytes: Message length (big-endian unsigned 32-bit integer)
 * - N bytes: Message data
 *
 * This codec handles encoding/decoding messages to/from this wire format.
 */
class LpmCodec {

    /**
     * Frame a message with the LPM header.
     *
     * @param message The raw message bytes
     * @param compressed Whether the message is compressed (default: false)
     * @return The framed message with header
     */
    fun frame(message: ByteArray, compressed: Boolean = false): ByteArray {
        val result = ByteArray(5 + message.size)

        // Compression flag
        result[0] = if (compressed) 1.toByte() else 0.toByte()

        // Message length (big-endian 32-bit)
        val length = message.size
        result[1] = ((length shr 24) and 0xFF).toByte()
        result[2] = ((length shr 16) and 0xFF).toByte()
        result[3] = ((length shr 8) and 0xFF).toByte()
        result[4] = (length and 0xFF).toByte()

        // Message data
        message.copyInto(result, 5)

        return result
    }

    /**
     * Extract a message from an LPM frame.
     *
     * @param frame The framed data
     * @return The raw message bytes
     * @throws IllegalArgumentException if the frame is malformed
     */
    fun unframe(frame: ByteArray): ByteArray {
        require(frame.size >= 5) { "Frame too short: ${frame.size} bytes" }

        val length = ((frame[1].toInt() and 0xFF) shl 24) or
                ((frame[2].toInt() and 0xFF) shl 16) or
                ((frame[3].toInt() and 0xFF) shl 8) or
                (frame[4].toInt() and 0xFF)

        require(frame.size >= 5 + length) {
            "Frame incomplete: expected ${5 + length} bytes, got ${frame.size}"
        }

        return frame.copyOfRange(5, 5 + length)
    }

    /**
     * Check if a message is compressed based on its header.
     *
     * @param frame The framed data
     * @return true if the message is compressed
     */
    fun isCompressed(frame: ByteArray): Boolean {
        require(frame.isNotEmpty()) { "Frame is empty" }
        return frame[0] != 0.toByte()
    }

    /**
     * Get the message length from the frame header.
     *
     * @param frame The framed data
     * @return The message length in bytes
     */
    fun messageLength(frame: ByteArray): Int {
        require(frame.size >= 5) { "Frame too short to read length" }

        return ((frame[1].toInt() and 0xFF) shl 24) or
                ((frame[2].toInt() and 0xFF) shl 16) or
                ((frame[3].toInt() and 0xFF) shl 8) or
                (frame[4].toInt() and 0xFF)
    }

    /**
     * Read multiple messages from a byte stream.
     *
     * @param data The raw byte data potentially containing multiple frames
     * @return List of unframed messages
     */
    fun readMessages(data: ByteArray): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        var offset = 0

        while (offset + 5 <= data.size) {
            val length = ((data[offset + 1].toInt() and 0xFF) shl 24) or
                    ((data[offset + 2].toInt() and 0xFF) shl 16) or
                    ((data[offset + 3].toInt() and 0xFF) shl 8) or
                    (data[offset + 4].toInt() and 0xFF)

            if (offset + 5 + length > data.size) {
                break // Incomplete message
            }

            messages.add(data.copyOfRange(offset + 5, offset + 5 + length))
            offset += 5 + length
        }

        return messages
    }

    companion object {
        /**
         * Header size in bytes.
         */
        const val HEADER_SIZE = 5

        /**
         * Maximum message size (4MB default, same as gRPC default).
         */
        const val DEFAULT_MAX_MESSAGE_SIZE = 4 * 1024 * 1024
    }
}
