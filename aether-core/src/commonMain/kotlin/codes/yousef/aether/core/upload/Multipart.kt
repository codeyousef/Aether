package codes.yousef.aether.core.upload

import kotlinx.serialization.Serializable

/**
 * Represents a single part in a multipart request.
 */
sealed class MultipartPart {
    /**
     * A form field (text data).
     */
    data class FormField(
        val name: String,
        val value: String,
        val contentType: String? = null
    ) : MultipartPart()

    /**
     * A file upload.
     */
    data class FilePart(
        val name: String,
        val filename: String,
        val contentType: String,
        val size: Long,
        private val dataProvider: suspend () -> ByteArray
    ) : MultipartPart() {
        /**
         * Read the file content as bytes.
         */
        suspend fun bytes(): ByteArray = dataProvider()

        /**
         * Read the file content as text.
         */
        suspend fun text(charset: String = "UTF-8"): String = 
            bytes().decodeToString()
    }
}

/**
 * Parsed multipart request containing all parts.
 */
class MultipartData(
    private val parts: List<MultipartPart>
) {
    /**
     * Get all parts.
     */
    fun all(): List<MultipartPart> = parts.toList()

    /**
     * Get all form fields.
     */
    fun formFields(): List<MultipartPart.FormField> = 
        parts.filterIsInstance<MultipartPart.FormField>()

    /**
     * Get all file parts.
     */
    fun files(): List<MultipartPart.FilePart> = 
        parts.filterIsInstance<MultipartPart.FilePart>()

    /**
     * Get a form field by name.
     */
    fun formField(name: String): MultipartPart.FormField? =
        formFields().find { it.name == name }

    /**
     * Get a file part by name.
     */
    fun file(name: String): MultipartPart.FilePart? =
        files().find { it.name == name }

    /**
     * Get form field value by name.
     */
    fun getValue(name: String): String? = formField(name)?.value

    /**
     * Check if a part with the given name exists.
     */
    fun contains(name: String): Boolean = parts.any { 
        when (it) {
            is MultipartPart.FormField -> it.name == name
            is MultipartPart.FilePart -> it.name == name
        }
    }

    /**
     * Get the number of parts.
     */
    val size: Int get() = parts.size

    /**
     * Check if the multipart data is empty.
     */
    val isEmpty: Boolean get() = parts.isEmpty()
}

/**
 * Configuration for file upload handling.
 */
data class UploadConfig(
    /**
     * Maximum file size in bytes (default: 10MB).
     */
    val maxFileSize: Long = 10 * 1024 * 1024,

    /**
     * Maximum total request size in bytes (default: 50MB).
     */
    val maxRequestSize: Long = 50 * 1024 * 1024,

    /**
     * Maximum number of files allowed (default: 10).
     */
    val maxFiles: Int = 10,

    /**
     * Allowed content types (empty = all allowed).
     */
    val allowedContentTypes: Set<String> = emptySet(),

    /**
     * Allowed file extensions (empty = all allowed).
     */
    val allowedExtensions: Set<String> = emptySet(),

    /**
     * Directory for temporary file storage.
     */
    val tempDirectory: String? = null,

    /**
     * Whether to delete temporary files automatically.
     */
    val autoDeleteTempFiles: Boolean = true
)

/**
 * Exception thrown when file upload validation fails.
 */
class UploadException(
    message: String,
    val errorCode: UploadErrorCode,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Error codes for upload failures.
 */
enum class UploadErrorCode {
    FILE_TOO_LARGE,
    REQUEST_TOO_LARGE,
    TOO_MANY_FILES,
    INVALID_CONTENT_TYPE,
    INVALID_EXTENSION,
    PARSE_ERROR,
    IO_ERROR
}

/**
 * Platform-specific multipart parser.
 */
expect class MultipartParser(config: UploadConfig = UploadConfig()) {
    /**
     * Parse multipart data from request body.
     */
    suspend fun parse(
        contentType: String,
        body: ByteArray
    ): MultipartData
}
