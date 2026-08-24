package codes.yousef.aether.browser

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

enum class BrowserBootstrapEncoding {
    JSON,
    BASE64_JSON
}

enum class BrowserBootstrapFailure {
    MISSING,
    EMPTY,
    TOO_LARGE,
    INVALID_ENCODING,
    INVALID_JSON
}

class BrowserBootstrapException(
    val failure: BrowserBootstrapFailure,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** Reads bounded bootstrap data by element ID without exposing DOM APIs to application code. */
object BrowserBootstrap {
    inline fun <reified T> decode(
        elementId: String,
        encoding: BrowserBootstrapEncoding = BrowserBootstrapEncoding.JSON,
        maximumBytes: Int = DEFAULT_MAXIMUM_BROWSER_BOOTSTRAP_BYTES,
        json: Json = Json { ignoreUnknownKeys = true }
    ): T = decode(elementId, serializer(), encoding, maximumBytes, json)

    fun <T> decode(
        elementId: String,
        deserializer: DeserializationStrategy<T>,
        encoding: BrowserBootstrapEncoding = BrowserBootstrapEncoding.JSON,
        maximumBytes: Int = DEFAULT_MAXIMUM_BROWSER_BOOTSTRAP_BYTES,
        json: Json = Json { ignoreUnknownKeys = true }
    ): T {
        requireValidBootstrapId(elementId)
        require(maximumBytes in 1..MAXIMUM_BROWSER_BOOTSTRAP_BYTES) {
            "Browser bootstrap limit must be between 1 byte and $MAXIMUM_BROWSER_BOOTSTRAP_BYTES bytes"
        }
        val stored = readPlatformBootstrapText(elementId)
            ?: throw BrowserBootstrapException(
                BrowserBootstrapFailure.MISSING,
                "Browser bootstrap element was not found"
            )
        return decodeStoredBootstrap(stored, deserializer, encoding, maximumBytes, json)
    }
}

internal fun <T> decodeStoredBootstrap(
    stored: String,
    deserializer: DeserializationStrategy<T>,
    encoding: BrowserBootstrapEncoding,
    maximumBytes: Int,
    json: Json
): T {
    require(maximumBytes in 1..MAXIMUM_BROWSER_BOOTSTRAP_BYTES) {
        "Browser bootstrap limit must be between 1 byte and $MAXIMUM_BROWSER_BOOTSTRAP_BYTES bytes"
    }
    if (stored.isBlank()) {
        throw BrowserBootstrapException(BrowserBootstrapFailure.EMPTY, "Browser bootstrap element was empty")
    }
    if (stored.encodeToByteArray().size > maximumBytes * 2) {
        throw BrowserBootstrapException(
            BrowserBootstrapFailure.TOO_LARGE,
            "Browser bootstrap data exceeded its configured hard limit"
        )
    }
    val decoded = when (encoding) {
        BrowserBootstrapEncoding.JSON -> stored
        BrowserBootstrapEncoding.BASE64_JSON -> try {
            decodePlatformBase64Utf8(stored.trim())
        } catch (error: Throwable) {
            throw BrowserBootstrapException(
                BrowserBootstrapFailure.INVALID_ENCODING,
                "Browser bootstrap data was not valid base64-encoded UTF-8",
                error
            )
        }
    }
    if (decoded.encodeToByteArray().size > maximumBytes) {
        throw BrowserBootstrapException(
            BrowserBootstrapFailure.TOO_LARGE,
            "Browser bootstrap data exceeded its configured hard limit"
        )
    }
    return try {
        json.decodeFromString(deserializer, decoded)
    } catch (error: SerializationException) {
        throw BrowserBootstrapException(
            BrowserBootstrapFailure.INVALID_JSON,
            "Browser bootstrap data could not be decoded",
            error
        )
    } catch (error: IllegalArgumentException) {
        throw BrowserBootstrapException(
            BrowserBootstrapFailure.INVALID_JSON,
            "Browser bootstrap data could not be decoded",
            error
        )
    }
}

const val DEFAULT_MAXIMUM_BROWSER_BOOTSTRAP_BYTES: Int = 1 * 1_024 * 1_024
const val MAXIMUM_BROWSER_BOOTSTRAP_BYTES: Int = 4 * 1_024 * 1_024

private fun requireValidBootstrapId(elementId: String) {
    require(elementId.isNotBlank()) { "Browser bootstrap element ID must not be blank" }
    require(elementId.length <= 256) { "Browser bootstrap element ID is too long" }
    require(elementId.none(Char::isISOControl)) { "Browser bootstrap element ID contains control characters" }
}
