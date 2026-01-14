package codes.yousef.aether.grpc.streaming

import kotlinx.coroutines.flow.Flow

/**
 * Handler for server streaming RPCs.
 *
 * Server streaming: Client sends one request, server sends a stream of responses.
 *
 * Example:
 * ```kotlin
 * val handler = ServerStreamingHandler<ListUsersRequest, User> { request ->
 *     flow {
 *         database.streamUsers(request.filter).collect { user ->
 *             emit(user)
 *         }
 *     }
 * }
 * ```
 *
 * @param TRequest The request message type
 * @param TResponse The response message type
 * @param handler Function that takes a request and returns a Flow of responses
 */
class ServerStreamingHandler<TRequest, TResponse>(
    private val handler: suspend (TRequest) -> Flow<TResponse>
) {
    /**
     * Handle a server streaming request.
     *
     * @param request The client's request
     * @return A Flow of response messages
     */
    suspend fun handle(request: TRequest): Flow<TResponse> {
        return handler(request)
    }
}

/**
 * Handler for client streaming RPCs.
 *
 * Client streaming: Client sends a stream of requests, server sends one response.
 *
 * Example:
 * ```kotlin
 * val handler = ClientStreamingHandler<UploadChunk, UploadResult> { chunks ->
 *     var totalBytes = 0L
 *     chunks.collect { chunk ->
 *         totalBytes += chunk.data.size
 *         storage.appendChunk(chunk)
 *     }
 *     UploadResult(totalBytes)
 * }
 * ```
 *
 * @param TRequest The request message type
 * @param TResponse The response message type
 * @param handler Function that takes a Flow of requests and returns a response
 */
class ClientStreamingHandler<TRequest, TResponse>(
    private val handler: suspend (Flow<TRequest>) -> TResponse
) {
    /**
     * Handle a client streaming request.
     *
     * @param requests A Flow of request messages from the client
     * @return The response message
     */
    suspend fun handle(requests: Flow<TRequest>): TResponse {
        return handler(requests)
    }
}

/**
 * Handler for bidirectional streaming RPCs.
 *
 * Bidirectional streaming: Both client and server send streams of messages.
 *
 * Example:
 * ```kotlin
 * val handler = BiDirectionalStreamingHandler<ChatMessage, ChatMessage> { incoming ->
 *     incoming.map { message ->
 *         // Echo messages back with timestamp
 *         message.copy(timestamp = Clock.System.now())
 *     }
 * }
 * ```
 *
 * @param TRequest The request message type
 * @param TResponse The response message type
 * @param handler Function that takes a Flow of requests and returns a Flow of responses
 */
class BiDirectionalStreamingHandler<TRequest, TResponse>(
    private val handler: suspend (Flow<TRequest>) -> Flow<TResponse>
) {
    /**
     * Handle a bidirectional streaming request.
     *
     * @param requests A Flow of request messages from the client
     * @return A Flow of response messages
     */
    suspend fun handle(requests: Flow<TRequest>): Flow<TResponse> {
        return handler(requests)
    }
}

/**
 * Creates a server streaming handler.
 *
 * @param handler Function that takes a request and returns a Flow of responses
 * @return A ServerStreamingHandler
 */
fun <TRequest, TResponse> serverStreamingHandler(
    handler: suspend (TRequest) -> Flow<TResponse>
): ServerStreamingHandler<TRequest, TResponse> {
    return ServerStreamingHandler(handler)
}

/**
 * Creates a client streaming handler.
 *
 * @param handler Function that takes a Flow of requests and returns a response
 * @return A ClientStreamingHandler
 */
fun <TRequest, TResponse> clientStreamingHandler(
    handler: suspend (Flow<TRequest>) -> TResponse
): ClientStreamingHandler<TRequest, TResponse> {
    return ClientStreamingHandler(handler)
}

/**
 * Creates a bidirectional streaming handler.
 *
 * @param handler Function that takes a Flow of requests and returns a Flow of responses
 * @return A BiDirectionalStreamingHandler
 */
fun <TRequest, TResponse> bidiStreamingHandler(
    handler: suspend (Flow<TRequest>) -> Flow<TResponse>
): BiDirectionalStreamingHandler<TRequest, TResponse> {
    return BiDirectionalStreamingHandler(handler)
}
