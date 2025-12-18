package codes.yousef.aether.core.upload

/**
 * JVM implementation of multipart parser.
 */
actual class MultipartParser actual constructor(
    private val config: UploadConfig
) {
    /**
     * Parse multipart data from request body.
     */
    actual suspend fun parse(
        contentType: String,
        body: ByteArray
    ): MultipartData {
        val boundary = extractBoundary(contentType)
            ?: throw UploadException("Missing boundary in Content-Type", UploadErrorCode.PARSE_ERROR)

        val parts = parseMultipartBody(body, boundary)
        return MultipartData(parts)
    }

    private fun extractBoundary(contentType: String): String? {
        val boundaryPrefix = "boundary="
        val index = contentType.indexOf(boundaryPrefix, ignoreCase = true)
        if (index < 0) return null

        var boundary = contentType.substring(index + boundaryPrefix.length)
        
        // Remove quotes if present
        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length - 1)
        }

        // Handle parameters after boundary
        val semicolonIndex = boundary.indexOf(';')
        if (semicolonIndex >= 0) {
            boundary = boundary.substring(0, semicolonIndex).trim()
        }

        return boundary
    }

    private fun parseMultipartBody(body: ByteArray, boundary: String): List<MultipartPart> {
        val parts = mutableListOf<MultipartPart>()
        val boundaryBytes = "--$boundary".toByteArray()
        val finalBoundaryBytes = "--$boundary--".toByteArray()

        var pos = 0
        
        // Skip preamble - find first boundary
        pos = findBytes(body, boundaryBytes, pos)
        if (pos < 0) {
            throw UploadException("No boundary found in body", UploadErrorCode.PARSE_ERROR)
        }
        pos += boundaryBytes.size

        while (pos < body.size) {
            // Skip CRLF after boundary
            if (pos < body.size - 1 && body[pos] == '\r'.code.toByte() && body[pos + 1] == '\n'.code.toByte()) {
                pos += 2
            } else if (pos < body.size && (body[pos] == '\r'.code.toByte() || body[pos] == '\n'.code.toByte())) {
                pos++
            }

            // Check for final boundary
            if (pos >= body.size) break

            // Parse headers
            val headersEnd = findHeadersEnd(body, pos)
            if (headersEnd < 0) break

            val headerBytes = body.copyOfRange(pos, headersEnd)
            val headers = parsePartHeaders(headerBytes.decodeToString())
            pos = headersEnd + 4 // Skip CRLF CRLF

            // Find next boundary
            val nextBoundary = findBytes(body, boundaryBytes, pos)
            if (nextBoundary < 0) break

            // Extract content (excluding CRLF before boundary)
            var contentEnd = nextBoundary
            if (contentEnd >= 2 && body[contentEnd - 2] == '\r'.code.toByte() && body[contentEnd - 1] == '\n'.code.toByte()) {
                contentEnd -= 2
            } else if (contentEnd >= 1 && body[contentEnd - 1] == '\n'.code.toByte()) {
                contentEnd -= 1
            }

            val content = body.copyOfRange(pos, contentEnd)
            
            // Create part
            val contentDisposition = headers["content-disposition"] ?: ""
            val name = extractParam(contentDisposition, "name") ?: continue
            val filename = extractParam(contentDisposition, "filename")
            val partContentType = headers["content-type"] ?: "text/plain"

            if (filename != null) {
                // File part
                val fileContent = content.copyOf() // Create a copy to avoid issues with closure
                parts.add(MultipartPart.FilePart(
                    name = name,
                    filename = filename,
                    contentType = partContentType,
                    size = content.size.toLong(),
                    dataProvider = { fileContent }
                ))
            } else {
                // Form field
                parts.add(MultipartPart.FormField(
                    name = name,
                    value = content.decodeToString(),
                    contentType = partContentType
                ))
            }

            pos = nextBoundary + boundaryBytes.size
            
            // Check if this is the final boundary
            if (pos < body.size - 1 && body[pos] == '-'.code.toByte() && body[pos + 1] == '-'.code.toByte()) {
                break
            }
        }

        return parts
    }

    private fun findBytes(source: ByteArray, target: ByteArray, startPos: Int): Int {
        if (target.isEmpty()) return startPos
        if (source.size < target.size) return -1

        outer@ for (i in startPos..(source.size - target.size)) {
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    private fun findHeadersEnd(body: ByteArray, startPos: Int): Int {
        // Look for CRLF CRLF (end of headers)
        for (i in startPos until body.size - 3) {
            if (body[i] == '\r'.code.toByte() && 
                body[i + 1] == '\n'.code.toByte() &&
                body[i + 2] == '\r'.code.toByte() && 
                body[i + 3] == '\n'.code.toByte()) {
                return i
            }
        }
        // Also try LF LF
        for (i in startPos until body.size - 1) {
            if (body[i] == '\n'.code.toByte() && body[i + 1] == '\n'.code.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun parsePartHeaders(headerSection: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val lines = headerSection.split(Regex("\r?\n"))
        
        for (line in lines) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val name = line.substring(0, colonIndex).trim().lowercase()
                val value = line.substring(colonIndex + 1).trim()
                headers[name] = value
            }
        }
        
        return headers
    }

    private fun extractParam(header: String, paramName: String): String? {
        val pattern = Regex("""$paramName\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
        return pattern.find(header)?.groupValues?.getOrNull(1)?.trim()
    }
}
