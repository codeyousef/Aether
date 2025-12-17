package codes.yousef.aether.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * WasmJS implementation of NetworkTransport.
 *
 * This is a stub implementation for browser environments where raw TCP sockets
 * are not available. Browser-based applications should use HTTP/WebSocket APIs
 * or other browser-compatible protocols instead.
 *
 * All operations log a warning and either throw an exception or return empty data.
 */
actual class TcpTransport actual constructor() : NetworkTransport {
    init {
        println("TcpTransport: Raw TCP sockets are not supported in browser environments")
        println("TcpTransport: Consider using HTTP, WebSocket, or WebRTC for network communication")
    }

    /**
     * Stub implementation that throws an exception.
     *
     * Raw TCP sockets are not available in browser environments.
     *
     * @throws UnsupportedOperationException always
     */
    actual override suspend fun send(data: ByteArray, destination: String) {
        println("TcpTransport.send() is not supported in browser environments")
        println("Attempted to send ${data.size} bytes to $destination")
        throw UnsupportedOperationException(
            "Raw TCP sockets are not available in browser environments. Use HTTP, WebSocket, or WebRTC instead."
        )
    }

    /**
     * Stub implementation that returns an empty flow.
     *
     * Raw TCP sockets are not available in browser environments.
     *
     * @return An empty Flow
     */
    actual override fun listen(port: Int): Flow<Packet> {
        println("TcpTransport.listen() is not supported in browser environments")
        println("Attempted to listen on port $port")
        println("TcpTransport: Returning empty flow")
        return emptyFlow()
    }

    /**
     * Stub implementation that does nothing.
     *
     * No resources to clean up in this stub implementation.
     */
    actual override suspend fun close() {
        println("TcpTransport.close() called (no-op in browser environment)")
    }
}
