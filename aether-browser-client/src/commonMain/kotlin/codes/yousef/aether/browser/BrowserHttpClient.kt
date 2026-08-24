package codes.yousef.aether.browser

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.serializer

/** HTTP methods exposed by the focused browser client. */
enum class BrowserHttpMethod {
    GET,
    POST
}

/** Fetch redirect behavior. The default rejects redirects for API requests. */
enum class BrowserRedirectPolicy(val fetchValue: String) {
    FOLLOW("follow"),
    ERROR("error"),
    MANUAL("manual")
}

/** Stable request metadata supplied to header and CSRF providers. */
data class BrowserRequestMetadata(
    val method: BrowserHttpMethod,
    val path: String
)

/** Supplies per-request headers without exposing browser primitives to the application. */
fun interface BrowserHeadersProvider {
    suspend fun headers(request: BrowserRequestMetadata): Map<String, String>

    companion object {
        val None: BrowserHeadersProvider = BrowserHeadersProvider { emptyMap() }
    }
}

/** A validated CSRF header emitted by [BrowserCsrfProvider]. */
data class BrowserCsrfHeader(
    val name: String = "X-CSRF-Token",
    val value: String
) {
    init {
        requireValidHeader(name, value)
    }
}

/** Supplies CSRF material for unsafe same-origin requests. */
fun interface BrowserCsrfProvider {
    suspend fun header(request: BrowserRequestMetadata): BrowserCsrfHeader?

    companion object {
        val None: BrowserCsrfProvider = BrowserCsrfProvider { null }

        fun fixed(value: String, headerName: String = "X-CSRF-Token"): BrowserCsrfProvider =
            BrowserCsrfProvider { BrowserCsrfHeader(headerName, value) }

        fun sessionStorage(
            storageKey: String,
            headerName: String = "X-CSRF-Token"
        ): BrowserCsrfProvider {
            require(storageKey.isNotBlank()) { "CSRF session storage key must not be blank" }
            require(storageKey.length <= MAXIMUM_STORAGE_KEY_CHARS) { "CSRF session storage key is too long" }
            require(storageKey.none(Char::isISOControl)) { "CSRF session storage key contains control characters" }
            return BrowserCsrfProvider {
                browserSessionStorageValue(storageKey)
                    ?.takeIf(String::isNotBlank)
                    ?.let { BrowserCsrfHeader(headerName, it) }
            }
        }
    }
}

/** Hard limits and request policy for [BrowserHttpClient]. */
class BrowserHttpClientConfig(
    defaultHeaders: Map<String, String> = emptyMap(),
    val headersProvider: BrowserHeadersProvider = BrowserHeadersProvider.None,
    val csrfProvider: BrowserCsrfProvider = BrowserCsrfProvider.None,
    val redirectPolicy: BrowserRedirectPolicy = BrowserRedirectPolicy.ERROR,
    val timeoutMillis: Int = DEFAULT_BROWSER_HTTP_TIMEOUT_MILLIS,
    val maximumRequestBytes: Int = DEFAULT_MAXIMUM_BROWSER_REQUEST_BYTES,
    val maximumResponseBytes: Int = DEFAULT_MAXIMUM_BROWSER_RESPONSE_BYTES
) {
    val defaultHeaders: Map<String, String> = defaultHeaders.toMap()

    init {
        require(timeoutMillis in 1..MAXIMUM_BROWSER_HTTP_TIMEOUT_MILLIS) {
            "Browser HTTP timeout must be between 1 ms and $MAXIMUM_BROWSER_HTTP_TIMEOUT_MILLIS ms"
        }
        require(maximumRequestBytes in 1..MAXIMUM_BROWSER_HTTP_BODY_BYTES) {
            "Browser HTTP request limit must be between 1 byte and $MAXIMUM_BROWSER_HTTP_BODY_BYTES bytes"
        }
        require(maximumResponseBytes in 1..MAXIMUM_BROWSER_HTTP_BODY_BYTES) {
            "Browser HTTP response limit must be between 1 byte and $MAXIMUM_BROWSER_HTTP_BODY_BYTES bytes"
        }
        requireValidHeaders(this.defaultHeaders)
    }
}

