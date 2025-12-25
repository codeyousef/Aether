package codes.yousef.aether.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer

/**
 * Represents a request-response exchange.
 * Provides high-level methods for responding to requests.
 */
interface Exchange {
    val request: Request
    val response: Response
    val attributes: Attributes

    /**
     * Send a plain text response.
     */
    suspend fun respond(statusCode: Int = 200, body: String) {
        response.statusCode = statusCode
        response.setHeader("Content-Type", "text/plain; charset=utf-8")
        response.write(body)
        response.end()
    }

    /**
     * Send an HTML response.
     */
    suspend fun respondHtml(statusCode: Int = 200, html: String) {
        var finalHtml = html
        val hooks = attributes.get(HtmlResponseHooksKey)
        hooks?.forEach { hook -> finalHtml = hook(finalHtml) }

        response.statusCode = statusCode
        response.setHeader("Content-Type", "text/html; charset=utf-8")
        response.write(finalHtml)
        response.end()
    }

    companion object {
        val HtmlResponseHooksKey = Attributes.key<MutableList<(String) -> String>>("HtmlResponseHooks")
    }

    /**
     * Send a JSON response.
     */
    suspend fun <T> respondJson(statusCode: Int = 200, data: T, json: Json = Json, serializer: kotlinx.serialization.KSerializer<T>) {
        response.statusCode = statusCode
        response.setHeader("Content-Type", "application/json; charset=utf-8")
        val jsonString = json.encodeToString(serializer, data)
        response.write(jsonString)
        response.end()
    }

    /**
     * Send a CBOR response.
     */
    suspend fun respondCbor(statusCode: Int = 200, data: ByteArray) {
        response.statusCode = statusCode
        response.setHeader("Content-Type", "application/cbor")
        response.write(data)
        response.end()
    }

    /**
     * Send binary data.
     */
    suspend fun respondBytes(statusCode: Int = 200, contentType: String, bytes: ByteArray) {
        response.statusCode = statusCode
        response.setHeader("Content-Type", contentType)
        response.write(bytes)
        response.end()
    }

    /**
     * Send a redirect response.
     */
    suspend fun redirect(url: String, permanent: Boolean = false) {
        response.statusCode = if (permanent) 301 else 302
        response.setHeader("Location", url)
        response.end()
    }

    /**
     * Send a 404 Not Found response.
     */
    suspend fun notFound(message: String = "Not Found") {
        respond(404, message)
    }

    /**
     * Send a 500 Internal Server Error response.
     */
    suspend fun internalError(message: String = "Internal Server Error") {
        respond(500, message)
    }

    /**
     * Send a 400 Bad Request response.
     */
    suspend fun badRequest(message: String = "Bad Request") {
        respond(400, message)
    }

    /**
     * Send a 401 Unauthorized response.
     */
    suspend fun unauthorized(message: String = "Unauthorized") {
        respond(401, message)
    }

    /**
     * Send a 403 Forbidden response.
     */
    suspend fun forbidden(message: String = "Forbidden") {
        respond(403, message)
    }
}

/**
 * Send a JSON response using reified type parameter.
 */
suspend inline fun <reified T> Exchange.respondJson(
    statusCode: Int = 200,
    data: T,
    json: Json = Json
) {
    respondJson(statusCode, data, json, serializer<T>())
}
