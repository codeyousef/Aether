# WebSockets API

Aether routes WebSockets through the same owned server scope as HTTP without allowing a failed
socket to cancel sibling requests.

## Route handlers

```kotlin
val router = router {
    ws("/ws/account/:account") {
        onConnect { session ->
            val account = session.pathParams.getValue("account")
            session.sendText("connected:$account")
        }
        onText { session, text -> session.sendText("echo:$text") }
        onClose { session, _, _ -> subscriptionFor(session)?.close() }
        onError { session, _ -> subscriptionFor(session)?.close() }
    }
}
```

`WebSocketSession` exposes `id`, `path`, `pathParams`, `queryParameters`, handshake `headers`,
application `attributes`, `isOpen`, typed send methods, `close`, and a finite `incoming()` flow.

## Bounded and authorized upgrades

```kotlin
val webSocketPolicy = WebSocketConfig(
    maxFrameSize = 4 * 1024,
    maxMessageSize = 4 * 1024,
    maxPendingMessages = 32,
    allowedOrigins = setOf("https://app.example"),
    authorizer = WebSocketUpgradeAuthorizer { request ->
        val identity = authenticateSessionCookie(request.headers)
            ?: return@WebSocketUpgradeAuthorizer WebSocketUpgradeAuthorization.Deny()
        val account = request.path.substringAfterLast('/')
        if (!identity.canSubscribe(account)) {
            WebSocketUpgradeAuthorization.Deny()
        } else {
            WebSocketUpgradeAuthorization.Allow(mapOf("account" to account))
        }
    }
)

val server = AetherServer.create(
    AetherServerConfig(webSocket = webSocketPolicy),
    router,
    pipeline
)
```

Origin and application authorization run on `ServerWebSocketHandshake` before acceptance. A
configured origin allowlist rejects missing and nonmatching origins. Query-string bearer
credentials are rejected; authenticate from the handshake's cookie or other approved header.
Authorization may install trusted session attributes for the route handler.

Inbound text size is measured as UTF-8 bytes. Binary and pong payloads use their decoded byte
length. Oversized messages close with `1009`; a full pending-message queue closes with `1013`.
Bounds are validated at startup and are also applied to Vert.x frame/message decoding.

Application authorization can change after connection. Recheck it before every privileged
notification and close with `1008` after revocation. WebSocket hints are not durable state:
clients must recover through a persisted cursor after reconnect.
