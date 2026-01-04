package codes.yousef.aether.db.http

import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse as JdkHttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JVM implementation of HttpClient using Java 11+ HttpClient with Virtual Threads support.
 */
actual class HttpClient actual constructor() {
    private val client = JdkHttpClient.newBuilder()
        .version(JdkHttpClient.Version.HTTP_2)
        .followRedirects(JdkHttpClient.Redirect.NORMAL)
        .build()

    actual suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        return withContext(Dispatchers.IO) {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()

            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            executeRequest(requestBuilder.build())
        }
    }

    actual suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
        return withContext(Dispatchers.IO) {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")

            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            executeRequest(requestBuilder.build())
        }
    }

    actual suspend fun patch(url: String, body: String, headers: Map<String, String>): HttpResponse {
        return withContext(Dispatchers.IO) {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")

            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            executeRequest(requestBuilder.build())
        }
    }

    actual suspend fun delete(url: String, headers: Map<String, String>): HttpResponse {
        return withContext(Dispatchers.IO) {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .DELETE()

            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            executeRequest(requestBuilder.build())
        }
    }

    private fun executeRequest(request: HttpRequest): HttpResponse {
        try {
            val response = client.send(request, JdkHttpResponse.BodyHandlers.ofString())
            val responseHeaders = response.headers().map().mapValues { it.value.firstOrNull() ?: "" }
            return HttpResponse(
                statusCode = response.statusCode(),
                body = response.body() ?: "",
                headers = responseHeaders
            )
        } catch (e: Exception) {
            throw HttpException("HTTP request failed: ${e.message}", cause = e)
        }
    }

    actual fun close() {
        // Java HttpClient doesn't require explicit close in most cases
    }
}
