package codes.yousef.aether.net

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.datagram.DatagramSocket
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * JVM implementation of UDP transport using Vert.x DatagramSocket.
 */
actual class UdpTransport actual constructor() : NetworkTransport {
    private val vertx: Vertx = Vertx.vertx()
    private var socket: DatagramSocket? = null

    /**
     * Send UDP datagram to the specified destination.
     * Destination format: "host:port"
     */
    actual override suspend fun send(data: ByteArray, destination: String) {
        val (host, port) = parseDestination(destination)

        if (socket == null) {
            socket = vertx.createDatagramSocket()
        }

        suspendCancellableCoroutine { continuation ->
            socket!!.send(Buffer.buffer(data), port, host) { result ->
                if (result.succeeded()) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(
                        result.cause() ?: Exception("Failed to send UDP datagram")
                    )
                }
            }
        }
    }

    /**
     * Listen for incoming UDP datagrams on the specified port.
     */
    actual override fun listen(port: Int): Flow<Packet> = callbackFlow {
        val datagramSocket = vertx.createDatagramSocket()

        datagramSocket.handler { packet ->
            val data = packet.data().bytes
            val sender = "${packet.sender().host()}:${packet.sender().port()}"
            trySend(Packet(data, sender))
        }

        datagramSocket.exceptionHandler { error ->
            close(error)
        }

        datagramSocket.listen(port, "0.0.0.0") { result ->
            if (!result.succeeded()) {
                close(result.cause())
            }
        }

        socket = datagramSocket

        awaitClose {
            datagramSocket.close()
        }
    }

    actual override suspend fun close() {
        socket?.close()
        socket = null
    }

    private fun parseDestination(destination: String): Pair<String, Int> {
        val parts = destination.split(":")
        require(parts.size == 2) { "Invalid destination format: $destination (expected host:port)" }
        return parts[0] to parts[1].toInt()
    }
}

/**
 * JVM implementation of HTTP transport using Vert.x Web Client.
 */
class VertxHttpTransport(
    private val config: TransportConfig = TransportConfig()
) : HttpTransport {
    private val vertx: Vertx = Vertx.vertx()
    private val client = vertx.createHttpClient()

    override suspend fun send(data: ByteArray, destination: String) {
        request("POST", destination, body = data)
    }

    override fun listen(port: Int): Flow<Packet> {
        throw UnsupportedOperationException("HTTP transport does not support listen()")
    }

    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?
    ): HttpResponse = suspendCancellableCoroutine { continuation ->
        val uri = java.net.URI.create(url)
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
        val path = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")

        val options = io.vertx.core.http.RequestOptions()
            .setHost(uri.host)
            .setPort(port)
            .setSsl(uri.scheme == "https")
            .setMethod(io.vertx.core.http.HttpMethod.valueOf(method.uppercase()))
            .setURI(path)

        client.request(options) { requestResult ->
            if (requestResult.failed()) {
                continuation.resumeWithException(requestResult.cause())
                return@request
            }

            val request = requestResult.result()
            
            headers.forEach { (name, value) ->
                request.putHeader(name, value)
            }

            if (body != null) {
                request.putHeader("Content-Length", body.size.toString())
            }

            request.send(body?.let { Buffer.buffer(it) }) { responseResult ->
                if (responseResult.failed()) {
                    continuation.resumeWithException(responseResult.cause())
                    return@send
                }

                val response = responseResult.result()
                val responseHeaders = response.headers()
                    .associate { it.key to it.value }

                response.body { bodyResult ->
                    if (bodyResult.failed()) {
                        continuation.resumeWithException(bodyResult.cause())
                        return@body
                    }

                    continuation.resume(
                        HttpResponse(
                            statusCode = response.statusCode(),
                            headers = responseHeaders,
                            body = bodyResult.result()?.bytes ?: ByteArray(0)
                        )
                    )
                }
            }
        }
    }

    override suspend fun close() {
        client.close()
    }
}
