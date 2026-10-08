package codes.yousef.aether.core.upload

import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.RequestBodyStreamLimits
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Metadata announced before bytes for one incrementally parsed multipart part. */
data class StreamingMultipartPart(
    val name: String,
    val filename: String?,
    val contentType: String?,
    val headers: Map<String, String>
)

/** Summary and retained transport high-water for a completed multipart stream. */
data class StreamingMultipartResult(
    val partCount: Int,
    val fileCount: Int,
    val transportHighWaterBytes: Long
)

/**
 * Sequential multipart destination. Calls never overlap. [onPartData] may retain bytes only by
 * copying them before it returns; the parser reuses its bounded transfer buffer.
 */
interface StreamingMultipartSink {
    suspend fun onPartBegin(part: StreamingMultipartPart)
    suspend fun onPartData(bytes: ByteArray, offset: Int, length: Int)
    suspend fun onPartEnd()
}

/**
 * Consume a multipart body incrementally. The parser retains only bounded headers, one boundary
 * candidate, and one transfer chunk. A compatibility-buffered server still works, but cannot offer
 * bounded transport retention.
 */
suspend fun Exchange.streamMultipart(
    config: UploadConfig = UploadConfig(),
    maximumChunkBytes: Int = 64 * 1024,
    deadline: Duration = 30.seconds,
    sink: StreamingMultipartSink
): StreamingMultipartResult {
    require(maximumChunkBytes > 0) { "Maximum multipart chunk size must be positive" }
    val contentType = request.headers.get("Content-Type")
        ?: throw UploadException("Missing Content-Type header", UploadErrorCode.PARSE_ERROR)
    val boundary = multipartBoundary(contentType)
    val stream = request.openBodyStream(
        RequestBodyStreamLimits(
            maximumTotalBytes = config.maxRequestSize,
            maximumChunkBytes = maximumChunkBytes,
            deadline = deadline
        )
    )
    if (stream == null) {
        val body = request.bodyBytes()
        val parsed = MultipartParser(config).parse(contentType, body)
        for (part in parsed.all()) {
            when (part) {
                is MultipartPart.FormField -> {
                    sink.onPartBegin(StreamingMultipartPart(part.name, null, part.contentType, emptyMap()))
                    val bytes = part.value.encodeToByteArray()
                    sink.onPartData(bytes, 0, bytes.size)
                }
                is MultipartPart.FilePart -> {
                    sink.onPartBegin(StreamingMultipartPart(part.name, part.filename, part.contentType, emptyMap()))
                    val bytes = part.bytes()
                    sink.onPartData(bytes, 0, bytes.size)
                }
            }
            sink.onPartEnd()
        }
        return StreamingMultipartResult(parsed.size, parsed.files().size, body.size.toLong())
    }

    return IncrementalMultipartParser(boundary, config, sink).run(stream)
}

