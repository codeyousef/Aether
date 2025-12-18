package codes.yousef.aether.net

import kotlinx.coroutines.flow.Flow

/**
 * UDP transport for datagram-based communication.
 */
expect class UdpTransport() : NetworkTransport {
    override suspend fun send(data: ByteArray, destination: String)
    override fun listen(port: Int): Flow<Packet>
    override suspend fun close()
}

/**
 * HTTP transport for request-response based communication.
 * Useful for environments where direct TCP/UDP is not available.
 */
interface HttpTransport : NetworkTransport {
    /**
     * Send an HTTP request.
     */
    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null
    ): HttpResponse
}

/**
 * HTTP response data.
 */
data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponse) return false
        return statusCode == other.statusCode &&
            headers == other.headers &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

/**
 * Transport factory for creating transport instances.
 */
object TransportFactory {
    /**
     * Create a transport by protocol name.
     */
    fun create(protocol: String): NetworkTransport {
        return when (protocol.lowercase()) {
            "tcp" -> TcpTransport()
            "udp" -> UdpTransport()
            else -> throw IllegalArgumentException("Unsupported protocol: $protocol")
        }
    }

    /**
     * Available transport protocols.
     */
    val availableProtocols: List<String> = listOf("tcp", "udp")
}

/**
 * Configuration for network transports.
 */
data class TransportConfig(
    /**
     * Connection timeout in milliseconds.
     */
    val connectTimeoutMs: Long = 30000,

    /**
     * Read timeout in milliseconds.
     */
    val readTimeoutMs: Long = 30000,

    /**
     * Write timeout in milliseconds.
     */
    val writeTimeoutMs: Long = 30000,

    /**
     * Buffer size for reading data.
     */
    val bufferSize: Int = 8192,

    /**
     * Whether to enable TCP keep-alive.
     */
    val keepAlive: Boolean = true,

    /**
     * Whether to disable Nagle's algorithm (TCP_NODELAY).
     */
    val tcpNoDelay: Boolean = true
)

/**
 * Reliable transport wrapper that adds retry and reconnection logic.
 */
class ReliableTransport(
    private val delegate: NetworkTransport,
    private val config: ReliableConfig = ReliableConfig()
) : NetworkTransport {

    override suspend fun send(data: ByteArray, destination: String) {
        var lastException: Exception? = null

        repeat(config.maxRetries + 1) { attempt ->
            try {
                delegate.send(data, destination)
                return
            } catch (e: Exception) {
                lastException = e
                if (attempt < config.maxRetries) {
                    kotlinx.coroutines.delay(config.retryDelayMs * (attempt + 1))
                }
            }
        }

        throw lastException ?: Exception("Send failed after ${config.maxRetries} retries")
    }

    override fun listen(port: Int): Flow<Packet> = delegate.listen(port)

    override suspend fun close() = delegate.close()
}

/**
 * Configuration for reliable transport.
 */
data class ReliableConfig(
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 1000
)

/**
 * Multiplexing transport that can route to multiple destinations.
 */
class MultiplexTransport(
    private val transports: Map<String, NetworkTransport>
) : NetworkTransport {

    override suspend fun send(data: ByteArray, destination: String) {
        val (protocol, address) = parseDestination(destination)
        val transport = transports[protocol]
            ?: throw IllegalArgumentException("No transport for protocol: $protocol")
        transport.send(data, address)
    }

    override fun listen(port: Int): Flow<Packet> {
        // Return flow from primary transport
        return transports.values.firstOrNull()?.listen(port)
            ?: throw IllegalStateException("No transports configured")
    }

    override suspend fun close() {
        transports.values.forEach { it.close() }
    }

    private fun parseDestination(destination: String): Pair<String, String> {
        val colonIndex = destination.indexOf("://")
        return if (colonIndex > 0) {
            val protocol = destination.substring(0, colonIndex)
            val address = destination.substring(colonIndex + 3)
            protocol to address
        } else {
            "tcp" to destination // Default to TCP
        }
    }

    companion object {
        fun builder() = MultiplexTransportBuilder()
    }
}

/**
 * Builder for MultiplexTransport.
 */
class MultiplexTransportBuilder {
    private val transports = mutableMapOf<String, NetworkTransport>()

    fun tcp(transport: NetworkTransport = TcpTransport()): MultiplexTransportBuilder {
        transports["tcp"] = transport
        return this
    }

    fun udp(transport: NetworkTransport = UdpTransport()): MultiplexTransportBuilder {
        transports["udp"] = transport
        return this
    }

    fun protocol(name: String, transport: NetworkTransport): MultiplexTransportBuilder {
        transports[name] = transport
        return this
    }

    fun build(): MultiplexTransport = MultiplexTransport(transports.toMap())
}
