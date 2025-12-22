# Network API

The `aether-net` module provides an abstraction layer over network transports. This allows Aether applications to run on different platforms (JVM, Wasm) and potentially use different underlying protocols (TCP, UDP, Mixnets) without changing application logic.

## NetworkTransport

The `NetworkTransport` interface defines the standard operations for sending and receiving data.

```kotlin
interface NetworkTransport {
    suspend fun send(data: ByteArray, destination: String)
    fun listen(port: Int): Flow<Packet>
    suspend fun close()
}
```

### Methods

*   `send(data: ByteArray, destination: String)`: Sends a byte array to a specific destination.
*   `listen(port: Int): Flow<Packet>`: Starts listening on a port and returns a Kotlin `Flow` of incoming `Packet`s.
*   `close()`: Closes the transport connection.

## Packet

Represents a unit of data received from the network.

```kotlin
data class Packet(
    val data: ByteArray,
    val source: String
)
```

## Implementations

### `TcpTransport`

A platform-specific implementation using the `expect`/`actual` pattern.

*   **JVM**: Implemented using Vert.x `NetServer` and `NetClient` for high-performance, non-blocking I/O.
*   **Wasm**: Provides stub implementations (or WebSocket-based bridges) suitable for browser or WASI environments.
