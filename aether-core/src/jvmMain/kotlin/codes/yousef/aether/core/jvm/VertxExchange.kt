package codes.yousef.aether.core.jvm

import codes.yousef.aether.core.*
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.http.HttpServerResponse
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CompletableDeferred

/**
 * Vert.x implementation of Request.
 */
private class VertxRequest(
    private val vertxRequest: HttpServerRequest,
    private val bodyDeferred: CompletableDeferred<ByteArray>
) : Request {
    override val method: HttpMethod = parseMethod(vertxRequest.method().name())

    override val uri: String = vertxRequest.uri()

    override val path: String = vertxRequest.path()

    override val query: String? = vertxRequest.query()

    override val headers: Headers by lazy {
        val builder = Headers.HeadersBuilder()
        vertxRequest.headers().forEach { entry ->
            builder.add(entry.key, entry.value)
        }
        builder.build()
    }

    override val cookies: Cookies by lazy {
        val cookieHeader = vertxRequest.getHeader("Cookie")
        Cookies.parse(cookieHeader)
    }

    override suspend fun bodyBytes(): ByteArray = bodyDeferred.await()

    private fun parseMethod(name: String): HttpMethod {
        return try {
            HttpMethod.valueOf(name.uppercase())
        } catch (e: IllegalArgumentException) {
            HttpMethod.GET
        }
    }
}

/**
 * Vert.x implementation of Response.
 */
private class VertxResponse(
    private val vertxResponse: HttpServerResponse
) : Response {
    override var statusCode: Int
        get() = vertxResponse.statusCode
        set(value) {
            vertxResponse.statusCode = value
        }

    override var statusMessage: String?
        get() = vertxResponse.statusMessage
        set(value) {
            if (value != null) {
                vertxResponse.statusMessage = value
            }
        }

    override val headers: Headers.HeadersBuilder = Headers.HeadersBuilder()

    override val cookies: MutableList<Cookie> = mutableListOf()

    private var headersWritten = false

    /**
     * Write headers to the Vert.x response if not already written.
     */
    private fun writeHeaders() {
        if (!headersWritten) {
            // Enable chunked transfer encoding if Content-Length is not set
            if (!headers.build().contains("Content-Length")) {
                vertxResponse.isChunked = true
            }
            headers.build().entries().forEach { (name, values) ->
                values.forEach { value ->
                    vertxResponse.putHeader(name, value)
                }
            }
            cookies.forEach { cookie ->
                vertxResponse.putHeader("Set-Cookie", cookie.toSetCookieHeader())
            }
            headersWritten = true
        }
    }

    override suspend fun write(data: ByteArray) {
        writeHeaders()
        vertxResponse.write(io.vertx.core.buffer.Buffer.buffer(data)).coAwait()
    }

    override suspend fun end() {
        writeHeaders()
        vertxResponse.end().coAwait()
    }
}

/**
 * Vert.x implementation of Exchange.
 */
class VertxExchange(
    override val request: Request,
    override val response: Response,
    override val attributes: Attributes = Attributes()
) : Exchange

/**
 * Create an Exchange from a Vert.x HttpServerRequest.
 * Reads the request body and creates the appropriate Request/Response wrappers.
 */
suspend fun createVertxExchange(vertxRequest: HttpServerRequest): VertxExchange {
    val bodyDeferred = CompletableDeferred<ByteArray>()

    // Read body using Vert.x's built-in body() method with proper suspension
    val bodyBytes: ByteArray = try {
        // The body() method returns a Future<Buffer> which we await
        val buffer = vertxRequest.body().coAwait()
        buffer?.bytes ?: ByteArray(0)
    } catch (e: Exception) {
        // Log the error for debugging
        System.err.println("Error reading request body: ${e.message}")
        ByteArray(0)
    }
    
    bodyDeferred.complete(bodyBytes)

    val request = VertxRequest(vertxRequest, bodyDeferred)
    val response = VertxResponse(vertxRequest.response())

    return VertxExchange(request, response)
}
