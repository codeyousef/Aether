package codes.yousef.aether.core

/**
 * Represents an HTTP request.
 * Provides access to request data including method, path, headers, cookies, and body.
 */
interface Request {
    val method: HttpMethod
    val uri: String
    val path: String
    val query: String?
    val headers: Headers
    val cookies: Cookies

    /**
     * Read the request body as bytes.
     * This is a suspend function as it may involve I/O.
     */
    suspend fun bodyBytes(): ByteArray

    /**
     * Read the request body as text (UTF-8).
     */
    suspend fun bodyText(): String = bodyBytes().decodeToString()

    /**
     * Parse query parameters from the query string.
     * Returns a map of parameter names to lists of values.
     */
    fun queryParameters(): Map<String, List<String>> {
        val query = query ?: return emptyMap()
        return query.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index > 0) {
                    val name = part.substring(0, index)
                    val value = part.substring(index + 1)
                    name to value
                } else {
                    null
                }
            }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * Get the first value of a query parameter.
     */
    fun queryParameter(name: String): String? = queryParameters()[name]?.firstOrNull()
}
