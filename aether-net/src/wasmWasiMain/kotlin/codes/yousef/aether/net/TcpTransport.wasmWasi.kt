package codes.yousef.aether.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * WasmWASI implementation of NetworkTransport.
 *
 * This is a stub implementation prepared for future integration with alternative
 * transport mechanisms. WASI environments may support different networking models
 * than traditional TCP sockets.
 *
 * This implementation is prepared for future enhancement when alternative
 * transport protocols become available in WASI environments.
 */
actual class TcpTransport actual constructor() : NetworkTransport {
    init {
        println("TcpTransport: WasmWASI implementation initialized")
        println("TcpTransport: This is a stub prepared for future alternative transports")
    }

    /**
     * Stub implementation that throws an exception.
     *
     * Will be implemented when alternative transport mechanisms are available.
     *
     * @throws UnsupportedOperationException currently not implemented
     */
    actual override suspend fun send(data: ByteArray, destination: String) {
        println("TcpTransport.send() called in WasmWASI environment")
        println("Attempted to send ${data.size} bytes to $destination")
        throw UnsupportedOperationException(
            "TcpTransport is not yet implemented for WasmWASI. " +
            "This stub is prepared for future alternative transport integration."
        )
    }

    /**
     * Stub implementation that returns an empty flow.
     *
     * Will be implemented when alternative transport mechanisms are available.
     *
     * @return An empty Flow
     */
    actual override fun listen(port: Int): Flow<Packet> {
        println("TcpTransport.listen() called in WasmWASI environment")
        println("Attempted to listen on port $port")
        println("Returning empty flow (not yet implemented)")
        return emptyFlow()
    }

    /**
     * Stub implementation that does nothing.
     *
     * No resources to clean up in this stub implementation.
     */
    actual override suspend fun close() {
        println("TcpTransport.close() called in WasmWASI environment (no-op)")
    }
}
