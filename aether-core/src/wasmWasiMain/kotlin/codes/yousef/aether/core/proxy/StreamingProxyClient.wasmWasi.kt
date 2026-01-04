package codes.yousef.aether.core.proxy

import kotlinx.coroutines.flow.Flow

/**
 * WasmWASI implementation of StreamingProxyClient.
 * 
 * WASI does not currently support outbound HTTP requests in most runtimes.
 * This stub throws UnsupportedOperationException.
 */
actual class StreamingProxyClient actual constructor(
    private val config: ProxyConfig
) {
    /**
     * Execute a streaming proxy request.
     * Not supported on WASI.
     */
    actual suspend fun execute(request: StreamingProxyRequest): ProxyResult {
        throw UnsupportedOperationException(
            "Streaming proxy is not supported on WasmWASI. " +
            "WASI does not provide outbound HTTP networking capabilities."
        )
    }
    
    /**
     * Close the client (no-op for WASI).
     */
    actual fun close() {
        // No resources to release in WASI
    }
}
