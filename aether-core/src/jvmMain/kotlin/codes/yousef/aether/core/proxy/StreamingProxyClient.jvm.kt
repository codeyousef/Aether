package codes.yousef.aether.core.proxy

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.*
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import kotlin.time.Duration

/**
 * JVM implementation of StreamingProxyClient using Vert.x HttpClient.
 * Provides true streaming with backpressure support and zero-copy buffer handling.
 */
actual class StreamingProxyClient actual constructor(
    private val config: ProxyConfig
) {
    private val vertx: Vertx = Vertx.vertx()
    
    private val httpClient: HttpClient = vertx.createHttpClient(
        HttpClientOptions().apply {
            connectTimeout = config.connectTimeout.inWholeMilliseconds.toInt()
            idleTimeout = config.idleTimeout.inWholeSeconds.toInt()
            maxPoolSize = 100
            maxWaitQueueSize = 1000
            isKeepAlive = true
            isPipelining = false  // Safer for proxying
            isDecompressionSupported = false  // Don't modify encoding for proxy
            isTrustAll = false
            // Note: Vert.x HttpClient doesn't follow redirects by default
        }
    )
    
    private val httpsClient: HttpClient = vertx.createHttpClient(
        HttpClientOptions().apply {
            connectTimeout = config.connectTimeout.inWholeMilliseconds.toInt()
            idleTimeout = config.idleTimeout.inWholeSeconds.toInt()
            maxPoolSize = 100
            maxWaitQueueSize = 1000
            isKeepAlive = true
            isPipelining = false
            isDecompressionSupported = false
            isTrustAll = false
            isSsl = true
            // Note: Vert.x HttpClient doesn't follow redirects by default
        }
    )
    
    /**
     * Execute a streaming proxy request.
     */
    actual suspend fun execute(request: StreamingProxyRequest): ProxyResult {
        val uri = try {
            URI.create(request.url)
        } catch (e: Exception) {
            throw ProxyConnectionException(request.url, "Invalid URL: ${request.url}", e)
        }
        
        val client = if (uri.scheme == "https") httpsClient else httpClient
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
        val path = buildString {
            append(uri.rawPath.ifEmpty { "/" })
            if (!uri.rawQuery.isNullOrEmpty()) {
                append("?")
                append(uri.rawQuery)
            }
        }
        
        val timeout = request.timeout ?: config.requestTimeout
        
        return try {
            executeWithClient(client, request, uri.host, port, path, timeout)
        } catch (e: ConnectException) {
            throw ProxyConnectionException(request.url, "Connection refused: ${uri.host}:$port", e)
        } catch (e: SocketTimeoutException) {
            throw ProxyTimeoutException(request.url, ProxyTimeoutException.TimeoutType.CONNECT, cause = e)
        } catch (e: TimeoutException) {
            // Vert.x throws java.util.concurrent.TimeoutException on request timeout
            throw ProxyTimeoutException(request.url, ProxyTimeoutException.TimeoutType.REQUEST, cause = e)
        } catch (e: SSLException) {
            throw ProxySslException(request.url, cause = e)
        } catch (e: ProxyException) {
            throw e
        } catch (e: CancellationException) {
            throw ProxyCancelledException(request.url, cause = e)
        } catch (e: Exception) {
            // Check if the exception message indicates a timeout (Vert.x NoStackTraceTimeoutException)
            if (e::class.simpleName?.contains("Timeout", ignoreCase = true) == true ||
                e.message?.contains("timeout", ignoreCase = true) == true) {
                throw ProxyTimeoutException(request.url, ProxyTimeoutException.TimeoutType.REQUEST, cause = e)
            }
            throw ProxyConnectionException(request.url, "Unexpected error (${e::class.simpleName}): ${e.message}", e)
        }
    }
    
    private suspend fun executeWithClient(
        client: HttpClient,
        request: StreamingProxyRequest,
        host: String,
        port: Int,
        path: String,
        timeout: Duration
    ): ProxyResult {
        val method = HttpMethod.valueOf(request.method.uppercase())
        
        // Create the request
        val clientRequest: HttpClientRequest = client.request(method, port, host, path).coAwait()
        
        // Set timeout
        @Suppress("DEPRECATION")
        clientRequest.setTimeout(timeout.inWholeMilliseconds)
        
        // Copy headers
        request.headers.forEach { (name, value) ->
            clientRequest.putHeader(name, value)
        }
        
        // Create channel to receive response body BEFORE we send the request
        val channel = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        var responseRef: HttpClientResponse? = null
        var requestError: Throwable? = null
        val responseDeferred = kotlinx.coroutines.CompletableDeferred<HttpClientResponse>()

        // Set up response handler on the request BEFORE sending
        clientRequest.response { ar ->
            if (ar.succeeded()) {
                val resp = ar.result()
                responseRef = resp
                responseDeferred.complete(resp)

                // Set up body handlers immediately
                resp.handler { buffer ->
                    channel.trySend(buffer.bytes)
                }
                
                resp.exceptionHandler { throwable ->
                    channel.close(throwable)
                }
                
                resp.endHandler {
                    channel.close()
                }
            } else {
                requestError = ar.cause()
                responseDeferred.completeExceptionally(ar.cause())
                channel.close(ar.cause())
            }
        }
        
        // Now send the request
        if (request.bodyFlow != null) {
            // Set content-length if known, otherwise use chunked
            if (request.bodySize != null && request.bodySize >= 0) {
                clientRequest.putHeader("Content-Length", request.bodySize.toString())
            } else {
                clientRequest.isChunked = true
            }
            
            // Write body chunks
            request.bodyFlow.collect { chunk ->
                clientRequest.write(Buffer.buffer(chunk)).coAwait()
            }
            
            // End request
            clientRequest.end().coAwait()
        } else {
            // No body - just end the request
            clientRequest.end().coAwait()
        }
        
        // Wait for response headers
        try {
            responseDeferred.await()
        } catch (_: Exception) {
            // Error will be handled by requestError check below
        }

        // Check if there was an error
        requestError?.let { error ->
            // Check if this is a timeout exception
            val errorName = error::class.simpleName ?: ""
            val errorMessage = error.message ?: ""
            if (errorName.contains("Timeout", ignoreCase = true) ||
                errorMessage.contains("timeout", ignoreCase = true)) {
                throw ProxyTimeoutException(request.url, ProxyTimeoutException.TimeoutType.REQUEST, cause = error)
            }
            // Re-throw as connection exception
            throw ProxyConnectionException(request.url, "Connection error: ${error.message}", error)
        }
        
        val response = responseRef ?: throw ProxyConnectionException(request.url, "No response received")
        
        // Extract headers
        val responseHeaders = mutableMapOf<String, MutableList<String>>()
        response.headers().forEach { entry ->
            responseHeaders.getOrPut(entry.key) { mutableListOf() }.add(entry.value)
        }
        
        // Determine content length
        val contentLength = response.getHeader("Content-Length")?.toLongOrNull() ?: -1L
        
        // Create flow from channel
        val bodyFlow: Flow<ByteArray> = kotlinx.coroutines.flow.flow {
            for (chunk in channel) {
                emit(chunk)
            }
        }
        
        return ProxyResult(
            statusCode = response.statusCode(),
            statusMessage = response.statusMessage(),
            headers = responseHeaders,
            bodyFlow = bodyFlow,
            contentLength = contentLength
        )
    }
    
    /**
     * Close the client and release resources.
     */
    actual fun close() {
        httpClient.close()
        httpsClient.close()
        vertx.close()
    }
}
