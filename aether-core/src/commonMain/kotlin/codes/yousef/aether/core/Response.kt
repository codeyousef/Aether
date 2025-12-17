package codes.yousef.aether.core

/**
 * Represents an HTTP response that can be written to incrementally.
 * Supports streaming response data.
 */
interface Response {
    var statusCode: Int
    var statusMessage: String?
    val headers: Headers.HeadersBuilder
    val cookies: MutableList<Cookie>

    /**
     * Write data to the response body.
     */
    suspend fun write(data: ByteArray)

    /**
     * Write text to the response body (UTF-8).
     */
    suspend fun write(text: String) = write(text.encodeToByteArray())

    /**
     * End the response. Must be called to complete the response.
     */
    suspend fun end()

    /**
     * Set a response header (replaces existing).
     */
    fun setHeader(name: String, value: String) {
        headers.set(name, value)
    }

    /**
     * Add a response header (allows duplicates).
     */
    fun addHeader(name: String, value: String) {
        headers.add(name, value)
    }

    /**
     * Add a cookie to the response.
     */
    fun setCookie(cookie: Cookie) {
        cookies.add(cookie)
    }
}
