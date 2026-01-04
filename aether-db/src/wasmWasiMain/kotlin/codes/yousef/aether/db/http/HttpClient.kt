package codes.yousef.aether.db.http

/**
 * WasmWASI stub implementation of HttpClient.
 * WASI environments typically don't have HTTP client support built-in.
 * This requires a WASI-specific HTTP client implementation or external bindings.
 */
actual class HttpClient actual constructor() {

    actual suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        throw HttpException("HTTP client not supported in WASI environment. Use an external HTTP client binding.")
    }

    actual suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResponse {
        throw HttpException("HTTP client not supported in WASI environment. Use an external HTTP client binding.")
    }

    actual suspend fun patch(url: String, body: String, headers: Map<String, String>): HttpResponse {
        throw HttpException("HTTP client not supported in WASI environment. Use an external HTTP client binding.")
    }

    actual suspend fun delete(url: String, headers: Map<String, String>): HttpResponse {
        throw HttpException("HTTP client not supported in WASI environment. Use an external HTTP client binding.")
    }

    actual fun close() {
        // No-op
    }
}
