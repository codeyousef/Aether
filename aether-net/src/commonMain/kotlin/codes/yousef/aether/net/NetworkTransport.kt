package codes.yousef.aether.net

import kotlinx.coroutines.flow.Flow

/**
 * Represents a network packet with its data and source information.
 *
 * @property data The raw bytes of the packet
 * @property source The identifier of the packet source (e.g., IP address or node identifier)
 */
data class Packet(
    val data: ByteArray,
    val source: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as Packet

        if (!data.contentEquals(other.data)) return false
        if (source != other.source) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + source.hashCode()
        return result
    }
}

/**
 * Abstract network transport interface that can be implemented with different
 * underlying protocols (TCP, UDP, or alternative transports).
 *
 * This interface provides a platform-agnostic way to send and receive data
 * over the network, allowing applications to remain transport-independent.
 */
interface NetworkTransport {
    /**
     * Sends data to a specified destination.
     *
     * @param data The bytes to send
     * @param destination The target address or identifier
     * @throws Exception if sending fails
     */
    suspend fun send(data: ByteArray, destination: String)

    /**
     * Starts listening for incoming packets on the specified port.
     *
     * @param port The port to listen on
     * @return A Flow of incoming packets
     * @throws Exception if listening fails to start
     */
    fun listen(port: Int): Flow<Packet>

    /**
     * Closes the transport and releases any associated resources.
     */
    suspend fun close()
}

/**
 * Platform-specific TCP transport implementation.
 *
 * JVM: Uses Vert.x NetServer/NetClient for asynchronous networking
 * Wasm: Provides stub implementations with appropriate logging
 */
expect class TcpTransport() : NetworkTransport {
    override suspend fun send(data: ByteArray, destination: String)
    override fun listen(port: Int): Flow<Packet>
    override suspend fun close()
}