private class IncrementalMultipartParser(
    boundary: String,
    private val config: UploadConfig,
    private val sink: StreamingMultipartSink
) {
    private val opening = "--$boundary\r\n".encodeToByteArray()
    private val delimiter = "\r\n--$boundary".encodeToByteArray()
    private val candidate = ByteArray(delimiter.size)
    private val output = ByteArray(8 * 1024)
    private val headers = ByteArray(16 * 1024)
    private var state = State.OPENING
    private var openingIndex = 0
    private var headerSize = 0
    private var headerTerminator = 0
    private var delimiterIndex = 0
    private var suffixIndex = 0
    private var outputSize = 0
    private var fileCount = 0
    private var partCount = 0
    private var partBytes = 0L
    private var currentPart: StreamingMultipartPart? = null

    suspend fun run(stream: codes.yousef.aether.core.RequestBodyStream): StreamingMultipartResult {
        stream.consume { chunk ->
            var index = 0
            while (index < chunk.size) {
                accept(chunk[index])
                index++
            }
        }
        flushOutput()
        if (state != State.DONE) fail("Multipart body ended before its closing boundary")
        return StreamingMultipartResult(partCount, fileCount, stream.highWaterBytes)
    }

    private suspend fun accept(byte: Byte) {
        when (state) {
            State.OPENING -> {
                if (byte != opening[openingIndex++]) fail("Invalid opening multipart boundary")
                if (openingIndex == opening.size) state = State.HEADERS
            }
            State.HEADERS -> acceptHeader(byte)
            State.CONTENT -> acceptContent(byte)
            State.SUFFIX -> acceptSuffix(byte)
            State.DONE -> if (byte != '\r'.code.toByte() && byte != '\n'.code.toByte()) {
                fail("Unexpected data after closing multipart boundary")
            }
        }
    }

    private suspend fun acceptHeader(byte: Byte) {
        if (headerSize == headers.size) fail("Multipart part headers exceed ${headers.size} bytes")
        headers[headerSize++] = byte
        val expected = HEADER_TERMINATOR[headerTerminator]
        headerTerminator = if (byte == expected) headerTerminator + 1 else if (byte == HEADER_TERMINATOR[0]) 1 else 0
        if (headerTerminator == HEADER_TERMINATOR.size) {
            val raw = headers.decodeToString(0, headerSize - HEADER_TERMINATOR.size)
            val parsedHeaders = raw.split("\r\n").associate { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) fail("Malformed multipart header")
                line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
            val disposition = parsedHeaders["content-disposition"]
                ?: fail("Multipart part is missing Content-Disposition")
            val name = dispositionParameter(disposition, "name")
                ?: fail("Multipart part is missing a name")
            val filename = dispositionParameter(disposition, "filename")
            val contentType = parsedHeaders["content-type"]
            validatePart(filename, contentType)
            currentPart = StreamingMultipartPart(name, filename, contentType, parsedHeaders)
            partBytes = 0
            headerSize = 0
            headerTerminator = 0
            if (partCount >= config.maxParts) {
                throw UploadException("Too many multipart parts", UploadErrorCode.TOO_MANY_PARTS)
            }
            partCount++
            sink.onPartBegin(currentPart!!)
            state = State.CONTENT
        }
    }

    private suspend fun acceptContent(byte: Byte) {
        if (byte == delimiter[delimiterIndex]) {
            candidate[delimiterIndex++] = byte
            if (delimiterIndex == delimiter.size) {
                flushOutput()
                sink.onPartEnd()
                currentPart = null
                delimiterIndex = 0
                suffixIndex = 0
                state = State.SUFFIX
            }
            return
        }
        if (delimiterIndex > 0) {
            appendData(candidate, delimiterIndex)
            delimiterIndex = 0
            acceptContent(byte)
        } else {
            appendData(byte)
        }
    }

    private suspend fun acceptSuffix(byte: Byte) {
        when (suffixIndex) {
            0 -> when (byte) {
                '-'.code.toByte() -> suffixIndex = 1
                '\r'.code.toByte() -> suffixIndex = 2
                else -> fail("Malformed multipart boundary suffix")
            }
            1 -> {
                if (byte != '-'.code.toByte()) fail("Malformed closing multipart boundary")
                state = State.DONE
            }
            2 -> {
                if (byte != '\n'.code.toByte()) fail("Malformed multipart boundary separator")
                state = State.HEADERS
            }
        }
    }

    private suspend fun appendData(byte: Byte) {
        output[outputSize++] = byte
        partBytes++
        enforcePartSize()
        if (outputSize == output.size) flushOutput()
    }

    private suspend fun appendData(bytes: ByteArray, length: Int) {
        var index = 0
        while (index < length) appendData(bytes[index++])
    }

    private suspend fun flushOutput() {
        if (outputSize == 0) return
        sink.onPartData(output, 0, outputSize)
        outputSize = 0
    }

    private fun enforcePartSize() {
        if (currentPart?.filename != null && partBytes > config.maxFileSize) {
            throw UploadException("Multipart file exceeds maximum ${config.maxFileSize}", UploadErrorCode.FILE_TOO_LARGE)
        }
    }

    private fun validatePart(filename: String?, contentType: String?) {
        if (filename == null) return
        fileCount++
        if (fileCount > config.maxFiles) {
            throw UploadException("Too many multipart files", UploadErrorCode.TOO_MANY_FILES)
        }
        if (config.allowedContentTypes.isNotEmpty() && contentType !in config.allowedContentTypes) {
            throw UploadException("Multipart content type is not allowed", UploadErrorCode.INVALID_CONTENT_TYPE)
        }
        if (config.allowedExtensions.isNotEmpty()) {
            val extension = filename.substringAfterLast('.', "").lowercase()
            if (extension !in config.allowedExtensions) {
                throw UploadException("Multipart file extension is not allowed", UploadErrorCode.INVALID_EXTENSION)
            }
        }
    }

    private fun fail(message: String): Nothing =
        throw UploadException(message, UploadErrorCode.PARSE_ERROR)

    private enum class State { OPENING, HEADERS, CONTENT, SUFFIX, DONE }

    private companion object {
        val HEADER_TERMINATOR = byteArrayOf(13, 10, 13, 10)
    }
}

private fun multipartBoundary(contentType: String): String {
    if (!contentType.substringBefore(';').trim().equals("multipart/form-data", ignoreCase = true)) {
        throw UploadException("Expected multipart/form-data", UploadErrorCode.PARSE_ERROR)
    }
    val raw = contentType.split(';').drop(1).firstNotNullOfOrNull { segment ->
        val pair = segment.trim().split('=', limit = 2)
        pair.takeIf { it.size == 2 && it[0].trim().equals("boundary", ignoreCase = true) }?.get(1)?.trim()
    } ?: throw UploadException("Missing multipart boundary", UploadErrorCode.PARSE_ERROR)
    val boundary = raw.removeSurrounding("\"")
    if (boundary.isEmpty() || boundary.length > 70 || boundary.any { it == '\r' || it == '\n' }) {
        throw UploadException("Invalid multipart boundary", UploadErrorCode.PARSE_ERROR)
    }
    return boundary
}

private fun dispositionParameter(disposition: String, name: String): String? =
    disposition.split(';').drop(1).firstNotNullOfOrNull { segment ->
        val pair = segment.trim().split('=', limit = 2)
        pair.takeIf { it.size == 2 && it[0].trim().equals(name, ignoreCase = true) }
            ?.get(1)?.trim()?.removeSurrounding("\"")
    }
