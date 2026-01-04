package codes.yousef.aether.core.proxy

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * A streaming HTTP client optimized for proxy operations.
 * Provides zero-copy streaming of request/response bodies.
 * 
 * This is an expect class - platform-specific implementations provide the actual HTTP transport.
 * - JVM: Uses Vert.x HttpClient with ReadStream piping
 * - WasmJS: Uses fetch() API with ReadableStream
 * - WasmWASI: Stub implementation (not supported)
 */
expect class StreamingProxyClient(config: ProxyConfig = ProxyConfig.Default) {
    /**
     * Execute a streaming proxy request.
     * 
     * @param request The proxy request to execute
     * @return A ProxyResult containing response metadata and a flow of response chunks
     */
    suspend fun execute(request: StreamingProxyRequest): ProxyResult
    
    /**
     * Close the client and release resources.
     */
    fun close()
}

/**
 * Represents a streaming proxy request.
 */
data class StreamingProxyRequest(
    /** HTTP method (GET, POST, etc.) */
    val method: String,
    
    /** Full URL to proxy to (including protocol, host, path, and query) */
    val url: String,
    
    /** Headers to send with the request */
    val headers: Map<String, String>,
    
    /** Request body as a flow of chunks for streaming, or null for bodyless requests */
    val bodyFlow: Flow<ByteArray>?,
    
    /** Known body size if available, for Content-Length header */
    val bodySize: Long?,
    
    /** Request timeout override */
    val timeout: Duration?
)

/**
 * Result of a streaming proxy operation.
 */
data class ProxyResult(
    /** HTTP status code from upstream */
    val statusCode: Int,
    
    /** Status message from upstream */
    val statusMessage: String?,
    
    /** Response headers from upstream */
    val headers: Map<String, List<String>>,
    
    /** Flow of response body chunks for streaming */
    val bodyFlow: Flow<ByteArray>,
    
    /** Content-Length if known, -1 for chunked/streaming responses */
    val contentLength: Long
) {
    /**
     * Get the Content-Type header.
     */
    val contentType: String?
        get() = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()
    
    /**
     * Check if this is a streaming response (SSE or chunked).
     */
    val isStreaming: Boolean
        get() = contentType?.contains("text/event-stream") == true ||
                contentLength < 0 ||
                headers.entries.any { 
                    it.key.equals("Transfer-Encoding", ignoreCase = true) && 
                    it.value.any { v -> v.contains("chunked", ignoreCase = true) }
                }
    
    /**
     * Check if this is a successful response (2xx).
     */
    val isSuccessful: Boolean
        get() = statusCode in 200..299
    
    /**
     * Convert to ProxyUpstreamResponse for inspection.
     */
    fun toUpstreamResponse(): ProxyUpstreamResponse = ProxyUpstreamResponse(
        statusCode = statusCode,
        headers = headers,
        contentType = contentType,
        contentLength = if (contentLength >= 0) contentLength else null
    )
}

/**
 * A chunk of data from a streaming response.
 */
sealed class ProxyChunk {
    /** A data chunk containing bytes */
    data class Data(val bytes: ByteArray) : ProxyChunk() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Data) return false
            return bytes.contentEquals(other.bytes)
        }
        
        override fun hashCode(): Int = bytes.contentHashCode()
    }
    
    /** End of stream marker */
    data object End : ProxyChunk()
    
    /** Error occurred during streaming */
    data class Error(val exception: Throwable) : ProxyChunk()
}
