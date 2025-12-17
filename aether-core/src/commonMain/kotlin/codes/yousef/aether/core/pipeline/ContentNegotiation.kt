package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.Attributes
import codes.yousef.aether.core.Exchange

/**
 * Content type constants.
 */
object ContentType {
    const val JSON = "application/json"
    const val HTML = "text/html"
    const val CBOR = "application/cbor"
    const val XML = "application/xml"
    const val TEXT = "text/plain"
    const val FORM = "application/x-www-form-urlencoded"
    const val MULTIPART = "multipart/form-data"
}

/**
 * Represents the preferred content type based on Accept header.
 */
enum class PreferredContentType {
    JSON,
    HTML,
    CBOR,
    XML,
    TEXT,
    UNKNOWN
}

/**
 * Attribute key for storing the preferred content type.
 */
val PREFERRED_CONTENT_TYPE = Attributes.key<PreferredContentType>("preferredContentType")

/**
 * ContentNegotiation middleware automatically detects the Accept header
 * and stores the preferred content type in the exchange attributes.
 *
 * This allows handlers to easily respond with the appropriate format.
 */
class ContentNegotiation {
    /**
     * Parse quality values from Accept header.
     * Example: "text/html, application/json;q=0.9, star/star;q=0.8"
     */
    private fun parseAcceptHeader(acceptHeader: String): List<Pair<String, Double>> {
        return acceptHeader.split(',')
            .mapNotNull { part ->
                val trimmed = part.trim()
                val segments = trimmed.split(';')
                val mediaType = segments[0].trim()
                val quality = segments.drop(1)
                    .find { it.trim().startsWith("q=") }
                    ?.substringAfter("q=")
                    ?.trim()
                    ?.toDoubleOrNull()
                    ?: 1.0

                if (mediaType.isNotEmpty()) mediaType to quality else null
            }
            .sortedByDescending { it.second }
    }

    /**
     * Determine the preferred content type from the Accept header.
     */
    private fun determinePreferredType(acceptHeader: String?): PreferredContentType {
        if (acceptHeader.isNullOrBlank()) {
            return PreferredContentType.UNKNOWN
        }

        val parsed = parseAcceptHeader(acceptHeader)

        for ((mediaType, _) in parsed) {
            when {
                mediaType.contains("application/json", ignoreCase = true) -> return PreferredContentType.JSON
                mediaType.contains("text/html", ignoreCase = true) -> return PreferredContentType.HTML
                mediaType.contains("application/cbor", ignoreCase = true) -> return PreferredContentType.CBOR
                mediaType.contains("application/xml", ignoreCase = true) ||
                    mediaType.contains("text/xml", ignoreCase = true) -> return PreferredContentType.XML
                mediaType.contains("text/plain", ignoreCase = true) -> return PreferredContentType.TEXT
            }
        }

        return PreferredContentType.UNKNOWN
    }

    /**
     * Create the middleware function.
     */
    fun middleware(): Middleware = { exchange, next ->
        val acceptHeader = exchange.request.headers["Accept"]
        val preferredType = determinePreferredType(acceptHeader)
        exchange.attributes.put(PREFERRED_CONTENT_TYPE, preferredType)
        next()
    }
}

/**
 * Extension function to easily get the preferred content type from an exchange.
 */
fun Exchange.preferredContentType(): PreferredContentType {
    return attributes.get(PREFERRED_CONTENT_TYPE) ?: PreferredContentType.UNKNOWN
}

/**
 * Install ContentNegotiation middleware.
 */
fun Pipeline.installContentNegotiation() {
    use(ContentNegotiation().middleware())
}
