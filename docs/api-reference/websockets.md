# WebSockets API

Aether supports WebSocket connections for real-time bidirectional communication.

## Defining WebSocket Routes

Use the `ws` method in the router DSL.

```kotlin
router {
    ws("/chat") { session ->
        // Handle new connection
        println("New connection: ${session.id}")

        // Receive messages
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> {
                    val text = frame.readText()
                    // Echo back
                    session.send(Frame.Text("Echo: $text"))
                }
                is Frame.Binary -> {
                    // Handle binary
                }
                is Frame.Close -> {
                    // Handle close
                }
            }
        }
    }
}
```

## WebSocketSession

The `WebSocketSession` represents the connection.

### Properties

*   `id`: Unique identifier for the session.
*   `incoming`: A `ReceiveChannel<Frame>` of incoming frames.
*   `outgoing`: A `SendChannel<Frame>` for outgoing frames.

### Methods

*   `send(frame: Frame)`: Sends a frame.
*   `close(reason: CloseReason)`: Closes the connection.

## Frame Types

*   `Frame.Text`: Contains text data.
*   `Frame.Binary`: Contains byte data.
*   `Frame.Ping` / `Frame.Pong`: Keep-alive frames.
*   `Frame.Close`: Connection closure frame.
