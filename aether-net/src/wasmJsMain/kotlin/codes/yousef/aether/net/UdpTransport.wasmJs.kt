package codes.yousef.aether.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * WasmJS stub implementation of UDP transport.
 * UDP is not directly available in browser environments.
 */
actual class UdpTransport actual constructor() : NetworkTransport {
    
    actual override suspend fun send(data: ByteArray, destination: String) {
        console.log("UdpTransport: UDP not supported in browser environment")
        throw UnsupportedOperationException("UDP transport not available in browser")
    }

    actual override fun listen(port: Int): Flow<Packet> {
        console.log("UdpTransport: UDP not supported in browser environment")
        return emptyFlow()
    }

    actual override suspend fun close() {
        // No-op
    }
}

private external object console {
    fun log(message: String)
}
