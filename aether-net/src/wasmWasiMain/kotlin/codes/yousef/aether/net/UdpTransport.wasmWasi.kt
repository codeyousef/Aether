package codes.yousef.aether.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * WasmWASI stub implementation of UDP transport.
 * UDP requires specific WASI capabilities that may not be available.
 */
actual class UdpTransport actual constructor() : NetworkTransport {
    
    actual override suspend fun send(data: ByteArray, destination: String) {
        println("UdpTransport: UDP not yet implemented for WASI")
        throw UnsupportedOperationException("UDP transport not available in WASI")
    }

    actual override fun listen(port: Int): Flow<Packet> {
        println("UdpTransport: UDP not yet implemented for WASI")
        return emptyFlow()
    }

    actual override suspend fun close() {
        // No-op
    }
}
