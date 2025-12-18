package codes.yousef.aether.core.upload

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Exchange

/**
 * Attribute key for accessing multipart data from an Exchange.
 */
val MultipartDataAttributeKey = AttributeKey<MultipartData>("aether.upload.multipart", MultipartData::class)

/**
 * Extension function to parse multipart form data.
 * Caches the result in attributes.
 */
suspend fun Exchange.parseMultipart(config: UploadConfig = UploadConfig()): MultipartData {
    // Check cache first
    attributes.get(MultipartDataAttributeKey)?.let { return it }

    val contentType = request.headers.get("Content-Type")
        ?: throw UploadException("Missing Content-Type header", UploadErrorCode.PARSE_ERROR)

    if (!contentType.startsWith("multipart/form-data")) {
        throw UploadException(
            "Invalid Content-Type: expected multipart/form-data, got $contentType",
            UploadErrorCode.PARSE_ERROR
        )
    }

    val body = request.bodyBytes()
    
    // Check request size
    if (body.size > config.maxRequestSize) {
        throw UploadException(
            "Request size ${body.size} exceeds maximum ${config.maxRequestSize}",
            UploadErrorCode.REQUEST_TOO_LARGE
        )
    }

    val parser = MultipartParser(config)
    val multipartData = parser.parse(contentType, body)

    // Validate file count
    if (multipartData.files().size > config.maxFiles) {
        throw UploadException(
            "Too many files: ${multipartData.files().size} exceeds maximum ${config.maxFiles}",
            UploadErrorCode.TOO_MANY_FILES
        )
    }

    // Validate each file
    for (file in multipartData.files()) {
        // Check file size
        if (file.size > config.maxFileSize) {
            throw UploadException(
                "File '${file.filename}' size ${file.size} exceeds maximum ${config.maxFileSize}",
                UploadErrorCode.FILE_TOO_LARGE
            )
        }

        // Check content type
        if (config.allowedContentTypes.isNotEmpty() && 
            file.contentType !in config.allowedContentTypes) {
            throw UploadException(
                "File '${file.filename}' content type '${file.contentType}' not allowed",
                UploadErrorCode.INVALID_CONTENT_TYPE
            )
        }

        // Check extension
        if (config.allowedExtensions.isNotEmpty()) {
            val extension = file.filename.substringAfterLast('.', "").lowercase()
            if (extension !in config.allowedExtensions) {
                throw UploadException(
                    "File '${file.filename}' extension '$extension' not allowed",
                    UploadErrorCode.INVALID_EXTENSION
                )
            }
        }
    }

    // Cache and return
    attributes.put(MultipartDataAttributeKey, multipartData)
    return multipartData
}

/**
 * Extension function to get a single uploaded file.
 */
suspend fun Exchange.file(name: String, config: UploadConfig = UploadConfig()): MultipartPart.FilePart? {
    return parseMultipart(config).file(name)
}

/**
 * Extension function to get all uploaded files.
 */
suspend fun Exchange.files(config: UploadConfig = UploadConfig()): List<MultipartPart.FilePart> {
    return parseMultipart(config).files()
}

/**
 * Extension function to get a form field from multipart data.
 */
suspend fun Exchange.formField(name: String, config: UploadConfig = UploadConfig()): String? {
    return parseMultipart(config).getValue(name)
}
