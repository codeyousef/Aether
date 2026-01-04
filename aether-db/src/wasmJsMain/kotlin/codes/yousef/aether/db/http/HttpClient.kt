package codes.yousef.aether.db.http

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WasmJS implementation of HttpClient using JavaScript fetch API.
 */
actual class HttpClient actual constructor() {

    actual suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        return performFetch(url, "GET", null, headers)
    }

    actual suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
        return performFetch(url, "POST", body, headers)
    }

    actual suspend fun patch(url: String, body: String, headers: Map<String, String>): HttpResponse {
        return performFetch(url, "PATCH", body, headers)
    }

    actual suspend fun delete(url: String, headers: Map<String, String>): HttpResponse {
        return performFetch(url, "DELETE", null, headers)
    }

    private suspend fun performFetch(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>
    ): HttpResponse {
        return suspendCancellableCoroutine { continuation ->
            try {
                val headersJson = headers.entries.joinToString(",") { (k, v) ->
                    "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
                }
                val headersObj = "{$headersJson}"
                
                val callback: (Int, String, String?) -> Unit = { statusCode, responseBody, errorMessage ->
                    if (errorMessage != null) {
                        continuation.resumeWithException(
                            HttpException("HTTP request failed: $errorMessage", statusCode)
                        )
                    } else {
                        continuation.resume(
                            HttpResponse(
                                statusCode = statusCode,
                                body = responseBody,
                                headers = emptyMap()
                            )
                        )
                    }
                }

                jsFetch(url, method, body, headersObj, callback)
            } catch (e: Exception) {
                continuation.resumeWithException(
                    HttpException("Failed to perform HTTP request: ${e.message}", cause = e)
                )
            }
        }
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    actual fun close() {
        // No resources to close for fetch-based client
    }
}

/**
 * JavaScript fetch implementation exposed to Kotlin/Wasm.
 */
@JsFun("""
(url, method, body, headersJson, callback) => {
    const headers = JSON.parse(headersJson);
    if (body !== null && !headers['Content-Type']) {
        headers['Content-Type'] = 'application/json';
    }
    
    const options = {
        method: method,
        headers: headers
    };
    
    if (body !== null) {
        options.body = body;
    }
    
    fetch(url, options)
        .then(response => {
            return response.text().then(text => {
                callback(response.status, text, null);
            });
        })
        .catch(error => {
            callback(0, '', error.toString());
        });
}
""")
private external fun jsFetch(
    url: String,
    method: String,
    body: String?,
    headersJson: String,
    callback: (Int, String, String?) -> Unit
)
