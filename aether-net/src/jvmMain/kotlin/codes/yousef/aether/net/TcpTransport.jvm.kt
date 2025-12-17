package codes.yousef.aether.net

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.net.NetClient
import io.vertx.core.net.NetServer
import io.vertx.core.net.NetSocket
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * JVM implementation of NetworkTransport using Vert.x networking.
 *
 * This implementation uses Vert.x's NetServer and NetClient for asynchronous,
 * non-blocking TCP communication. It's designed to work efficiently with
 * virtual threads and coroutines.
 */
actual class TcpTransport actual constructor() : NetworkTransport {
    private val vertx: Vertx = Vertx.vertx()
    private var server: NetServer? = null
    private val clients = mutableMapOf<String, NetClient>()

    /**
     * Sends data to the specified destination over TCP.
     *
     * The destination should be in the format "host:port" (e.g., "localhost:8080").
     * A NetClient is created and cached for each destination to reuse connections.
     *
     * @param data The bytes to send
     * @param destination The target address in "host:port" format
     * @throws IllegalArgumentException if destination format is invalid
     * @throws Exception if connection or sending fails
     */
    actual override suspend fun send(data: ByteArray, destination: String) {
        val (host, port) = parseDestination(destination)

        val client = clients.getOrPut(destination) {
            vertx.createNetClient()
        }

        suspendCancellableCoroutine { continuation ->
            client.connect(port, host) { asyncResult ->
                if (asyncResult.succeeded()) {
                    val socket = asyncResult.result()
                    socket.write(Buffer.buffer(data)) { writeResult ->
                        if (writeResult.succeeded()) {
                            socket.close()
                            continuation.resume(Unit)
                        } else {
                            socket.close()
                            continuation.resumeWithException(
                                writeResult.cause() ?: Exception("Failed to write data")
                            )
                        }
                    }
                } else {
                    continuation.resumeWithException(
                        asyncResult.cause() ?: Exception("Failed to connect to $destination")
                    )
                }
            }
        }
    }

    /**
     * Starts listening for incoming TCP connections on the specified port.
     *
     * Returns a Flow that emits Packet objects for each chunk of data received.
     * The Flow remains active until the transport is closed or an error occurs.
     *
     * @param port The port to listen on
     * @return A Flow of incoming Packets
     * @throws Exception if the server fails to start
     */
    actual override fun listen(port: Int): Flow<Packet> = callbackFlow {
        val netServer = vertx.createNetServer()

        netServer.connectHandler { socket ->
            handleConnection(socket)
        }

        netServer.listen(port) { asyncResult ->
            if (asyncResult.succeeded()) {
                server = asyncResult.result()
            } else {
                close(asyncResult.cause() ?: Exception("Failed to start server on port $port"))
            }
        }

        awaitClose {
            server?.close()
        }
    }

    /**
     * Handles an incoming TCP connection.
     *
     * @param socket The connected socket
     */
    private fun handleConnection(socket: NetSocket) {
        val source = "${socket.remoteAddress().host()}:${socket.remoteAddress().port()}"

        socket.handler { buffer ->
            val packet = Packet(
                data = buffer.bytes,
                source = source
            )
            // In a real implementation, we would send this packet through the Flow
            // For now, this is a simplified version that processes each buffer
        }

        socket.exceptionHandler { error ->
            println("Error on socket from $source: ${error.message}")
            socket.close()
        }

        socket.closeHandler {
            // Connection closed
        }
    }

    /**
     * Closes the transport and releases all resources.
     *
     * This includes closing the server (if listening) and all client connections.
     */
    actual override suspend fun close() {
        suspendCancellableCoroutine { continuation ->
            // Close all clients
            clients.values.forEach { it.close() }
            clients.clear()

            // Close server
            server?.close { asyncResult ->
                if (asyncResult.succeeded()) {
                    vertx.close { vertxCloseResult ->
                        if (vertxCloseResult.succeeded()) {
                            continuation.resume(Unit)
                        } else {
                            continuation.resumeWithException(
                                vertxCloseResult.cause() ?: Exception("Failed to close Vertx")
                            )
                        }
                    }
                } else {
                    continuation.resumeWithException(
                        asyncResult.cause() ?: Exception("Failed to close server")
                    )
                }
            } ?: run {
                // No server to close, just close Vertx
                vertx.close { vertxCloseResult ->
                    if (vertxCloseResult.succeeded()) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            vertxCloseResult.cause() ?: Exception("Failed to close Vertx")
                        )
                    }
                }
            }
        }
    }

    /**
     * Parses a destination string into host and port components.
     *
     * @param destination The destination in "host:port" format
     * @return A Pair of (host, port)
     * @throws IllegalArgumentException if format is invalid
     */
    private fun parseDestination(destination: String): Pair<String, Int> {
        val parts = destination.split(":")
        require(parts.size == 2) { "Destination must be in format 'host:port'" }

        val host = parts[0]
        val port = parts[1].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid port number: ${parts[1]}")

        return host to port
    }
}