/** A bounded raw browser response. */
data class BrowserHttpResponse(
    val statusCode: Int,
    val statusText: String,
    val headers: Map<String, String>,
    val body: String
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/** Normalized information decoded from a non-success response body. */
data class BrowserHttpErrorEnvelope(
    val code: String?,
    val message: String?,
    val requestId: String?,
    val details: JsonElement?,
    val raw: JsonObject?
)

/** Browser transport failure categories which do not have an HTTP response. */
enum class BrowserHttpFailure {
    NETWORK,
    TIMEOUT,
    RESPONSE_TOO_LARGE,
    UNSUPPORTED
}

class BrowserHttpTransportException(
    val failure: BrowserHttpFailure,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class BrowserHttpRequestTooLargeException(
    val actualBytes: Int,
    val maximumBytes: Int
) : Exception("Browser HTTP request exceeded its configured hard limit")

class BrowserHttpResponseTooLargeException(
    val actualBytes: Int,
    val maximumBytes: Int
) : Exception("Browser HTTP response exceeded its configured hard limit")

class BrowserHttpResponseException(
    val response: BrowserHttpResponse,
    val error: BrowserHttpErrorEnvelope
) : Exception(error.message ?: "Browser HTTP request failed with status ${response.statusCode}") {
    val statusCode: Int get() = response.statusCode
}

class BrowserHttpDecodeException(
    val statusCode: Int,
    cause: Throwable
) : Exception("Browser HTTP response could not be decoded", cause)

/**
 * A same-origin, JSON-first browser client.
 *
 * Request paths must be root-relative, fetch credentials are always `same-origin`, and all
 * responses are bounded before they are returned to callers.
 */
class BrowserHttpClient internal constructor(
    private val config: BrowserHttpClientConfig,
    private val json: Json,
    private val transport: BrowserHttpTransport
) {
    constructor(
        config: BrowserHttpClientConfig = BrowserHttpClientConfig(),
        json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    ) : this(config, json, createPlatformBrowserHttpTransport())

    suspend inline fun <reified Response> get(
        path: String,
        headers: Map<String, String> = emptyMap()
    ): Response = get(path, serializer(), headers)

    suspend fun <Response> get(
        path: String,
        responseDeserializer: DeserializationStrategy<Response>,
        headers: Map<String, String> = emptyMap()
    ): Response = decode(
        execute(BrowserHttpMethod.GET, path, body = null, headers = headers),
        responseDeserializer
    )

    suspend inline fun <reified Request, reified Response> post(
        path: String,
        body: Request,
        headers: Map<String, String> = emptyMap()
    ): Response = post(path, body, serializer(), serializer(), headers)

    suspend fun <Request, Response> post(
        path: String,
        body: Request,
        requestSerializer: SerializationStrategy<Request>,
        responseDeserializer: DeserializationStrategy<Response>,
        headers: Map<String, String> = emptyMap()
    ): Response {
        val encoded = json.encodeToString(requestSerializer, body)
        return decode(
            execute(BrowserHttpMethod.POST, path, body = encoded, headers = headers),
            responseDeserializer
        )
    }

    suspend fun execute(
        method: BrowserHttpMethod,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): BrowserHttpResponse {
        requireSameOriginRequestPath(path)
        if (method == BrowserHttpMethod.GET) {
            require(body == null) { "GET requests must not include a body" }
        }

        val requestBytes = body?.encodeToByteArray()?.size ?: 0
        if (requestBytes > config.maximumRequestBytes) {
            throw BrowserHttpRequestTooLargeException(requestBytes, config.maximumRequestBytes)
        }

        val metadata = BrowserRequestMetadata(method, path)
        val mergedHeaders = linkedMapOf<String, String>()
        putHeader(mergedHeaders, "Accept", "application/json")
        config.defaultHeaders.forEach { (name, value) -> putHeader(mergedHeaders, name, value) }
        config.headersProvider.headers(metadata).forEach { (name, value) -> putHeader(mergedHeaders, name, value) }
        headers.forEach { (name, value) -> putHeader(mergedHeaders, name, value) }
        if (body != null && mergedHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            putHeader(mergedHeaders, "Content-Type", "application/json")
        }
        if (method == BrowserHttpMethod.POST) {
            config.csrfProvider.header(metadata)?.let { putHeader(mergedHeaders, it.name, it.value) }
        }
        requireValidHeaders(mergedHeaders)

        val response = try {
            transport.execute(
                BrowserTransportRequest(
                    method = method,
                    path = path,
                    headers = mergedHeaders.toMap(),
                    body = body,
                    redirectPolicy = config.redirectPolicy,
                    timeoutMillis = config.timeoutMillis,
                    maximumResponseBytes = config.maximumResponseBytes
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        }

        val responseBytes = response.body.encodeToByteArray().size
        if (responseBytes > config.maximumResponseBytes) {
            throw BrowserHttpResponseTooLargeException(responseBytes, config.maximumResponseBytes)
        }
        if (!response.isSuccessful) {
            throw BrowserHttpResponseException(response, decodeErrorEnvelope(response))
        }
        return response
    }

    private fun <Response> decode(
        response: BrowserHttpResponse,
        deserializer: DeserializationStrategy<Response>
    ): Response = try {
        json.decodeFromString(deserializer, response.body)
    } catch (error: SerializationException) {
        throw BrowserHttpDecodeException(response.statusCode, error)
    } catch (error: IllegalArgumentException) {
        throw BrowserHttpDecodeException(response.statusCode, error)
    }

    private fun decodeErrorEnvelope(response: BrowserHttpResponse): BrowserHttpErrorEnvelope {
        val root = runCatching { json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
        val errorElement = root?.get("error")
        val errorObject = errorElement as? JsonObject
        val primitiveError = (errorElement as? JsonPrimitive)?.contentOrNull
        val message = errorObject.string("message")
            ?: primitiveError
            ?: root.string("message")
            ?: response.statusText.takeIf(String::isNotBlank)
        return BrowserHttpErrorEnvelope(
            code = errorObject.string("code") ?: root.string("code"),
            message = message,
            requestId = root.string("requestId")
                ?: root.string("request_id")
                ?: response.headers.valueIgnoringCase("x-request-id"),
            details = errorObject?.get("details") ?: root?.get("details"),
            raw = root
        )
    }
}

const val DEFAULT_BROWSER_HTTP_TIMEOUT_MILLIS: Int = 30_000
const val MAXIMUM_BROWSER_HTTP_TIMEOUT_MILLIS: Int = 5 * 60_000
const val DEFAULT_MAXIMUM_BROWSER_REQUEST_BYTES: Int = 1 * 1_024 * 1_024
const val DEFAULT_MAXIMUM_BROWSER_RESPONSE_BYTES: Int = 4 * 1_024 * 1_024
const val MAXIMUM_BROWSER_HTTP_BODY_BYTES: Int = 16 * 1_024 * 1_024

private const val MAXIMUM_BROWSER_HTTP_HEADERS: Int = 128
private const val MAXIMUM_BROWSER_HTTP_HEADER_VALUE_CHARS: Int = 8_192
private const val MAXIMUM_STORAGE_KEY_CHARS: Int = 256
private val BROWSER_HTTP_HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")

internal fun requireSameOriginRequestPath(path: String) {
    require(path.startsWith('/') && !path.startsWith("//")) {
        "Browser HTTP paths must be same-origin root-relative paths"
    }
    require('\\' !in path) { "Browser HTTP paths must not contain backslashes" }
    require('#' !in path) { "Browser HTTP paths must not contain URL fragments" }
    require(path.none(Char::isISOControl)) { "Browser HTTP paths must not contain control characters" }
}

private fun requireValidHeaders(headers: Map<String, String>) {
    require(headers.size <= MAXIMUM_BROWSER_HTTP_HEADERS) { "Too many browser HTTP headers" }
    headers.forEach { (name, value) -> requireValidHeader(name, value) }
}

private fun requireValidHeader(name: String, value: String) {
    require(BROWSER_HTTP_HEADER_NAME.matches(name)) { "Invalid browser HTTP header name" }
    require(value.length <= MAXIMUM_BROWSER_HTTP_HEADER_VALUE_CHARS) { "Browser HTTP header value is too long" }
    require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
        "Browser HTTP header value contains a control delimiter"
    }
}

private fun putHeader(headers: MutableMap<String, String>, name: String, value: String) {
    headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(headers::remove)
    headers[name] = value
}

private fun JsonObject?.string(name: String): String? =
    this?.get(name)?.let { element ->
        (element as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }

private fun Map<String, String>.valueIgnoringCase(name: String): String? =
    entries.firstOrNull { entry -> entry.key.equals(name, ignoreCase = true) }?.value
