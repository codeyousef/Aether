package codes.yousef.aether.db.http

/**
 * Platform-agnostic HTTP client interface for database drivers.
 * Implementations exist for JVM (ktor/java.net.http) and Wasm (fetch).
 */
expect class HttpClient() {
    /**
     * Performs an HTTP GET request.
     * @param url The full URL including query parameters
     * @param headers HTTP headers to include
     * @return The response body as a string
     */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    /**
     * Performs an HTTP POST request.
     * @param url The URL to post to
     * @param body The request body (typically JSON)
     * @param headers HTTP headers to include
     * @return The response body as a string
     */
    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse

    /**
     * Performs an HTTP PATCH request.
     * @param url The URL to patch
     * @param body The request body (typically JSON)
     * @param headers HTTP headers to include
     * @return The response body as a string
     */
    suspend fun patch(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse

    /**
     * Performs an HTTP DELETE request.
     * @param url The URL to delete
     * @param headers HTTP headers to include
     * @return The response body as a string
     */
    suspend fun delete(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    /**
     * Closes the HTTP client and releases resources.
     */
    fun close()
}

/**
 * HTTP response containing status code and body.
 */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/**
 * Exception thrown when HTTP operations fail.
 */
class HttpException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)
