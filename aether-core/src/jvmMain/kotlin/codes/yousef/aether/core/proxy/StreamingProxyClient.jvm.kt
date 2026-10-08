package codes.yousef.aether.core.proxy

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.*
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * JVM implementation of StreamingProxyClient using Vert.x HttpClient.
 * Provides true streaming with backpressure support and zero-copy buffer handling.
 */
actual class StreamingProxyClient actual constructor(
    private val config: ProxyConfig
) {
    private val vertx: Vertx = Vertx.vertx()
    private val closed = AtomicBoolean(false)
    
    private val httpClient: HttpClient = vertx.createHttpClient(
        HttpClientOptions().apply {
            protocolVersion = HttpVersion.HTTP_1_1
            isHttp2ClearTextUpgrade = false
            connectTimeout = config.connectTimeout.inWholeMilliseconds.toInt()
            idleTimeout = config.idleTimeout.inWholeSeconds.toInt()
            maxChunkSize = config.streamBufferSize
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
            protocolVersion = HttpVersion.HTTP_1_1
            connectTimeout = config.connectTimeout.inWholeMilliseconds.toInt()
            idleTimeout = config.idleTimeout.inWholeSeconds.toInt()
            maxChunkSize = config.streamBufferSize
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
            throw e
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
        
        // One HTTP chunk per demand unit keeps retention proportional to the configured window.
        val channel = Channel<ByteArray>(config.streamBufferChunks)
        var responseRef: HttpClientResponse? = null
        var requestError: Throwable? = null
        val responseDeferred = CompletableDeferred<HttpClientResponse>()
        val queuedResponseBytes = AtomicLong(0)
        val responseHighWaterBytes = AtomicLong(0)
        val metrics = object : StreamingBufferMetrics {
            override val highWaterBytes: Long
                get() = responseHighWaterBytes.get()
        }

        // Set up response handler on the request BEFORE sending
        clientRequest.response { ar ->
            if (ar.succeeded()) {
                val resp = ar.result()
                responseRef = resp
                resp.pause()
                resp.handler { buffer ->
                    val bytes = buffer.bytes
                    val queued = queuedResponseBytes.addAndGet(bytes.size.toLong())
                    if (channel.trySend(bytes).isFailure) {
                        queuedResponseBytes.addAndGet(-bytes.size.toLong())
                        clientRequest.reset(0, IllegalStateException("Bounded response channel overflow"))
                    } else {
                        responseHighWaterBytes.accumulateAndGet(queued, ::maxOf)
                    }
                }
                resp.exceptionHandler { throwable ->
                    channel.close(throwable)
                }
                resp.endHandler {
                    channel.close()
                }
                responseDeferred.complete(resp)
                resp.fetch(config.streamBufferChunks.toLong())
            } else {
                requestError = ar.cause()
                responseDeferred.completeExceptionally(ar.cause())
                channel.close(ar.cause())
            }
        }
        
        // Now send the request. Every accepted chunk is bounded before entering Vert.x.
        try {
            if (request.bodyFlow != null) {
                if (request.bodySize != null) {
                    if (request.bodySize < 0 || request.bodySize > config.maxRequestBodySize) {
                        throw ProxyPayloadTooLargeException(config.maxRequestBodySize, request.bodySize)
                    }
                    clientRequest.putHeader("Content-Length", request.bodySize.toString())
                } else {
                    clientRequest.isChunked = true
                }

                var sentBytes = 0L
                request.bodyFlow.collect { chunk ->
                    if (chunk.size > config.streamBufferSize) {
                        throw ProxyPayloadTooLargeException(config.streamBufferSize.toLong(), chunk.size.toLong())
                    }
                    if (sentBytes > config.maxRequestBodySize - chunk.size) {
                        throw ProxyPayloadTooLargeException(
                            config.maxRequestBodySize,
                            sentBytes + chunk.size
                        )
                    }
                    sentBytes += chunk.size
                    if (clientRequest.writeQueueFull()) awaitDrain(clientRequest)
                    clientRequest.write(Buffer.buffer(chunk)).coAwait()
                }
                clientRequest.end().coAwait()
            } else {
                clientRequest.end().coAwait()
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                clientRequest.connection().close().coAwait()
            }
            throw failure
        }
        
        // Wait for response headers
        try {
            responseDeferred.await()
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                clientRequest.connection().close().coAwait()
            }
            throw cancellation
        } catch (_: Exception) {
            // Transport error classification uses the callback's retained failure below.
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
        
        val bodyFlow: Flow<ByteArray> = flow {
            var fullyConsumed = false
            try {
                for (chunk in channel) {
                    queuedResponseBytes.addAndGet(-chunk.size.toLong())
                    emit(chunk)
                    response.fetch(1)
                }
                fullyConsumed = true
            } finally {
                if (!fullyConsumed) withContext(NonCancellable) {
                    response.request().connection().close().coAwait()
                }
            }
        }
        
        return ProxyResult(
            statusCode = response.statusCode(),
            statusMessage = response.statusMessage(),
            headers = responseHeaders,
            bodyFlow = bodyFlow,
            contentLength = contentLength,
            metrics = metrics
        )
    }
    
    /**
     * Close the client and release resources.
     */
    actual fun close() {
        if (!closed.compareAndSet(false, true)) return
        httpClient.close()
        httpsClient.close()
        vertx.close()
    }

    private suspend fun awaitDrain(request: HttpClientRequest) {
        val drained = CompletableDeferred<Unit>()
        request.drainHandler { drained.complete(Unit) }
        if (request.writeQueueFull()) drained.await()
    }
}
