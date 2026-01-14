package codes.yousef.aether.grpc.service

import kotlinx.coroutines.flow.Flow

/**
 * Base interface for gRPC method handlers.
 */
sealed interface GrpcMethod<REQ, RESP> {
    val descriptor: GrpcMethodDescriptor
}

/**
 * Handler for unary (single request, single response) gRPC methods.
 */
class UnaryMethod<REQ, RESP>(
    override val descriptor: GrpcMethodDescriptor,
    private val handler: suspend (REQ) -> RESP
) : GrpcMethod<REQ, RESP> {

    /**
     * Invoke the handler with the request.
     */
    suspend fun invoke(request: REQ): RESP = handler(request)
}

/**
 * Handler for server streaming (single request, streaming response) gRPC methods.
 */
class ServerStreamingMethod<REQ, RESP>(
    override val descriptor: GrpcMethodDescriptor,
    private val handler: suspend (REQ) -> Flow<RESP>
) : GrpcMethod<REQ, Flow<RESP>> {

    /**
     * Invoke the handler with the request, returning a Flow of responses.
     */
    suspend fun invoke(request: REQ): Flow<RESP> = handler(request)
}

/**
 * Handler for client streaming (streaming request, single response) gRPC methods.
 */
class ClientStreamingMethod<REQ, RESP>(
    override val descriptor: GrpcMethodDescriptor,
    private val handler: suspend (Flow<REQ>) -> RESP
) : GrpcMethod<Flow<REQ>, RESP> {

    /**
     * Invoke the handler with a Flow of requests.
     */
    suspend fun invoke(requests: Flow<REQ>): RESP = handler(requests)
}

/**
 * Handler for bidirectional streaming gRPC methods.
 */
class BidiStreamingMethod<REQ, RESP>(
    override val descriptor: GrpcMethodDescriptor,
    private val handler: suspend (Flow<REQ>) -> Flow<RESP>
) : GrpcMethod<Flow<REQ>, Flow<RESP>> {

    /**
     * Invoke the handler with a Flow of requests, returning a Flow of responses.
     */
    suspend fun invoke(requests: Flow<REQ>): Flow<RESP> = handler(requests)
}

/**
 * Create a unary method.
 */
inline fun <reified REQ, reified RESP> unaryMethod(
    name: String,
    serviceName: String,
    noinline handler: suspend (REQ) -> RESP
): UnaryMethod<REQ, RESP> {
    val descriptor = GrpcMethodDescriptor(
        name = name,
        fullName = "$serviceName/$name",
        type = GrpcMethodType.UNARY,
        inputType = REQ::class.simpleName ?: "Unknown",
        outputType = RESP::class.simpleName ?: "Unknown"
    )
    return UnaryMethod(descriptor, handler)
}

/**
 * Create a server streaming method.
 */
inline fun <reified REQ, reified RESP> serverStreamingMethod(
    name: String,
    serviceName: String,
    noinline handler: suspend (REQ) -> Flow<RESP>
): ServerStreamingMethod<REQ, RESP> {
    val descriptor = GrpcMethodDescriptor(
        name = name,
        fullName = "$serviceName/$name",
        type = GrpcMethodType.SERVER_STREAMING,
        inputType = REQ::class.simpleName ?: "Unknown",
        outputType = RESP::class.simpleName ?: "Unknown"
    )
    return ServerStreamingMethod(descriptor, handler)
}

/**
 * Create a client streaming method.
 */
inline fun <reified REQ, reified RESP> clientStreamingMethod(
    name: String,
    serviceName: String,
    noinline handler: suspend (Flow<REQ>) -> RESP
): ClientStreamingMethod<REQ, RESP> {
    val descriptor = GrpcMethodDescriptor(
        name = name,
        fullName = "$serviceName/$name",
        type = GrpcMethodType.CLIENT_STREAMING,
        inputType = REQ::class.simpleName ?: "Unknown",
        outputType = RESP::class.simpleName ?: "Unknown"
    )
    return ClientStreamingMethod(descriptor, handler)
}

/**
 * Create a bidirectional streaming method.
 */
inline fun <reified REQ, reified RESP> bidiStreamingMethod(
    name: String,
    serviceName: String,
    noinline handler: suspend (Flow<REQ>) -> Flow<RESP>
): BidiStreamingMethod<REQ, RESP> {
    val descriptor = GrpcMethodDescriptor(
        name = name,
        fullName = "$serviceName/$name",
        type = GrpcMethodType.BIDI_STREAMING,
        inputType = REQ::class.simpleName ?: "Unknown",
        outputType = RESP::class.simpleName ?: "Unknown"
    )
    return BidiStreamingMethod(descriptor, handler)
}
